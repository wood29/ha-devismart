package io.homeassistant.devi.mqtt.service;

import static io.homeassistant.binding.danfoss.internal.DanfossBindingConstants.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.text.StringSubstitutor;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.homeassistant.binding.danfoss.internal.DanfossBindingConfig;
import io.homeassistant.binding.danfoss.internal.DeviRegHandler;
import io.homeassistant.binding.danfoss.internal.SDGPeerConnector;

/**
 * The bridge: connects to MQTT, publishes Home Assistant discovery and connects to every thermostat.
 *
 * Run inside the Home Assistant app through {@link AddonMain}, or by hand:
 * {@code java -jar ha-devi-mqtt.jar --mqtt-config mqtt_config.json --devi-config devi_config.json}
 */
public class ConsoleRunner {

    static class Template {
        String topic;
        Map<String, Object> template;
    }

    private static final Logger logger = LoggerFactory.getLogger(ConsoleRunner.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** Restart when the MQTT broker has been unreachable this long. */
    static final long MQTT_DOWN_LIMIT_MS = 10 * 60 * 1000L;
    /** Restart when no thermostat has sent anything for this long (stuck cloud session). */
    static final long THERMOSTATS_SILENT_LIMIT_MS = 30 * 60 * 1000L;

    /** Exit codes. Any non-zero exit makes the Supervisor watchdog restart the app. */
    static final int EXIT_CONFIG = 2;
    static final int EXIT_MQTT_DOWN = 3;
    static final int EXIT_THERMOSTATS_SILENT = 4;

    private static MQTTService mqttService;
    private static CommandMediator commandMediator;

    public static void main(String[] args) throws Exception {
        Map<String, String> params = parseParameters(args);
        if (params.containsKey("help")) {
            printHelp();
            return;
        }

        AddonOptions mqtt;
        Path mqttConfig = Paths.get(params.getOrDefault("mqtt-config", "mqtt_config.json"));
        try {
            mqtt = AddonOptions.load(mqttConfig);
        } catch (Exception e) {
            logger.error("Cannot read MQTT configuration {}: {}", mqttConfig, e.toString());
            System.exit(EXIT_CONFIG);
            return;
        }
        run(mqtt, Paths.get(params.getOrDefault("devi-config", "devi_config.json")),
                Paths.get(params.getOrDefault("auto-discovery-templates", "auto-discovery-templates")));
    }

    /** One thermostat entry from devi_config.json. */
    static class Room {
        String peerId;
        String deviceId;
        String name;
    }

    static List<Room> readRooms(JsonObject deviConfig) {
        List<Room> rooms = new ArrayList<>();
        if (!deviConfig.has("rooms") || !deviConfig.get("rooms").isJsonArray()) {
            return rooms;
        }
        for (JsonElement e : deviConfig.getAsJsonArray("rooms")) {
            JsonObject o = e.getAsJsonObject();
            Room r = new Room();
            r.peerId = o.has("devicePeerID") && !o.get("devicePeerID").isJsonNull()
                    ? o.get("devicePeerID").getAsString()
                    : null;
            if (r.peerId == null || r.peerId.isEmpty()) {
                continue;
            }
            String sn = o.has("serialNumber") && !o.get("serialNumber").isJsonNull()
                    ? o.get("serialNumber").getAsString().trim()
                    : "";
            // The serial number is the device ID in MQTT topics and entity IDs. If pairing could not read it,
            // use a stable ID derived from the thermostat's cloud ID instead of crashing.
            r.deviceId = sn.isEmpty() ? "p" + r.peerId.substring(0, Math.min(12, r.peerId.length())).toLowerCase()
                    : sn;
            r.name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : r.deviceId;
            rooms.add(r);
        }
        return rooms;
    }

    public static void run(AddonOptions mqtt, Path deviConfigPath, Path templatesPath) throws Exception {
        logger.info("Starting DEVIreg Smart MQTT bridge");

        // Read and check everything before connecting anywhere
        JsonObject deviConfig;
        String userName;
        String privateKey;
        List<Room> rooms;
        try {
            deviConfig = JsonParser.parseString(Files.readString(deviConfigPath, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            userName = deviConfig.get("userName").getAsString();
            privateKey = deviConfig.get("privateKey").getAsString();
            rooms = readRooms(deviConfig);
        } catch (Exception e) {
            logger.error("{} is missing or damaged ({}). Delete it and pair again.", deviConfigPath, e.toString());
            System.exit(EXIT_CONFIG);
            return;
        }
        if (rooms.isEmpty()) {
            logger.error("{} contains no thermostats. Delete it and pair again.", deviConfigPath);
            System.exit(EXIT_CONFIG);
            return;
        }

        List<Template> templates = loadTemplates(templatesPath);
        if (templates.isEmpty()) {
            logger.error("No discovery templates found in {}", templatesPath);
            System.exit(EXIT_CONFIG);
            return;
        }

        commandMediator = new CommandMediator();
        mqttService = new MQTTService(mqtt.host, mqtt.port, mqtt.user, mqtt.password);
        mqttService.registerMediator(commandMediator); // before connecting, so no command is ever dropped

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down");
            mqttService.stop();
        }, "shutdown"));

        try {
            mqttService.start(MQTT_DOWN_LIMIT_MS);
        } catch (Exception e) {
            logger.error("Giving up on the MQTT broker after {} minutes: {}", MQTT_DOWN_LIMIT_MS / 60000, e.toString());
            System.exit(EXIT_MQTT_DOWN);
            return;
        }

        int deviceNumber = 0;
        for (Room room : rooms) {
            Map<String, String> valuesMap = new HashMap<>();
            valuesMap.put("deviceSN", room.deviceId);
            valuesMap.put("deviceNumber", String.valueOf(deviceNumber++));
            publishDiscovery(templates, valuesMap);

            logger.info("Connecting to thermostat '{}' ({})", room.name, room.deviceId);
            handleThermostat(room.peerId, userName, privateKey, room.deviceId);
        }

        startLivenessMonitor();
    }

