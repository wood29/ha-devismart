package io.homeassistant.devi.mqtt.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;

/**
 * Entry point inside the Home Assistant app.
 *
 * Arguments: options.json, devi_config.json, templates folder. A legacy /config/mqtt_config.json still overrides
 * the MQTT settings from the app configuration, as in earlier versions.
 */
public class AddonMain {

    private static final Logger logger = LoggerFactory.getLogger(AddonMain.class);

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(
                (t, e) -> logger.error("Unexpected error in thread {}", t.getName(), e));

        Path optionsFile = Paths.get(args.length > 0 ? args[0] : "/data/options.json");
        Path deviConfig = Paths.get(args.length > 1 ? args[1] : "/config/devi_config.json");
        Path templates = Paths.get(args.length > 2 ? args[2] : "/app/auto-discovery-templates");

        AddonOptions opts;
        try {
            opts = AddonOptions.load(optionsFile);
        } catch (Exception e) {
            ConsoleRunner.parkForever("Cannot read the app configuration " + optionsFile + ": " + e);
            return;
        }
        applyLogLevel(opts.logLevel);

        Path legacyMqtt = Paths.get("/config/mqtt_config.json");
        AddonOptions mqtt = opts;
        if (Files.exists(legacyMqtt)) {
            logger.info("Using MQTT settings from {} (the app configuration's MQTT fields are ignored)", legacyMqtt);
            try {
                mqtt = AddonOptions.load(legacyMqtt);
            } catch (Exception e) {
                ConsoleRunner.parkForever(legacyMqtt + " is damaged (" + e + "). Fix or delete it.");
                return;
            }
        }

        Path customTemplates = Paths.get("/config/auto-discovery-templates");
        if (Files.isDirectory(customTemplates)) {
            logger.info("Using custom discovery templates from {}", customTemplates);
            templates = customTemplates;
        }

        logger.info("Self-healing restarts need 'Watchdog' switched on in the app's Info tab.");
        ConsoleRunner.run(mqtt, deviConfig, templates);
    }

    /** Sets the root log level from the app configuration (trace, debug, info, warning, error). */
    static void applyLogLevel(String level) {
        String l = level == null ? "info" : level.trim().toLowerCase();
        if (l.equals("warning")) {
            l = "warn";
        }
        try {
            ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory
                    .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.toLevel(l, Level.INFO));
        } catch (ClassCastException e) {
            // another logging backend; keep its defaults
        }
    }
}
