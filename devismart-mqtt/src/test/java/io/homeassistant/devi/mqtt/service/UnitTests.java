package io.homeassistant.devi.mqtt.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

class UnitTests {

    // ---- MQTT command topics -------------------------------------------------------------------

    @Test
    void parsesValidCommandTopic() {
        InputCommand c = MQTTService.parseCommandTopic("devi/command/10159384/setpoint_comfort", " 21.5 ");
        assertNotNull(c);
        assertEquals("10159384", c.deviceId);
        assertEquals("setpoint_comfort", c.sensorName);
        assertEquals("21.5", c.payload);
    }

    @Test
    void rejectsMalformedTopicsWithoutThrowing() {
        assertNull(MQTTService.parseCommandTopic("devi/command/x", "1"));
        assertNull(MQTTService.parseCommandTopic("devi/command/a/b/c", "1"));
        assertNull(MQTTService.parseCommandTopic("devi/command//setpoint_comfort", "1"));
        assertNull(MQTTService.parseCommandTopic("devi/state/1/setpoint_comfort", "1"));
        assertNull(MQTTService.parseCommandTopic("", "1"));
        assertNull(MQTTService.parseCommandTopic(null, "1"));
    }

    // ---- Value validation ----------------------------------------------------------------------

    @Test
    void clampsAndRoundsTemperatures() {
        assertEquals(21.5, CommandValidator.temperature("setpoint_comfort", "21.4"));
        assertEquals(21.0, CommandValidator.temperature("setpoint_comfort", "21,2"));
        assertEquals(45.0, CommandValidator.temperature("setpoint_comfort", "999"));
        assertEquals(5.0, CommandValidator.temperature("setpoint_comfort", "-40"));
        assertEquals(9.0, CommandValidator.temperature("setpoint_antifreeze", "20"));
        assertEquals(20.0, CommandValidator.temperature("setpoint_max_floor", "10"));
        assertEquals(10.0, CommandValidator.temperature("setpoint_min_floor", "2"));
    }

    @Test
    void rejectsUnusableTemperatures() {
        assertNull(CommandValidator.temperature("setpoint_comfort", "abc"));
        assertNull(CommandValidator.temperature("setpoint_comfort", "NaN"));
        assertNull(CommandValidator.temperature("setpoint_comfort", "Infinity"));
        assertNull(CommandValidator.temperature("setpoint_comfort", ""));
        assertNull(CommandValidator.temperature("setpoint_comfort", null));
        assertNull(CommandValidator.temperature("not_a_channel", "20"));
    }

    @Test
    void validatesSwitchesBrightnessAndModes() {
        assertEquals(Boolean.TRUE, CommandValidator.onOff("on"));
        assertEquals(Boolean.FALSE, CommandValidator.onOff("OFF"));
        assertNull(CommandValidator.onOff("maybe"));
        assertEquals(100, CommandValidator.brightness("300"));
        assertEquals(0, CommandValidator.brightness("-5"));
        assertNull(CommandValidator.brightness("bright"));
        assertEquals("OVERRIDE", CommandValidator.mode("SET OVERRIDE"));
        assertEquals("SCHEDULE", CommandValidator.mode("schedule"));
        assertEquals("RESUME", CommandValidator.mode("RESUME"));
        assertNull(CommandValidator.mode("TURBO"));
        assertNull(CommandValidator.mode(""));
    }

