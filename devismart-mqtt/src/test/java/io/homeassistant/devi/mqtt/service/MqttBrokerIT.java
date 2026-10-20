package io.homeassistant.devi.mqtt.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs the bridge's MQTT side against a real Mosquitto broker (started by CI) and checks the failure cases from the
 * review: broker restart, malformed messages and a crashed bridge.
 *
 * Needs MQTT_TEST_HOST (and optionally MQTT_TEST_PORT). Set MQTT_TEST_CONTAINER to the broker's Docker container
 * name to also test a broker restart.
 */
@EnabledIfEnvironmentVariable(named = "MQTT_TEST_HOST", matches = ".+")
class MqttBrokerIT {

    private final String host = System.getenv("MQTT_TEST_HOST");
    private final String port = System.getenv().getOrDefault("MQTT_TEST_PORT", "1883");

    /** Collects commands the bridge would hand to a thermostat. */
    static class RecordingMediator implements Mediator {
        final BlockingQueue<InputCommand> commands = new LinkedBlockingQueue<>();

        @Override
        public void notify(Object sender, InputCommand event) {
            commands.add(event);
        }
    }

    /** A Home Assistant stand-in: remembers the latest value per topic. */
    static class Observer {
        final MqttClient client;
        final Map<String, String> latest = new ConcurrentHashMap<>();
        final List<String> discoveryTopics = new CopyOnWriteArrayList<>();

        Observer(String host, String port) throws Exception {
            client = new MqttClient("tcp://" + host + ":" + port, MqttClient.generateClientId(),
                    new MemoryPersistence());
            MqttConnectOptions o = new MqttConnectOptions();
            o.setAutomaticReconnect(true);
            o.setCleanSession(true);
            client.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String uri) {
                    new Thread(() -> {
                        try {
                            client.subscribe(new String[] { "devi/#", "homeassistant/#" }, new int[] { 1, 1 });
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
                }

                @Override
                public void connectionLost(Throwable cause) {
                }

                @Override
                public void messageArrived(String topic, MqttMessage m) {
                    latest.put(topic, new String(m.getPayload(), StandardCharsets.UTF_8));
                    if (topic.endsWith("/config")) {
                        discoveryTopics.add(topic);
                    }
                }

                @Override
                public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken t) {
                }
            });
            client.connect(o);
        }

        void publish(String topic, String payload) throws Exception {
            client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, false);
        }

        String await(String topic, String expected, long seconds) throws InterruptedException {
            long until = System.currentTimeMillis() + seconds * 1000;
            while (System.currentTimeMillis() < until) {
                String v = latest.get(topic);
                if (expected.equals(v)) {
                    return v;
                }
                Thread.sleep(100);
            }
            return latest.get(topic);
        }
    }

    private static InputCommand awaitCommand(RecordingMediator m, String payload, long seconds)
            throws InterruptedException {
        long until = System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < until) {
            InputCommand c = m.commands.poll(200, TimeUnit.MILLISECONDS);
            if (c != null && payload.equals(c.payload)) {
                return c;
            }
        }
        return null;
    }

    /** Sends the same command repeatedly until it arrives (the bridge may still be reconnecting). */
    private static InputCommand sendUntilReceived(Observer o, RecordingMediator m, String topic, String payload,
            long seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < until) {
            try {
                o.publish(topic, payload);
            } catch (Exception e) {
                // observer still reconnecting
            }
            InputCommand c = awaitCommand(m, payload, 2);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    @Test
    void commandsDiscoveryAndAvailabilitySurviveFailures() throws Exception {
        Observer ha = new Observer(host, port);
        RecordingMediator mediator = new RecordingMediator();

        MQTTService bridge = new MQTTService(host, port, "", "");
        bridge.registerMediator(mediator);
        bridge.start(60_000);
        bridge.SendDiscoveryMessage("homeassistant/sensor/T1_test/config", "{\"name\":\"t\"}");
        bridge.SendSensorData("T1", "temperature_current", "21.5", true);

        // 1. Online status, discovery and state arrive; commands are delivered
        assertEquals("online", ha.await(MQTTService.BRIDGE_STATUS_TOPIC, "online", 10));
        assertEquals("21.5", ha.await("devi/state/T1/temperature_current", "21.5", 10));
        assertNotNull(sendUntilReceived(ha, mediator, "devi/command/T1/setpoint_comfort", "20.5", 10),
                "command before failures");

        // 2. Malformed messages are ignored and do not break command handling (review H4)
        ha.publish("devi/command/x", "1");
        ha.publish("devi/command/a/b/c", "1");
        ha.publish("devi/command/T1/setpoint_comfort", "\u0000ÿ");
        assertNotNull(sendUntilReceived(ha, mediator, "devi/command/T1/setpoint_comfort", "21.0", 10),
                "command after malformed messages");
        assertTrue(bridge.isConnected());

        // 3. Home Assistant restart: discovery is re-published on its birth message (review M4)
        ha.discoveryTopics.clear();
        ha.publish(MQTTService.HA_STATUS_TOPIC, "online");
        long until = System.currentTimeMillis() + 10_000;
        while (ha.discoveryTopics.isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(100);
        }
        assertTrue(ha.discoveryTopics.contains("homeassistant/sensor/T1_test/config"), "discovery re-published");

        // 4. Broker restart: the bridge reconnects, re-subscribes and re-publishes everything (review H1, H2)
        String container = System.getenv("MQTT_TEST_CONTAINER");
        if (container != null && !container.isEmpty()) {
            ha.latest.clear();
            Process p = new ProcessBuilder("docker", "restart", container).inheritIO().start();
            assertEquals(0, p.waitFor(), "docker restart");

            assertEquals("online", ha.await(MQTTService.BRIDGE_STATUS_TOPIC, "online", 90),
                    "bridge online again after broker restart");
            assertEquals("21.5", ha.await("devi/state/T1/temperature_current", "21.5", 30),
                    "state re-published after broker restart");
            assertNotNull(sendUntilReceived(ha, mediator, "devi/command/T1/setpoint_comfort", "22.5", 60),
                    "command after broker restart");
        }

        // 5. Bridge dies without saying goodbye: the last will marks everything unavailable (review H3)
        MqttClient bridgeClient = getClient(bridge);
        bridgeClient.disconnectForcibly(0, 1000, false);
        assertEquals("offline", ha.await(MQTTService.BRIDGE_STATUS_TOPIC, "offline", 90),
                "last will published");

        ha.client.disconnect();
    }

    private static MqttClient getClient(MQTTService s) throws Exception {
        java.lang.reflect.Field f = MQTTService.class.getDeclaredField("client");
        f.setAccessible(true);
        return (MqttClient) f.get(s);
    }
}