    /**
     * Exits (so the Supervisor restarts the app) when the bridge is alive but not doing its job. A restart
     * rebuilds both the MQTT and the cloud connection from scratch.
     */
    private static void startLivenessMonitor() {
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "liveness");
            t.setDaemon(true);
            return t;
        });
        final long[] mqttBadSince = { 0 };
        monitor.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                if (mqttService.isConnected()) {
                    mqttBadSince[0] = 0;
                } else if (mqttBadSince[0] == 0) {
                    mqttBadSince[0] = now;
                } else if (now - mqttBadSince[0] > MQTT_DOWN_LIMIT_MS) {
                    logger.error("MQTT has not worked for {} minutes, restarting the app", MQTT_DOWN_LIMIT_MS / 60000);
                    System.exit(EXIT_MQTT_DOWN);
                }

                long silent = SDGPeerConnector.millisSinceAnyPacket();
                if (silent > THERMOSTATS_SILENT_LIMIT_MS) {
                    logger.error("No thermostat has answered for {} minutes, restarting the app", silent / 60000);
                    System.exit(EXIT_THERMOSTATS_SILENT);
                }
            } catch (Throwable t) {
                logger.warn("Liveness check failed: {}", t.toString());
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    static List<Template> loadTemplates(Path folder) throws IOException {
        List<Template> result = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> paths = Files.walk(folder)) {
            files = paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).sorted()
                    .collect(Collectors.toList());
        }
        for (Path file : files) {
            try {
                Template t = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Template.class);
                if (t == null || t.topic == null || t.template == null) {
                    logger.warn("Skipping discovery template {}: missing topic or template", file.getFileName());
                    continue;
                }
                t.template = DiscoveryPayloads.withAvailability(t.topic, t.template);
                result.add(t);
            } catch (Exception e) {
                // One broken custom template must not stop the app
                logger.warn("Skipping invalid discovery template {}: {}", file.getFileName(), e.toString());
            }
        }
        logger.info("Loaded {} discovery templates from {}", result.size(), folder);
        return result;
    }

    private static void publishDiscovery(List<Template> templates, Map<String, String> valuesMap) {
        for (Template t : templates) {
            String topic = StringSubstitutor.replace(t.topic, valuesMap);
            String payload = StringSubstitutor.replace(gson.toJson(t.template), valuesMap);
            mqttService.SendDiscoveryMessage(topic, payload);
        }
    }

    private static void handleThermostat(String devicePeerID, String userName, String privateKey, String deviceSN) {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("privateKey", privateKey);
        configMap.put("peerId", devicePeerID);
        configMap.put("userName", userName);
        DanfossBindingConfig.update(configMap, new MockConfigurationAdmin());

        DeviRegHandler deviRegHandler = new DeviRegHandler(new MockThing(devicePeerID));
        commandMediator.addDeviRegHandler(deviceSN, deviRegHandler);

        // All state is retained, so Home Assistant has every value immediately after it restarts
        deviRegHandler.setCallback(
                new MockThingCallback((key, value) -> mqttService.SendSensorData(deviceSN, key, value, true)));

        deviRegHandler.initialize();

        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        // Ask for a value that is not pushed automatically
        ChannelUID refreshUptime = new ChannelUID(new ThingUID("cmd", "danfoss", "devismart"), CHANNEL_ON_TIME_TOTAL);
        deviRegHandler.handleCommand(refreshUptime, RefreshType.REFRESH);
    }

    private static Map<String, String> parseParameters(String[] args) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--auto-discovery-templates":
                case "--mqtt-config":
                case "--devi-config":
                    if (i + 1 < args.length) {
                        params.put(args[i].substring(2), args[++i]);
                    } else {
                        logger.error("Missing value for {}", args[i]);
                        params.put("help", "true");
                    }
                    break;
                default:
                    if (!args[i].equals("--help")) {
                        logger.error("Unknown parameter: {}", args[i]);
                    }
                    params.put("help", "true");
                    break;
            }
        }
        return params;
    }

    private static void printHelp() {
        System.out.println("Usage: java -jar ha-devi-mqtt.jar [options]");
        System.out.println("  --auto-discovery-templates <path>  Default: auto-discovery-templates");
        System.out.println("  --mqtt-config <path>               Default: mqtt_config.json");
        System.out.println("  --devi-config <path>               Default: devi_config.json");
    }
}