    // ---- Discovery and availability -----------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void addsBridgeAndDeviceAvailability() {
        Map<String, Object> t = new Gson().fromJson(
                "{\"name\":\"x\",\"availability_topic\":\"devi/state/1/binary_sensor_device_connected\","
                        + "\"payload_available\":\"ON\",\"payload_not_available\":\"OFF\"}",
                Map.class);
        Map<String, Object> out = DiscoveryPayloads.withAvailability("homeassistant/climate/1_x/config", t);
        assertFalse(out.containsKey("availability_topic"));
        assertEquals("all", out.get("availability_mode"));
        List<Map<String, Object>> a = (List<Map<String, Object>>) out.get("availability");
        assertEquals(2, a.size());
        assertEquals(MQTTService.BRIDGE_STATUS_TOPIC, a.get(0).get("topic"));
        assertEquals("devi/state/1/binary_sensor_device_connected", a.get(1).get("topic"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectivityEntitiesOnlyDependOnTheBridge() {
        Map<String, Object> out = DiscoveryPayloads.withAvailability(
                "homeassistant/binary_sensor/1_binary_sensor_device_connected/config", Map.of("name", "x"));
        List<Map<String, Object>> a = (List<Map<String, Object>>) out.get("availability");
        assertEquals(1, a.size());
        assertEquals(MQTTService.BRIDGE_STATUS_TOPIC, a.get(0).get("topic"));
    }

    @Test
    void allShippedTemplatesLoadAndHaveAvailability() throws Exception {
        List<ConsoleRunner.Template> templates = ConsoleRunner.loadTemplates(Paths.get("auto-discovery-templates"));
        assertTrue(templates.size() >= 40, "expected the full template set, got " + templates.size());
        for (ConsoleRunner.Template t : templates) {
            assertTrue(t.template.containsKey("availability"), t.topic);
            assertFalse(t.template.containsKey("availability_topic"), t.topic);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void climateEntityIsValidForHomeAssistant() throws Exception {
        ConsoleRunner.Template climate = ConsoleRunner.loadTemplates(Paths.get("auto-discovery-templates")).stream()
                .filter(t -> t.topic.startsWith("homeassistant/climate/")).findFirst().orElseThrow();
        Map<String, Object> c = climate.template;
        assertFalse(c.containsKey("device_class"), "climate has no device_class in HA");
        List<String> presets = (List<String>) c.get("preset_modes");
        assertFalse(presets.contains("OFF"), "OFF is a mode, not a preset");
        assertFalse(presets.contains("SET OVERRIDE"));
        assertFalse(presets.contains("none"), "HA forbids 'none' in preset_modes");
        assertTrue(((String) c.get("mode_command_template")).contains("RESUME"));
        assertEquals("devi/state/${deviceSN}/hvac_action", c.get("action_topic"));
    }

    // ---- devi_config.json ---------------------------------------------------------------------

    @Test
    void roomsWithoutSerialNumberGetStableFallbackId() {
        String json = "{\"userName\":\"u\",\"privateKey\":\"k\",\"rooms\":["
                + "{\"name\":\"Bath\",\"devicePeerID\":\"ABCDEF0123456789ABCD\",\"serialNumber\":null},"
                + "{\"name\":\"Hall\",\"devicePeerID\":\"1111\",\"serialNumber\":\"10159384\"},"
                + "{\"name\":\"Broken\",\"serialNumber\":\"1\"}]}";
        List<ConsoleRunner.Room> rooms = ConsoleRunner.readRooms(JsonParser.parseString(json).getAsJsonObject());
        assertEquals(2, rooms.size());
        assertEquals("pabcdef012345", rooms.get(0).deviceId);
        assertEquals("10159384", rooms.get(1).deviceId);
    }

    @Test
    void configFileIsWrittenPrivately(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("devi_config.json");
        Pairing.writePrivate(out, "{\"privateKey\":\"secret\"}");
        Pairing.writePrivate(out, "{\"privateKey\":\"secret2\"}"); // replaces cleanly
        assertEquals("{\"privateKey\":\"secret2\"}", Files.readString(out));
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(out);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temp files left behind");
        }
    }

    @Test
    void readsAppOptions(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("options.json");
        Files.writeString(f, "{\"host\":\"core-mosquitto\",\"port\":1883,\"user\":\"u\",\"password\":\"p\","
                + "\"pairing_code\":\" 123-456 789 \",\"pairing_name\":\"\"}");
        AddonOptions o = AddonOptions.load(f);
        assertEquals("1883", o.port);
        assertEquals("123-456 789", o.pairingCode);
        assertEquals("Home Assistant", o.pairingName);
        assertEquals("info", o.logLevel);
    }
}
