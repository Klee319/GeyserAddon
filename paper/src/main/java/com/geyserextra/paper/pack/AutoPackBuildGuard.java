package com.geyserextra.paper.pack;

/**
 * Prevents replacing a resource pack after Geyser has cached it for serving.
 */
public final class AutoPackBuildGuard {

    private volatile boolean startupWindowOpen;

    /**
     * Runs the synchronous immediate scan. ZIP replacement is enabled only
     * during a full startup before Geyser starts; plugin reloads keep the
     * window closed. The window always closes when a startup scan fails.
     *
     * @param geyserAlreadyEnabled true for a reload against a running Geyser
     * @param action immediate startup scan and save
     */
    public void runStartupScan(boolean geyserAlreadyEnabled, Runnable action) {
        if (geyserAlreadyEnabled) {
            action.run();
            return;
        }
        startupWindowOpen = true;
        try {
            action.run();
        } finally {
            startupWindowOpen = false;
        }
    }

    /**
     * Geyser reads pack metadata during startup. Replacing the visible ZIP
     * afterwards can make its cached hash/size disagree with the file bytes
     * sent to Bedrock, which causes every custom texture to disappear.
     *
     * @return true only while the pre-registration startup window is open
     */
    public boolean mayReplacePack() {
        return startupWindowOpen;
    }
}
