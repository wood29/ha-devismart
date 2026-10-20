package io.homeassistant.devi.mqtt.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds availability to Home Assistant discovery payloads.
 *
 * Every entity becomes unavailable when the bridge itself is down (retained last will on
 * {@link MQTTService#BRIDGE_STATUS_TOPIC}). Entities that read from or control a thermostat also become unavailable
 * while that thermostat is offline, instead of showing frozen values.
 */
public final class DiscoveryPayloads {

    private DiscoveryPayloads() {
    }

    /** Connectivity entities must stay available while the thermostat is offline, or they could never show it. */
    private static boolean isConnectivityEntity(String topic) {
        return topic.contains("device_connected") || topic.contains("cloud_connected");
    }

    /**
     * @param topic discovery topic (placeholders may still be unresolved)
     * @param template the "template" object of a discovery template file
     * @return a copy with an {@code availability} list and {@code availability_mode: all}
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> withAvailability(String topic, Map<String, Object> template) {
        Map<String, Object> out = new LinkedHashMap<>(template);

        if (out.containsKey("availability")) {
            // A custom template defines its own list: just make sure the bridge status is part of it
            Object existing = out.get("availability");
            if (existing instanceof List) {
                List<Object> list = new ArrayList<>((List<Object>) existing);
                boolean hasBridge = list.stream().anyMatch(
                        a -> a instanceof Map && MQTTService.BRIDGE_STATUS_TOPIC.equals(((Map<?, ?>) a).get("topic")));
                if (!hasBridge) {
                    list.add(0, bridgeAvailability());
                }
                out.put("availability", list);
                out.putIfAbsent("availability_mode", "all");
            }
            return out;
        }

        List<Object> availability = new ArrayList<>();
        availability.add(bridgeAvailability());

        Object deviceTopic = out.remove("availability_topic");
        Object available = out.remove("payload_available");
        Object notAvailable = out.remove("payload_not_available");
        if (deviceTopic != null) {
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("topic", deviceTopic);
            device.put("payload_available", available != null ? available : "ON");
            device.put("payload_not_available", notAvailable != null ? notAvailable : "OFF");
            availability.add(device);
        } else if (!isConnectivityEntity(topic)) {
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("topic", "devi/state/${deviceSN}/binary_sensor_device_connected");
            device.put("payload_available", "ON");
            device.put("payload_not_available", "OFF");
            availability.add(device);
        }

        out.put("availability", availability);
        out.put("availability_mode", "all");
        return out;
    }

    private static Map<String, Object> bridgeAvailability() {
        Map<String, Object> bridge = new LinkedHashMap<>();
        bridge.put("topic", MQTTService.BRIDGE_STATUS_TOPIC);
        bridge.put("payload_available", MQTTService.BRIDGE_ONLINE);
        bridge.put("payload_not_available", MQTTService.BRIDGE_OFFLINE);
        return bridge;
    }
}
