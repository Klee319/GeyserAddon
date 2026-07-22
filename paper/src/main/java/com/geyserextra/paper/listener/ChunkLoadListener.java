package com.geyserextra.paper.listener;

import com.geyserextra.paper.GeyserExtraPaper;
import com.geyserextra.paper.scanner.SkullScanner;

import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listener for chunk load events that trigger skull scanning.
 *
 * This listener monitors chunk loads to discover custom skull textures
 * placed in the world for Bedrock player compatibility.
 */
public final class ChunkLoadListener implements Listener {

    /**
     * Set of chunks currently being scanned to prevent duplicate scans.
     */
    private final Set<Long> scanningChunks;

    private final SkullScanner scanner;
    private final GeyserExtraPaper plugin;

    /**
     * Creates a new ChunkLoadListener.
     *
     * @param scanner The skull scanner
     * @param plugin  The plugin instance
     * @throws NullPointerException if scanner or plugin is null
     */
    public ChunkLoadListener(SkullScanner scanner, GeyserExtraPaper plugin) {
        this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.scanningChunks = ConcurrentHashMap.newKeySet();
    }

    /**
     * Handles chunk load events to scan for skulls.
     *
     * When a chunk is loaded, it is scheduled for asynchronous scanning
     * to discover any custom skull textures placed in the world.
     *
     * @param event The chunk load event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();

        // Skip if this is a newly generated chunk (no skulls yet)
        if (event.isNewChunk()) {
            return;
        }

        // Create unique key for this chunk
        long chunkKey = createChunkKey(chunk);

        // Skip if already scanning this chunk
        if (!scanningChunks.add(chunkKey)) {
            return;
        }

        // Schedule async task to scan chunk
        // Use slight delay to allow chunk to fully load
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {
                scanChunkAsync(chunk, chunkKey);
            } finally {
                // Always remove from scanning set
                scanningChunks.remove(chunkKey);
            }
        }, 5L);  // 0.25 second delay
    }

    /**
     * Scans a chunk for skulls asynchronously.
     *
     * The actual block access is done on the main thread for thread safety,
     * but the scheduling and result handling is async.
     *
     * @param chunk    The chunk to scan
     * @param chunkKey The unique key for this chunk
     */
    private void scanChunkAsync(Chunk chunk, long chunkKey) {
        // Verify chunk is still loaded
        if (!chunk.isLoaded()) {
            return;
        }

        // Run the actual scan on the main thread for thread safety
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!chunk.isLoaded()) {
                return;
            }

            int discovered = scanner.scanChunk(chunk);

            if (discovered > 0 && plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().fine(() -> String.format(
                    "Chunk [%d, %d] in %s: discovered %d skulls",
                    chunk.getX(),
                    chunk.getZ(),
                    chunk.getWorld().getName(),
                    discovered
                ));
            }
        });
    }

    /**
     * Creates a unique key for a chunk based on coordinates.
     *
     * The key combines X and Z coordinates into a single long value.
     *
     * @param chunk The chunk
     * @return A unique long key for this chunk
     */
    private long createChunkKey(Chunk chunk) {
        // Combine world, X and Z into unique key
        int worldHash = chunk.getWorld().getName().hashCode();
        int x = chunk.getX();
        int z = chunk.getZ();

        // Pack coordinates: use high bits for world hash, low bits for x/z
        return ((long) worldHash << 32) | (((long) x & 0xFFFF) << 16) | ((long) z & 0xFFFF);
    }

    /**
     * Gets the current number of chunks being scanned.
     *
     * @return The number of chunks currently being scanned
     */
    public int getScanningCount() {
        return scanningChunks.size();
    }

    /**
     * Checks if a specific chunk is currently being scanned.
     *
     * @param chunk The chunk to check
     * @return true if the chunk is currently being scanned
     */
    public boolean isScanning(Chunk chunk) {
        return scanningChunks.contains(createChunkKey(chunk));
    }
}
