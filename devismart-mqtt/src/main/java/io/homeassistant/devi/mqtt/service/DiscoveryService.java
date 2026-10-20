package io.homeassistant.devi.mqtt.service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * Interactive pairing for running the jar by hand on a computer:
 * {@code java -cp ha-devi-mqtt.jar io.homeassistant.devi.mqtt.service.DiscoveryService}
 *
 * Inside the Home Assistant app, pairing is done with the "pairing_code" option instead (see {@link AddonPairing}).
 */
public class DiscoveryService {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("In the DEVIsmart app go to Settings -> Share house to generate a code.");
        System.out.print("Name shown in the DEVIsmart app [Home Assistant]: ");
        String name = scanner.nextLine().trim();
        if (name.isEmpty()) {
            name = "Home Assistant";
        }

        Path out = Paths.get(args.length > 0 ? args[0] : "devi_config.json");
        while (true) {
            System.out.print("Enter the code (digits only): ");
            String otp = scanner.nextLine();
            Pairing.Result result = Pairing.pair(otp, name, out);
            if (result == Pairing.Result.OK) {
                System.out.println("Done. Saved " + out.toAbsolutePath());
                System.exit(0);
            }
            System.out.println("That did not work. Generate a new code in the DEVIsmart app and try again.");
        }
    }
}
