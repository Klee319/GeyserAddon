package com.geyserextra.paper.scanner;

import com.geyserextra.paper.GeyserExtraPaper;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;

import java.util.Objects;

/**
 * Scanner for detecting custom skulls from all loaded worlds at startup.
 *
 * Why: Instead of waiting for chunks to load or players to interact,
 * this scanner proactively scans all loaded chunks in all worlds
 * to discover custom skull textures immediately after plugins load.
 */
public final class WorldSkullScanner {

    private final SkullScanner skullScanner;
    private final GeyserExtraPaper plugin;

    /**
     * Creates a new WorldSkullScanner.
     *
     * @param skullScanner The skull scanner to use for skull detection
     * @param plugin       The plugin instance
     */
    public WorldSkullScanner(SkullScanner skullScanner, GeyserExtraPaper plugin) {
        this.skullScanner = Objects.requireNonNull(skullScanner, "skullScanner must not be null");
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    /**
     * Scans all loaded chunks in all worlds for custom skulls.
     * Should be called after all plugins have loaded.
     *
     * @return The number of custom skulls discovered
     */
    public int scanAllWorlds() {
        int totalDiscovered = 0;
        int totalChunks = 0;
        int totalSkulls = 0;

        for (World world : Bukkit.getWorlds()) {
            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().fine("[WorldSkullScanner] Scanning world: " + world.getName());
            }

            for (Chunk chunk : world.getLoadedChunks()) {
                totalChunks++;
                int discovered = scanChunkForSkulls(chunk);
                totalDiscovered += discovered;
                totalSkulls += countSkullsInChunk(chunk);
            }
        }

        plugin.getLogger().fine("[WorldSkullScanner] Scanned " + totalChunks + " chunks, found "
            + totalSkulls + " skull blocks, discovered " + totalDiscovered + " unique textures");

        return totalDiscovered;
    }

    /**
     * Scans a chunk for skull blocks and registers their textures.
     *
     * @param chunk The chunk to scan
     * @return The number of new skulls discovered
     */
    private int scanChunkForSkulls(Chunk chunk) {
        int discovered = 0;

        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Skull skull)) {
                continue;
            }

            // Only process player heads (they have custom textures)
            Material type = state.getType();
            if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) {
                continue;
            }

            // Use the existing skull scanner to process this skull
            if (skullScanner.scanBlock(state.getBlock()).isPresent()) {
                discovered++;
            }
        }

        return discovered;
    }

    /**
     * Counts the number of skull blocks in a chunk.
     *
     * @param chunk The chunk to count
     * @return The number of skull blocks
     */
    private int countSkullsInChunk(Chunk chunk) {
        int count = 0;
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof Skull) {
                Material type = state.getType();
                if (type == Material.PLAYER_HEAD || type == Material.PLAYER_WALL_HEAD) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Schedules a world scan after a delay to ensure all plugins have loaded.
     *
     * @param delayTicks The delay in ticks before scanning
     */
    public void scheduleDelayedScan(long delayTicks) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.getLogger().fine("Scanning all worlds for custom skulls...");
            int discovered = scanAllWorlds();
            plugin.getLogger().fine("World skull scan complete. Discovered " + discovered + " unique skull textures.");

            // Save through the shared serializer without rebuilding the live ZIP.
            plugin.saveRegistryMetadataAsync();
        }, delayTicks);
    }
}
