package com.geyserextra.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixes where the Bedrock pack's zip build is allowed to run.
 *
 * <p><b>The production failure this exists for (2026-08-22 and 2026-08-21):</b>
 * Paper's watchdog fired with the server thread inside {@code Deflater.deflate}:</p>
 *
 * <pre>
 * The server has not responded for 10 seconds! Creating thread dump
 *   java.util.zip.Deflater.deflateBytesBytes(Native Method)
 *   AutoBedrockPackBuilder.putEntry(AutoBedrockPackBuilder.java:1881)
 *   GeyserExtraPaper.saveRegistriesToSharedFolder(GeyserExtraPaper.java:1180)
 *   GeyserExtraPaper.runStartupScans(GeyserExtraPaper.java:734)
 * </pre>
 *
 * <p>The startup scans genuinely need the primary thread (Bukkit's recipe and
 * world APIs), so the save they trigger inherited it — but the pack build
 * itself touches no Bukkit API and writes only the <em>pending</em> zip, which
 * the Extension promotes later. It never had to block the tick loop.</p>
 *
 * <p><b>Why this is a unit test of a boolean and not an integration test:</b> the
 * defect is a threading decision. A threading decision that can only be observed
 * by booting Paper and watching a watchdog is one that silently regresses — the
 * next person to add work inside {@code saveRegistriesToSharedFolder} would have
 * no failing test to stop them.</p>
 */
class PackBuildOffloadTest {

    /**
     * The startup path: on the server thread, plugin live. This is the exact
     * combination the watchdog caught, so it must be the one that offloads.
     */
    @Test
    void aBuildOnTheServerThreadIsHandedToAnAsyncTask() {
        assertTrue(GeyserExtraPaper.shouldOffloadPackBuild(true, true),
            "this is the startup path the watchdog reported; it must not run inline");
    }

    /**
     * The periodic save and the one-shot dynamic-pack refresh are already
     * scheduled asynchronously. Offloading again would only add a hop — and
     * would break the caller's assumption that the pack exists once its call
     * returns.
     */
    @Test
    void aBuildAlreadyOffTheServerThreadRunsInline() {
        assertFalse(GeyserExtraPaper.shouldOffloadPackBuild(false, true),
            "async callers are already where we want them");
        assertFalse(GeyserExtraPaper.shouldOffloadPackBuild(false, false),
            "the thread is what decides here, not the enabled state");
    }

    /**
     * {@code onDisable} saves one last time. Bukkit's scheduler rejects new
     * tasks for a plugin that is shutting down, so offloading there would throw
     * and drop the final pack — with no tick loop left to protect. Shutdown
     * therefore keeps the blocking build on purpose.
     */
    @Test
    void aBuildDuringShutdownStaysInlineBecauseTheSchedulerIsClosed() {
        assertFalse(GeyserExtraPaper.shouldOffloadPackBuild(true, false),
            "scheduling during onDisable throws IllegalPluginAccessException;"
                + " the last save has to block instead of being lost");
    }
}
