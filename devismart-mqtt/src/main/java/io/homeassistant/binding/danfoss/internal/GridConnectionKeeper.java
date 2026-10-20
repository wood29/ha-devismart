package io.homeassistant.binding.danfoss.internal;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;

import org.openhab.core.common.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sonic_amiga.opensdg.java.Connection;
import io.github.sonic_amiga.opensdg.java.GridConnection;

public class GridConnectionKeeper {
    private static final Logger logger = LoggerFactory.getLogger(GridConnectionKeeper.class);
    private static GridConnection g_Conn;
    private static int numUsers = 0;
    private static String privateKey = null;

    public synchronized static GridConnection getConnection()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        if (privateKey == null) {
            privateKey = DanfossBindingConfig.get().privateKey;
        }
        if (g_Conn == null) {
            // We are not necessarily called from within a ThingHandler; it could also be
            // a DanfossDiscoveryService. Here we are trying to reuse the same named pool
            // as used by all thing handlers for the sake of resource conservation
            // A little bit hacky-wacky (because this name is OpenHAB's internal constant),
            // but we don't want to create yet another pool for such small needs as pinging
            // the grid connection.
            ScheduledExecutorService scheduler = ThreadPoolManager.getScheduledPool("thingHandler");
            g_Conn = new GridConnection(SDGUtils.ParseKey(privateKey), scheduler);
        }

        if (g_Conn.getState() != Connection.State.CONNECTED) {
            try {
                g_Conn.connect(GridConnection.Danfoss);
            } catch (IOException | InterruptedException | ExecutionException | TimeoutException | RuntimeException e) {
                // Start from a fresh connection object next time instead of reusing one in an unknown state
                logger.warn("Connecting to Danfoss grid failed: {}", e.toString());
                closeConnection();
                throw e;
            }
            logger.info("Successfully connected to Danfoss grid");
        }

        return g_Conn;
    }

    public static synchronized void UpdatePrivateKey(String newKey) {
        if (g_Conn != null && !newKey.equals(privateKey)) {
            // Will reconnect on demand
            closeConnection();
        }
        privateKey = newKey;
    }

    public static synchronized void AddUser() {
        numUsers++;
    }

    public static synchronized void RemoveUser() {
        if (--numUsers > 0) {
            return;
        }

        if (g_Conn == null) {
            return;
        }

        logger.info("Last user is gone, disconnecting from Danfoss grid");
        closeConnection();
    }

    private static void closeConnection() {
        GridConnection c = g_Conn;
        g_Conn = null;
        if (c != null) {
            try {
                c.close();
            } catch (RuntimeException e) {
                logger.debug("Error closing grid connection: {}", e.toString());
            }
        }
        logger.info("Grid connection closed");
    }
}
