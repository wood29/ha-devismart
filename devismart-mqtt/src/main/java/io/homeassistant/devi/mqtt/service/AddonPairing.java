package io.homeassistant.devi.mqtt.service;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs once at app start when no devi_config.json exists yet. Takes the code from the "pairing_code" option.
 *
 * Exit codes: 0 paired, 3 no code entered yet, 4 code rejected, 5 other failure. The start script waits quietly
 * on anything but 0, so a bad code is never retried in a loop.
 */
public class AddonPairing {

    private static final Logger logger = LoggerFactory.getLogger(AddonPairing.class);

    public static void main(String[] args) {
        Path optionsFile = Paths.get(args.length > 0 ? args[0] : "/data/options.json");
        Path out = Paths.get(args.length > 1 ? args[1] : "/config/devi_config.json");

        AddonOptions opts;
        try {
            opts = AddonOptions.load(optionsFile);
        } catch (Exception e) {
            logger.error("Cannot read the app configuration: {}", e.toString());
            System.exit(5);
            return;
        }
        AddonMain.applyLogLevel(opts.logLevel);

        if (opts.pairingCode.replaceAll("[^0-9]", "").isEmpty()) {
            logger.info("------------------------------------------------------------------");
            logger.info("Not paired yet. To connect your thermostats:");
            logger.info(" 1. In the DEVIsmart app open Settings -> Share house and generate a code.");
            logger.info(" 2. In Home Assistant open this app's Configuration tab, paste the code into");
            logger.info("    'pairing_code' and save.");
            logger.info(" 3. Restart the app. Approve the request in the DEVIsmart app if it asks.");
            logger.info("------------------------------------------------------------------");
            System.exit(3);
            return;
        }

        Pairing.Result result = Pairing.pair(opts.pairingCode, opts.pairingName, out);
        switch (result) {
            case OK:
                logger.info("Paired. You can clear 'pairing_code' in the configuration; it is not used again.");
                System.exit(0);
                break;
            case BAD_CODE:
                logger.error("Generate a NEW code in the DEVIsmart app, enter it in the configuration and restart.");
                System.exit(4);
                break;
            default:
                logger.error("Pairing failed. Check the internet connection, then restart the app to try again "
                        + "(you may need a new code).");
                System.exit(5);
        }
    }
}
