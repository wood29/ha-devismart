package io.homeassistant.devi.mqtt.service;

import static io.homeassistant.binding.danfoss.internal.DanfossBindingConstants.*;

import java.util.Map;
import java.util.Set;

/**
 * Checks values arriving over MQTT before they are sent to a thermostat.
 *
 * Home Assistant enforces min/max only in its own UI; anything published straight to MQTT (an automation, a typo in
 * Developer Tools) would otherwise reach the thermostat unchanged. Ranges follow the DEVIreg Smart datasheet:
 * room 5-35 °C, floor 5-45 °C, frost protection 5-9 °C, max floor 20-45 °C.
 */
public final class CommandValidator {

    private CommandValidator() {
    }

    private static final Map<String, double[]> TEMPERATURE_RANGES = Map.ofEntries(
            Map.entry(CHANNEL_SETPOINT_COMFORT, new double[] { 5, 45 }),
            Map.entry(CHANNEL_SETPOINT_ECONOMY, new double[] { 5, 45 }),
            Map.entry(CHANNEL_SETPOINT_MANUAL, new double[] { 5, 45 }),
            Map.entry(CHANNEL_SETPOINT_TEMPORARY, new double[] { 5, 45 }),
            Map.entry(CHANNEL_SETPOINT_AWAY, new double[] { 5, 45 }),
            Map.entry(CHANNEL_SETPOINT_ANTIFREEZE, new double[] { 5, 9 }),
            Map.entry(CHANNEL_SETPOINT_MIN_FLOOR, new double[] { 10, 35 }),
            Map.entry(CHANNEL_SETPOINT_MAX_FLOOR, new double[] { 20, 45 }),
            Map.entry(CHANNEL_SETPOINT_WARNING, new double[] { 5, 45 }),
            Map.entry(CHANNEL_SET_TARGET_TEMP, new double[] { 5, 45 }));

    /** Modes accepted on the control-mode select and the climate preset/mode topics. */
    public static final String MODE_RESUME = "RESUME";
    private static final Set<String> MODES = Set.of(CONTROL_MODE_MANUAL, CONTROL_MODE_OVERRIDE,
            CONTROL_MODE_SCHEDULE, CONTROL_MODE_VACATION, CONTROL_MODE_PAUSE, CONTROL_MODE_OFF, MODE_RESUME);

    /** True for channels whose payload is a temperature. */
    public static boolean isTemperatureChannel(String channel) {
        return TEMPERATURE_RANGES.containsKey(channel);
    }

    /**
     * Parses a temperature, clamps it to the channel's range and rounds it to 0.5 °C.
     *
     * @return the value to send, or null when the payload is not a usable number
     */
    public static Double temperature(String channel, String payload) {
        double[] range = TEMPERATURE_RANGES.get(channel);
        if (range == null || payload == null) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(payload.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        value = Math.max(range[0], Math.min(range[1], value));
        return Math.round(value * 2) / 2.0;
    }

    /** Parses a brightness percentage 0-100, or null when unusable. */
    public static Integer brightness(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(payload.trim());
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return null;
            }
            return (int) Math.round(Math.max(0, Math.min(100, value)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Accepts ON/OFF (case-insensitive), or null for anything else. */
    public static Boolean onOff(String payload) {
        if (payload == null) {
            return null;
        }
        String p = payload.trim();
        if (p.equalsIgnoreCase("ON")) {
            return Boolean.TRUE;
        }
        if (p.equalsIgnoreCase("OFF")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** Normalises a mode name, or null for an unknown mode. "SET OVERRIDE" is accepted for old dashboards. */
    public static String mode(String payload) {
        if (payload == null) {
            return null;
        }
        String p = payload.trim().toUpperCase();
        if (p.equals("SET OVERRIDE")) {
            p = CONTROL_MODE_OVERRIDE;
        }
        return MODES.contains(p) ? p : null;
    }
}
