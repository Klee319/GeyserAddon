package com.geyserextra.paper.bedrock;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Keeps {@code <extension>/bedrock-recipes/<backend>.json} in step with what the backend
 * plugins have written, so the Geyser extension on the proxy can inject corrected recipes.
 *
 * <h2>Why a poll instead of a one-shot on startup</h2>
 * Two things move this file after our own {@code onEnable}:
 * <ul>
 *   <li><b>Plugin load order.</b> TrinityForge writes its table for real only after ArsPaper has
 *       enabled (before that, {@code custom:} ingredients backed by Ars items cannot be
 *       resolved and are dropped). A single pass at our enable would ship a table missing
 *       exactly the compressed-material recipes this whole feature exists for.</li>
 *   <li><b>{@code /trinityforge reload} and {@code /arspaper reload}.</b> Both rewrite their
 *       table. A collector that only ran at startup would leave the proxy serving a stale copy
 *       with no way to notice — the symptom is "I reloaded and it did not take", which is
 *       indistinguishable from the feature not working at all.</li>
 * </ul>
 *
 * <p>The poll is a directory listing plus a stat per source file, once a minute, off the main
 * thread. It re-collects only when a fingerprint changes, so the steady state writes nothing.
 */
public final class BedrockRecipeTableService {

    /** Delay before the first pass. Long enough for the other plugins to finish enabling. */
    private static final long INITIAL_DELAY_TICKS = 20L * 10;

    /** Poll interval. Recipes change on reload, not per tick, so a minute is plenty. */
    private static final long INTERVAL_TICKS = 20L * 60;

    private final JavaPlugin plugin;
    private final Path pluginsFolder;
    private final Path extensionDataFolder;
    private final String backendId;

    /**
     * Kept in step with the shipped table so the smithing CMD stripper stops fighting the
     * injected recipes. See {@link SmithingBaseExemptions} for why the two cancel out.
     */
    private final SmithingBaseExemptions smithingBaseExemptions;

    private BukkitTask task;
    private List<String> lastFingerprint = List.of();

    public BedrockRecipeTableService(JavaPlugin plugin, Path extensionDataFolder, String backendId,
                                     SmithingBaseExemptions smithingBaseExemptions) {
        this.plugin = plugin;
        this.pluginsFolder = plugin.getDataFolder().getParentFile().toPath();
        this.extensionDataFolder = extensionDataFolder;
        this.backendId = backendId;
        this.smithingBaseExemptions = smithingBaseExemptions;
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin, this::poll, INITIAL_DELAY_TICKS, INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void poll() {
        try {
            List<String> fingerprint = fingerprint();
            if (fingerprint.equals(lastFingerprint)) {
                return;
            }
            lastFingerprint = fingerprint;
            BedrockRecipeTableCollector.Result result =
                BedrockRecipeTableCollector.collect(pluginsFolder, extensionDataFolder, backendId);
            if (result.isEmpty() && result.rejected().isEmpty()) {
                // Nothing shipped a table. Not an error: a backend without TrinityForge or
                // ArsPaper simply has no custom-ingredient recipes to correct.
                plugin.getLogger().fine("[bedrock-recipes] no source tables under "
                    + pluginsFolder.toAbsolutePath());
                return;
            }
            if (smithingBaseExemptions != null) {
                // Only after a successful collect: on a failure path the previous set stays, which
                // matches the file the proxy is still serving.
                smithingBaseExemptions.update(result.smithingBases());
            }
            plugin.getLogger().info("[bedrock-recipes] " + backendId + ": " + result.describe());
            if (!result.rejected().isEmpty()) {
                plugin.getLogger().warning("[bedrock-recipes] some tables were not usable and were"
                    + " left out entirely (a partly-understood table would inject wrong"
                    + " ingredients): " + String.join(", ", result.rejected()));
            }
        } catch (IOException | RuntimeException e) {
            // Never take the backend down over this: it only affects Bedrock crafting hints.
            plugin.getLogger().log(Level.WARNING,
                "[bedrock-recipes] collection failed; the proxy keeps the previous table", e);
        }
    }

    /**
     * Cheap change detector: path, size and modification time of every source file.
     *
     * <p>Content hashing would be steadier but has to read every file each minute for a value
     * that almost never changes. Size+mtime misses only an in-place edit that preserves both,
     * which the writers cannot produce — they write a temp file and move it into place.
     */
    private List<String> fingerprint() throws IOException {
        List<String> parts = new ArrayList<>();
        for (Path file : BedrockRecipeTableCollector.findSourceFiles(pluginsFolder)) {
            parts.add(file + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis());
        }
        return parts;
    }
}
