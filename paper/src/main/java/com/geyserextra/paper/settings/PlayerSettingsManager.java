package com.geyserextra.paper.settings;

import com.geyserextra.core.util.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages per-player settings with in-memory caching and disk persistence.
 *
 * Why: Player settings must survive server restarts and player reconnects, so they are
 * stored as individual JSON files on disk. An in-memory cache (ConcurrentHashMap) avoids
 * repeated disk I/O during gameplay, which would cause lag on the main server thread.
 *
 * Thread safety: All cache operations use ConcurrentHashMap. Disk writes are performed
 * asynchronously on a separate thread to avoid blocking the main server tick loop.
 * Disk reads happen synchronously during player join (acceptable because join events
 * are infrequent and the files are tiny).
 */
public final class PlayerSettingsManager {

    private static final String FILE_EXTENSION = ".json";

    private final Path dataFolder;
    private final Logger logger;

    // Why: ConcurrentHashMap provides thread-safe access without explicit synchronization.
    // The main thread reads settings during tick processing while async threads write
    // settings to disk after updates.
    private final ConcurrentMap<UUID, PlayerSettings> cache;

    // Why: A single-thread executor serializes all disk writes, preventing concurrent
    // file corruption when a player toggles settings rapidly or disconnects during a write.
    // Using newSingleThreadExecutor instead of spawning daemon threads per-save.
    private final ExecutorService saveExecutor;

