package io.homeassistant.devi.mqtt.service;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;

import io.github.sonic_amiga.opensdg.java.GridConnection;
import io.github.sonic_amiga.opensdg.java.PairingConnection;
import io.github.sonic_amiga.opensdg.java.PeerConnection;
import io.github.sonic_amiga.opensdg.java.SDG;
import io.homeassistant.binding.danfoss.internal.DanfossBindingConfig;
import io.homeassistant.binding.danfoss.internal.DeviRegHandler;
import io.homeassistant.binding.danfoss.internal.GridConnectionKeeper;
import io.homeassistant.binding.danfoss.internal.protocol.DominionConfiguration;

/**
 * Pairs with the DEVIsmart app using a one-time "Share house" code and writes devi_config.json.
 *
 * This is the logic of the original interactive DiscoveryService, made non-interactive so it can run inside the
 * app with the code taken from the app configuration: one attempt per code, a time limit on every step, and the
 * private key written to a file only the app can read and never printed.
 */
public final class Pairing {

    private static final Logger logger = LoggerFactory.getLogger(Pairing.class);

    /** How long to wait for the thermostats to report their serial numbers. */
    private static final long SERIAL_NUMBER_TIMEOUT_SEC = 120;

    private Pairing() {
    }

    /** Outcome of one pairing attempt. */
    public enum Result {
        OK,
        /** The code was rejected or expired: generate a new one in the DEVIsmart app. */
        BAD_CODE,
        /** Network or cloud problem: try again later. */
        FAILED
    }

    /**
     * @param otp the code shown by the DEVIsmart app, digits only (dashes and spaces are removed)
     * @param userName the name this connection gets in the DEVIsmart app's list of shared users
     * @param out where to write devi_config.json
     */
    public static Result pair(String otp, String userName, Path out) {
        String code = otp == null ? "" : otp.replaceAll("[^0-9]", "");
        if (code.isEmpty()) {
            logger.error("The pairing code is empty");
            return Result.BAD_CODE;
        }

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("userName", userName);
        ConfigurationAdmin configAdmin = new MockConfigurationAdmin();
        DanfossBindingConfig.update(configMap, configAdmin); // creates a new private key

        GridConnectionKeeper.AddUser();
        GridConnection grid;
        try {
            grid = GridConnectionKeeper.getConnection();
        } catch (Exception e) {
            logger.error("Could not connect to the Danfoss cloud: {}", e.toString());
            return Result.FAILED;
        }

        byte[] myPeerId = grid.getMyPeerId();
        if (myPeerId == null) {
            logger.error("The Danfoss cloud did not assign an ID to this connection");
            return Result.FAILED;
        }
        String myPeerIdHex = SDG.bin2hex(myPeerId);

        // Step 1: pair with the phone using the code
        byte[] phoneId;
        PairingConnection pairing = new PairingConnection();
        try {
            logger.info("Pairing with the DEVIsmart app...");
            pairing.pairWithRemote(grid, code);
            phoneId = pairing.getPeerId();
        } catch (RemoteException e) {
            logger.error("The DEVIsmart app refused the code. It is probably wrong or has expired.");
            return Result.BAD_CODE;
        } catch (Exception e) {
            logger.error("Pairing failed: {}", e.toString());
            return Result.FAILED;
        } finally {
            try {
                pairing.close();
            } catch (RuntimeException ignored) {
                // nothing to do
            }
        }

        // Step 2: ask the phone for the house configuration (list of thermostats)
        DominionConfiguration.Response house;
        PeerConnection cfg = new PeerConnection();
        try {
            logger.info("Paired. Receiving the house configuration from the DEVIsmart app...");
            cfg.connectToRemote(grid, phoneId, "dominion-configuration-1.0");
            DominionConfiguration.Request request = new DominionConfiguration.Request(userName, myPeerIdHex);
            cfg.sendData(new Gson().toJson(request).getBytes(StandardCharsets.UTF_8));
            byte[] data = receiveAll(cfg);
            house = new Gson().fromJson(new JsonReader(new StringReader(new String(data, StandardCharsets.UTF_8))),
                    DominionConfiguration.Response.class);
        } catch (Exception e) {
            logger.error("Could not receive the house configuration: {}", e.toString());
            return Result.FAILED;
        } finally {
            try {
                cfg.close();
            } catch (RuntimeException ignored) {
                // nothing to do
            }
        }

        if (house == null || house.rooms == null || house.rooms.length == 0) {
            logger.error("The DEVIsmart app sent no thermostats");
            return Result.FAILED;
        }

        List<Map<String, String>> rooms = new ArrayList<>();
        for (DominionConfiguration.Room room : house.rooms) {
            Map<String, String> r = new LinkedHashMap<>();
            r.put("name", room.roomName);
            r.put("devicePeerID", room.peerId);
            r.put("serialNumber", null);
            rooms.add(r);
            logger.info("Found thermostat '{}'", room.roomName);
        }

        // Step 3: connect to every thermostat once to read its serial number (used as the device ID in MQTT)
        readSerialNumbers(rooms, userName);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("userName", userName);
        json.put("privateKey", DanfossBindingConfig.get().privateKey);
        json.put("peerId", myPeerIdHex);
        json.put("homePeerId", house.housePeerId);
        json.put("houseName", house.houseName);
        json.put("rooms", rooms);

        try {
            writePrivate(out, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(json));
        } catch (IOException e) {
            logger.error("Could not save {}: {}", out, e.toString());
            return Result.FAILED;
        }
        logger.info("Pairing complete: {} thermostat(s) saved", rooms.size());
        return Result.OK;
    }

