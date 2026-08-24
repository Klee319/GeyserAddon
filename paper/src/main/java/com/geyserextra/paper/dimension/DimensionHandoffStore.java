package com.geyserextra.paper.dimension;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * File I/O for the nether-sky repair, in the shared extension folder.
 *
 * <p>Two kinds of tiny per-player JSON files live in
 * {@code dimension-handoff/}:</p>
 *
 * <ul>
 *   <li>{@code <uuid>.json} — a {@link DimensionHandoffFlag}, written on quit,
 *       consumed (read + deleted) on the next join, possibly by a different
 *       backend. Overwritten on every quit, so the directory holds at most one
 *       per player.</li>
 *   <li>{@code <uuid>.return.json} — a {@link PendingReturn}, written just
 *       before the repair teleports the player away and deleted when they are
 *       teleported back. If the player disconnects in the window between the
 *       two teleports, this is the only record of where they belong; the next
 *       join on the owning backend consumes it.</li>
 * </ul>
 *
 * <p>Bukkit-free on purpose so it can be unit tested against a temp directory
 * — this module has no server harness.</p>
 */
public final class DimensionHandoffStore {

    /** A pending return older than this is abandoned: the player's world may have changed legitimately. */
    public static final long PENDING_RETURN_MAX_AGE_MS = 60L * 60L * 1000L;

    /** Where the repair must put the player back, if the return teleport never ran. */
    public static final class PendingReturn {
        private String worldUid;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
        private boolean invulnerable;
        private long savedAtMs;

        /** Gson needs this. */
        public PendingReturn() {
        }

        public PendingReturn(String worldUid, double x, double y, double z,
                             float yaw, float pitch, boolean invulnerable, long savedAtMs) {
            this.worldUid = worldUid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.invulnerable = invulnerable;
            this.savedAtMs = savedAtMs;
        }

        public String worldUid() {
            return worldUid;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public float yaw() {
            return yaw;
        }

        public float pitch() {
            return pitch;
        }

        /** The player's invulnerability before the repair touched it. */
        public boolean invulnerable() {
            return invulnerable;
        }

        public long savedAtMs() {
            return savedAtMs;
        }
    }

    private final Path directory;
    private final Logger logger;
    private final Gson gson = new Gson();

    public DimensionHandoffStore(Path directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    public void writeFlag(UUID player, DimensionHandoffFlag flag) {
        write(flagFile(player), gson.toJson(flag));
    }

    /**
     * Reads and deletes the quit flag, so one risky quit triggers at most one
     * repair no matter how many times the player rejoins afterwards.
     */
    public DimensionHandoffFlag consumeFlag(UUID player) {
        Path file = flagFile(player);
        DimensionHandoffFlag flag = read(file, DimensionHandoffFlag.class);
        delete(file);
        return flag;
    }

    public void writePendingReturn(UUID player, PendingReturn pendingReturn) {
        write(returnFile(player), gson.toJson(pendingReturn));
    }

    /**
     * Reads the pending return without deleting it: the caller only deletes
     * once the player is actually back, and a join on the <em>wrong</em>
     * backend (whose worlds don't include the recorded one) must leave the
     * file for the owning backend. A record past
     * {@link #PENDING_RETURN_MAX_AGE_MS} is deleted and not returned.
     */
    public PendingReturn readPendingReturn(UUID player, long nowMs) {
        Path file = returnFile(player);
        PendingReturn pendingReturn = read(file, PendingReturn.class);
        if (pendingReturn == null) {
            return null;
        }
        if (nowMs - pendingReturn.savedAtMs() > PENDING_RETURN_MAX_AGE_MS
            || nowMs < pendingReturn.savedAtMs()) {
            delete(file);
            return null;
        }
        return pendingReturn;
    }

    public void deletePendingReturn(UUID player) {
        delete(returnFile(player));
    }

    private Path flagFile(UUID player) {
        return directory.resolve(player + ".json");
    }

    private Path returnFile(UUID player) {
        return directory.resolve(player + ".return.json");
    }

    private void write(Path file, String json) {
        try {
            Files.createDirectories(directory);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Non-fatal by design: losing a flag means one missed automatic
            // repair, and /fixsky remains as the manual remedy.
            logger.log(Level.WARNING, "Failed to write " + file.getFileName(), e);
        }
    }

    private <T> T read(Path file, Class<T> type) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
        } catch (IOException | JsonParseException e) {
            logger.log(Level.WARNING, "Failed to read " + file.getFileName(), e);
            return null;
        }
    }

    private void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to delete " + file.getFileName(), e);
        }
    }
}