    /**
     * Creates a new PlayerSettingsManager.
     *
     * Why: The dataFolder is created eagerly at construction time rather than lazily
     * on first write, so that permission or path errors surface immediately during
     * plugin startup rather than silently failing when a player joins.
     *
     * @param dataFolder the directory where per-player JSON files are stored
     * @param logger     the logger for warnings and errors
     */
    public PlayerSettingsManager(Path dataFolder, Logger logger) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder must not be null");
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.cache = new ConcurrentHashMap<>();
        this.saveExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PlayerSettings-Save");
            t.setDaemon(true);
            return t;
        });

        ensureDataFolderExists();
    }

    /**
     * Shuts down the save executor, allowing pending writes to complete.
     *
     * Why: Called during plugin disable to ensure all queued settings writes
     * finish before the server shuts down.
     */
    public void shutdown() {
        saveExecutor.shutdown();
    }

    /**
     * Returns the settings for the given player.
     *
     * Why: This method is the primary access point called frequently during gameplay
     * (e.g., every tick for display updates). It returns the cached value to avoid
     * disk I/O. If the player is not cached (rare edge case — e.g., called before
     * the join event fires), it falls back to loading from disk or returning defaults.
     *
     * @param playerId the UUID of the player
     * @return the player's settings, never null
     */
    public PlayerSettings getSettings(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId must not be null");

        // Why: computeIfAbsent is atomic in ConcurrentHashMap, preventing duplicate
        // disk reads if multiple threads request the same player simultaneously.
        return cache.computeIfAbsent(playerId, this::loadFromDisk);
    }

    /**
     * Updates the settings for the given player in cache and persists to disk asynchronously.
     *
     * Why: The cache is updated synchronously so that subsequent getSettings() calls
     * on the main thread immediately see the new values. The disk write is async
     * because file I/O can block for milliseconds, which is unacceptable on the
     * main server thread (20 TPS budget = 50ms per tick).
     *
     * @param playerId the UUID of the player
     * @param settings the new settings to apply
     */
    public void updateSettings(UUID playerId, PlayerSettings settings) {
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(settings, "settings must not be null");

        cache.put(playerId, settings);

        // Why: Queued on a single-thread executor to serialize writes and prevent
        // concurrent file corruption from rapid settings toggles or quit-during-save.
        saveToDiskAsync(playerId, settings);
    }

    /**
     * Loads a player's settings from disk into the cache.
     *
     * Why: Called during player join events. Loading is synchronous because join events
     * are infrequent and the settings file is small (typically under 1KB). Having the
     * settings available immediately after join prevents null-check complexity in
     * downstream display code.
     *
     * @param playerId the UUID of the player
     */
    public void loadPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId must not be null");

        PlayerSettings settings = loadFromDisk(playerId);
        cache.put(playerId, settings);
    }

    /**
     * Saves the player's current settings to disk and removes them from cache.
     *
     * Why: Called during player quit events. The save is synchronous here because
     * the quit event is the last opportunity to persist data before the player
     * reference is cleaned up. Removing from cache prevents memory leaks from
     * accumulating entries for players who have left.
     *
     * @param playerId the UUID of the player
     */
    public void unloadPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId must not be null");

        PlayerSettings settings = cache.remove(playerId);
        if (settings != null) {
            saveToDisk(playerId, settings);
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    /**
     * Resolves the JSON file path for a given player UUID.
     *
     * Why: Using the UUID as the filename guarantees uniqueness and avoids issues
     * with special characters in player names. The .json extension aids manual
     * inspection and editing by server administrators.
     */
    private Path resolvePlayerFile(UUID playerId) {
        return dataFolder.resolve(playerId.toString() + FILE_EXTENSION);
    }

    /**
     * Loads settings from disk for the given player.
     *
     * Why: Returns a default PlayerSettings on any failure (file not found, parse error,
     * I/O error) to ensure the system never crashes due to corrupt or missing player data.
     * Warnings are logged so administrators can investigate persistent issues.
     */
    private PlayerSettings loadFromDisk(UUID playerId) {
        Path file = resolvePlayerFile(playerId);

        if (!Files.exists(file)) {
            // Why: A missing file is the normal case for first-time players.
            // No warning is needed — just return defaults.
            return new PlayerSettings();
        }

        try {
            String json = Files.readString(file);
            PlayerSettings loaded = JsonUtil.fromJson(json, PlayerSettings.class);

            if (loaded == null) {
                // Why: GSON returns null for the JSON literal "null" or empty input.
                // Treating this as a corrupt file and falling back to defaults
                // is safer than propagating null through the system.
                logger.warning("Player settings file contained null for " + playerId + ", using defaults");
                return new PlayerSettings();
            }

            // Why: GSON can inject null into enum fields via reflection when the JSON
            // contains explicit null values or unknown enum constants. Null enum fields
            // would cause NullPointerException in DisplayManager's switch/comparison
            // logic every tick, breaking all Bedrock players' displays.
            if (loaded.getBiomeDisplay() == null
                    || loaded.getLightLevelDisplay() == null
                    || loaded.getEntityDisplay() == null) {
                logger.warning("Corrupt enum fields in settings for " + playerId + ", using defaults");
                return new PlayerSettings();
            }

            return loaded;
        } catch (IOException e) {
            logger.log(Level.WARNING,
                    "Failed to read player settings for " + playerId + ", using defaults", e);
            return new PlayerSettings();
        } catch (Exception e) {
            // Why: Catching broad Exception here guards against malformed JSON or
            // unexpected GSON deserialization errors. A corrupt settings file should
            // never take down the server.
            logger.log(Level.WARNING,
                    "Failed to parse player settings for " + playerId + ", using defaults", e);
            return new PlayerSettings();
        }
    }

    /**
     * Saves settings to disk synchronously.
     *
     * Why: Used during player quit where we must guarantee the write completes
     * before the reference is discarded.
     */
    private void saveToDisk(UUID playerId, PlayerSettings settings) {
        Path file = resolvePlayerFile(playerId);

        try {
            ensureDataFolderExists();
            String json = JsonUtil.toPrettyJson(settings);
            Files.writeString(file, json);
        } catch (IOException e) {
            // Why: Logging rather than throwing ensures a failed save does not
            // disrupt the player quit flow or cause cascading errors.
            logger.log(Level.WARNING,
                    "Failed to save player settings for " + playerId, e);
        }
    }

    /**
     * Queues a settings save on the single-thread executor.
     *
     * Why: File I/O during gameplay (settings toggle) must not block the main thread.
     * The single-thread executor serializes writes for the same player, preventing
     * concurrent file corruption from rapid settings toggles. Previous approach of
     * spawning a daemon thread per save risked race conditions.
     */
    private void saveToDiskAsync(UUID playerId, PlayerSettings settings) {
        saveExecutor.execute(() -> saveToDisk(playerId, settings));
    }

    /**
     * Ensures the data folder directory exists, creating it if necessary.
     *
     * Why: The directory may not exist on first plugin launch or after manual deletion.
     * Creating it proactively avoids FileNotFoundException on the first save attempt.
     */
    private void ensureDataFolderExists() {
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            logger.log(Level.SEVERE,
                    "Failed to create player settings directory: " + dataFolder, e);
        }
    }
}