    private static byte[] receiveAll(PeerConnection cfg) throws Exception {
        int dataSize = 0;
        int offset = 0;
        byte[] data = null;
        do {
            DataInputStream chunk = new DataInputStream(cfg.receiveData());
            int chunkSize = chunk.available();

            if (chunkSize >= 8 && data == null) {
                chunk.mark(8);
                if (chunk.readInt() == 0) {
                    dataSize = Integer.reverseBytes(chunk.readInt());
                    data = new byte[dataSize];
                    chunkSize -= 8;
                } else {
                    chunk.reset();
                }
            }
            if (data == null) {
                dataSize = chunkSize;
                data = new byte[dataSize];
            }
            if (offset + chunkSize > data.length) {
                throw new IOException("Received more data than announced");
            }
            chunk.readFully(data, offset, chunkSize);
            offset += chunkSize;
        } while (offset < dataSize);
        return data;
    }

    private static void readSerialNumbers(List<Map<String, String>> rooms, String userName) {
        CountDownLatch latch = new CountDownLatch(rooms.size());
        List<DeviRegHandler> handlers = new ArrayList<>();

        for (Map<String, String> room : rooms) {
            String peerId = room.get("devicePeerID");
            Map<String, Object> configMap = new HashMap<>();
            configMap.put("userName", userName);
            configMap.put("privateKey", DanfossBindingConfig.get().privateKey);
            configMap.put("peerId", peerId);
            DanfossBindingConfig.update(configMap, new MockConfigurationAdmin());

            DeviRegHandler handler = new DeviRegHandler(new MockThing(peerId));
            handler.setCallback(new MockThingCallback((key, value) -> {
                if ("sys_serial_number".equals(key) && value != null && room.get("serialNumber") == null) {
                    synchronized (room) {
                        if (room.get("serialNumber") == null) {
                            room.put("serialNumber", value);
                            latch.countDown();
                        }
                    }
                }
            }));
            handler.initialize();
            handlers.add(handler);
        }

        try {
            if (!latch.await(SERIAL_NUMBER_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                logger.warn("Some thermostats did not answer in time; they get an ID based on their cloud ID instead");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (DeviRegHandler h : handlers) {
            try {
                h.dispose();
            } catch (RuntimeException ignored) {
                // nothing to do
            }
        }
    }

    /** Writes the file readable by the owner only, replacing any old file in one step. */
    static void writePrivate(Path out, String content) throws IOException {
        Path dir = out.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, ".devi_config", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            tmp = Files.createTempFile(dir, ".devi_config", ".tmp"); // non-POSIX file system
        }
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
