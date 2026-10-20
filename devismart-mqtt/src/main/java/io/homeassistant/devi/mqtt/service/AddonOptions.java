package io.homeassistant.devi.mqtt.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The app configuration as written by the Supervisor to /data/options.json (or a legacy mqtt_config.json). */
public class AddonOptions {

    public String host = "core-mosquitto";
    public String port = "1883";
    public String user = "";
    public String password = "";
    public String pairingCode = "";
    public String pairingName = "Home Assistant";
    public String logLevel = "info";

    public static AddonOptions load(Path file) throws IOException {
        JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        AddonOptions opts = new AddonOptions();
        opts.host = str(o, "host", opts.host);
        opts.port = str(o, "port", opts.port);
        opts.user = str(o, "user", opts.user);
        opts.password = str(o, "password", opts.password);
        opts.pairingCode = str(o, "pairing_code", opts.pairingCode);
        opts.pairingName = str(o, "pairing_name", opts.pairingName);
        opts.logLevel = str(o, "log_level", opts.logLevel);
        if (opts.pairingName.isBlank()) {
            opts.pairingName = "Home Assistant";
        }
        return opts;
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        return e.getAsString().trim();
    }
}
