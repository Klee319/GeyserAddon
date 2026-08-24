package com.geyserextra.paper.dimension;

/**
 * Decides whether a Bedrock player's join needs the dimension round-trip that
 * un-sticks a nether sky.
 *
 * <p>Deliberately free of every Bukkit type so it can be unit tested: this
 * module has no server harness, and even constructing an {@code ItemStack}
 * needs a live server on Paper 1.21.11. The listener feeds it plain values it
 * read from Bukkit; this class only holds the rules.</p>
 *
 * <p>The rules encode the two ways the client gets stuck (GeyserMC #3005
 * family): the client begins the portal fog transition, and then the player is
 * moved to another backend — or relogs — before the transition resolves. The
 * new backend reloads chunks without a dimension-change packet, so the client
 * keeps the nether sky in the overworld until something forces a dimension
 * rebuild.</p>
 */
public final class DimensionHandoffDecision {

    /**
     * How old a quit flag may be and still describe <em>this</em> join. A
     * Velocity transfer lands in well under a second; 60&nbsp;s generously
     * covers a slow relog while making yesterday's flag inert.
     */
    public static final long FRESH_WINDOW_MS = 60_000L;

    private DimensionHandoffDecision() {
    }

    /**
     * @param nowMs current wall-clock time
     * @param flag what the previous backend recorded at quit, or {@code null}
     *     if there is no flag (first join, flag consumed, or a Java player)
     * @param standingInPortal whether the player is inside a nether portal
     *     block right now, on this backend
     * @param currentEnvironment {@code World.Environment.name()} of the world
     *     the player joined into
     * @return whether to run the dimension round-trip
     */
    public static boolean shouldJuggle(long nowMs, DimensionHandoffFlag flag,
                                       boolean standingInPortal, String currentEnvironment) {
        // Standing inside a portal on join is the risk itself, regardless of
        // where the player came from: the client starts the fog transition
        // immediately and any hiccup leaves it stuck. This also covers the
        // relog repro, where no cross-backend flag exists.
        if (standingInPortal) {
            return true;
        }
        if (flag == null) {
            return false;
        }
        long age = nowMs - flag.quitAtMs();
        // A negative age means a corrupt or hand-edited file, not time travel
        // — the backends share one host clock. Treat it as stale.
        if (age < 0 || age > FRESH_WINDOW_MS) {
            return false;
        }
        // Quit while inside a portal: the reliable live repro (stand in the
        // resource server's portal, /server to main → nether sky on main).
        if (flag.inPortal()) {
            return true;
        }
        // Quit in one environment, joined in another: the client had a real
        // dimension switch in flight across the transfer.
        return flag.environment() != null && !flag.environment().equals(currentEnvironment);
    }
}
