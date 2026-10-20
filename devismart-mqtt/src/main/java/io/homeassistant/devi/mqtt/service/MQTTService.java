package io.homeassistant.devi.mqtt.service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MQTT side of the bridge.
 *
 * Reliability rules this class follows:
 * <ul>
 * <li>Paho's automatic reconnect is the only reconnect path. Nothing else ever calls connect() after start().</li>
 * <li>Every (re)connect re-subscribes and re-publishes discovery and the bridge "online" status; a reconnect also
 * re-sends the last known state of every topic, because the broker may have lost it or values changed meanwhile.</li>
 * <li>A retained last will on {@link #BRIDGE_STATUS_TOPIC} tells Home Assistant when the bridge dies.</li>
 * <li>Home Assistant's birth message ({@code homeassistant/status = online}) triggers a discovery re-publish.</li>
 * <li>No work is done on Paho's callback thread; it goes to a worker thread, so a slow or failing command can never
 * stall or close the MQTT connection.</li>
 * </ul>
 */
public class MQTTService {

    private static final Logger logger = LoggerFactory.getLogger(MQTTService.class);

    public static final String BRIDGE_STATUS_TOPIC = "devi/bridge/status";
    public static final String BRIDGE_ONLINE = "online";
    public static final String BRIDGE_OFFLINE = "offline";
    public static final String HA_STATUS_TOPIC = "homeassistant/status";

    static final String COMMANDS_TOPIC = "devi/command/#";
    private static final String STATE_PUBLISH_PREFIX = "devi/state/%s/";

    /** Identical state values are re-published at most this often (keeps the log and broker quiet). */
    private static final long DUPLICATE_SUPPRESSION_MS = 10 * 60 * 1000L;

    private final String broker;
    private final String username;
    private final String password;

    private volatile Mediator inputCommandMediator;
    private volatile MqttClient client;

    /** Discovery config messages, keyed by topic. Re-published on reconnect and on HA birth. */
    private final Map<String, String> discoveryMessages = new ConcurrentHashMap<>();
    /** Last known state per topic. Re-published on reconnect. */
    private final Map<String, String> lastState = new ConcurrentHashMap<>();
    private final Map<String, Long> lastStateSentAt = new ConcurrentHashMap<>();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mqtt-worker");
        t.setDaemon(true);
        return t;
    });

    /** When the connection was last lost; 0 while connected. Read by the liveness monitor. */
    private final AtomicLong disconnectedSince = new AtomicLong(System.currentTimeMillis());

    public MQTTService(String broker, String port, String username, String password) {
        this.broker = "tcp://" + broker + ":" + port;
        this.username = username;
        this.password = password;
    }

    /** Parses {@code devi/command/<device>/<channel>}. Returns null for anything else. */
    static InputCommand parseCommandTopic(String topicFullPath, String payload) {
        if (topicFullPath == null) {
            return null;
        }
        String[] parts = topicFullPath.split("/", -1);
        if (parts.length != 4 || !"devi".equals(parts[0]) || !"command".equals(parts[1]) || parts[2].isEmpty()
                || parts[3].isEmpty()) {
            return null;
        }

        InputCommand inputCommand = new InputCommand();
        inputCommand.deviceId = parts[2];
        inputCommand.sensorName = parts[3];
        inputCommand.topicFullPath = topicFullPath;
        inputCommand.payload = payload == null ? "" : payload.trim();
        return inputCommand;
    }

    public void registerMediator(Mediator inputCommandMediator) {
        this.inputCommandMediator = inputCommandMediator;
    }

    /**
     * Connects to the broker, retrying with backoff until it succeeds. Paho handles every later reconnect.
     *
     * @param giveUpAfterMs stop trying and throw after this long, so the caller can exit and the Supervisor
     *            restarts the app
     */
    public void start(long giveUpAfterMs) throws MqttException, InterruptedException {
        MqttClient c = new MqttClient(broker, MqttClient.generateClientId(), new MemoryPersistence());

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        options.setMaxReconnectDelay(60);
        options.setKeepAliveInterval(30);
        options.setConnectionTimeout(15);
        options.setMaxInflight(1000);
        if (username != null && !username.isEmpty()) {
            options.setUserName(username);
        }
        if (password != null && !password.isEmpty()) {
            options.setPassword(password.toCharArray());
        }
        options.setWill(BRIDGE_STATUS_TOPIC, BRIDGE_OFFLINE.getBytes(StandardCharsets.UTF_8), 1, true);

        c.setCallback(new Callback());
        // Never block forever on a half-open connection (publishes come from thermostat threads)
        c.setTimeToWait(10_000);
        client = c;

        long started = System.currentTimeMillis();
        long delay = 2000;
        while (true) {
            try {
                c.connect(options);
                return; // connectComplete() does the rest
            } catch (MqttException e) {
                if (e.getReasonCode() == MqttException.REASON_CODE_FAILED_AUTHENTICATION
                        || e.getReasonCode() == MqttException.REASON_CODE_NOT_AUTHORIZED) {
                    logger.error("MQTT broker rejected the username/password. Check the app configuration.");
                } else {
                    logger.warn("Cannot connect to MQTT broker {}: {}", broker, e.toString());
                }
                if (System.currentTimeMillis() - started > giveUpAfterMs) {
                    throw e;
                }
                Thread.sleep(delay);
                delay = Math.min(delay * 2, 60000);
            }
        }
    }

    private volatile boolean subscribed = false;

    /** True while connected to the broker and subscribed to the command topics. */
    public boolean isConnected() {
        MqttClient c = client;
        return c != null && c.isConnected() && subscribed;
    }

    /** How long the broker connection has been down, or 0 when connected. */
    public long disconnectedForMs() {
        long since = disconnectedSince.get();
        return since == 0 ? 0 : System.currentTimeMillis() - since;
    }

    /** Publishes the bridge's "offline" status and disconnects cleanly. Used on normal shutdown. */
    public void stop() {
        MqttClient c = client;
        try {
            if (c != null && c.isConnected()) {
                publish(BRIDGE_STATUS_TOPIC, BRIDGE_OFFLINE, true);
                c.disconnect(2000);
            }
        } catch (Exception e) {
            logger.debug("Error during MQTT shutdown: {}", e.toString());
        }
    }

    public void SendSensorData(String sensorId, String sensorName, String sensorValue, boolean setRetain) {
        if (sensorValue == null) {
            return;
        }
        String topic = String.format(STATE_PUBLISH_PREFIX, sensorId) + sensorName;

        String previous = lastState.put(topic, sensorValue);
        long now = System.currentTimeMillis();
        Long sentAt = lastStateSentAt.get(topic);
        if (sensorValue.equals(previous) && sentAt != null && now - sentAt < DUPLICATE_SUPPRESSION_MS) {
            return;
        }
        if (publish(topic, sensorValue, setRetain)) {
            lastStateSentAt.put(topic, now);
            logger.debug("Published {} = {}", topic, sensorValue);
        }
    }

    public void SendDiscoveryMessage(String topic, String payload) {
        discoveryMessages.put(topic, payload);
        publish(topic, payload, true);
    }

    /**
     * Publishes without ever triggering a reconnect. If the client is disconnected the message is dropped; the
     * last known state is re-sent once Paho has reconnected.
     */
    private boolean publish(String topic, String payload, boolean retained) {
        MqttClient c = client;
        if (c == null || !c.isConnected()) {
            return false;
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            message.setRetained(retained);
            c.publish(topic, message);
            return true;
        } catch (MqttException e) {
            logger.warn("Failed to publish to {}: {}", topic, e.toString());
            return false;
        }
    }

    private void onConnected(boolean reconnect) throws InterruptedException {
        MqttClient c = client;
        // Without the subscription, commands would be ignored silently, so keep trying while connected.
        // If it never succeeds, subscribed stays false and the liveness monitor restarts the app.
        subscribed = false;
        while (c.isConnected()) {
            try {
                c.subscribe(COMMANDS_TOPIC, 1);
                subscribed = true;
                break;
            } catch (MqttException e) {
                logger.error("Subscribing to commands failed, retrying: {}", e.toString());
                Thread.sleep(5000);
            }
        }
        if (!subscribed) {
            return; // connection dropped again; connectComplete() will run once more
        }
        try {
            // Optional: lets us re-publish discovery when Home Assistant restarts
            c.subscribe(HA_STATUS_TOPIC, 1);
        } catch (MqttException e) {
            logger.warn("Could not subscribe to {} ({}); discovery is still re-sent on every reconnect",
                    HA_STATUS_TOPIC, e.toString());
        }

        publish(BRIDGE_STATUS_TOPIC, BRIDGE_ONLINE, true);
        republishDiscovery();
        if (reconnect) {
            for (Map.Entry<String, String> e : lastState.entrySet()) {
                if (publish(e.getKey(), e.getValue(), true)) {
                    lastStateSentAt.put(e.getKey(), System.currentTimeMillis());
                }
            }
        }
        logger.info("MQTT {} to {}: subscribed, published {} discovery and {} state topics",
                reconnect ? "reconnected" : "connected", broker, discoveryMessages.size(),
                reconnect ? lastState.size() : 0);
    }

    private void republishDiscovery() {
        for (Map.Entry<String, String> e : discoveryMessages.entrySet()) {
            publish(e.getKey(), e.getValue(), true);
        }
    }

    private void onMessage(String topic, String payload) {
        if (HA_STATUS_TOPIC.equals(topic)) {
            if ("online".equals(payload)) {
                logger.info("Home Assistant came online, re-publishing discovery");
                republishDiscovery();
            }
            return;
        }

        InputCommand inputCmd = parseCommandTopic(topic, payload);
        if (inputCmd == null) {
            logger.warn("Ignoring message on unexpected topic {}", topic);
            return;
        }
        logger.info("Command: {}", inputCmd);

        Mediator mediator = inputCommandMediator;
        if (mediator != null) {
            mediator.notify(this, inputCmd);
        }
    }

    private class Callback implements MqttCallbackExtended {
        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            disconnectedSince.set(0);
            // Never call blocking client methods on Paho's callback thread; it can deadlock.
            worker.execute(() -> {
                try {
                    onConnected(reconnect);
                } catch (Exception e) {
                    logger.error("Error after MQTT connect", e);
                }
            });
        }

        @Override
        public void connectionLost(Throwable cause) {
            subscribed = false;
            disconnectedSince.compareAndSet(0, System.currentTimeMillis());
            logger.warn("MQTT connection lost ({}); reconnecting automatically", String.valueOf(cause));
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            // Must never throw: an exception here makes Paho close the connection.
            try {
                final String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                worker.execute(() -> {
                    try {
                        onMessage(topic, payload);
                    } catch (Exception e) {
                        logger.error("Error handling message on {}", topic, e);
                    }
                });
            } catch (Exception e) {
                logger.error("Error queuing message on {}", topic, e);
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // nothing to do
        }
    }
}
