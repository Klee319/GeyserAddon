package com.geyserextra.paper.dimension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the rules for when a Bedrock join gets the dimension round-trip.
 *
 * <p>These are the only rules standing between "every affected player plays
 * under a red sky" (repair too narrow) and "every backend transfer costs two
 * loading screens" (repair too eager). The Bukkit halves — portal detection,
 * the teleports — cannot be unit tested in this module, which is exactly why
 * the decision lives in a Bukkit-free class.</p>
 */
@DisplayName("DimensionHandoffDecision")
class DimensionHandoffDecisionTest {

    private static final long NOW = 1_000_000L;

    private static DimensionHandoffFlag flag(long quitAt, String env, boolean inPortal) {
        return new DimensionHandoffFlag(quitAt, env, inPortal);
    }

    @Test
    @DisplayName("standing in a portal on join always repairs, even with no flag")
    void portalOnJoinRepairs() {
        // The relog repro: no cross-backend flag exists, the risk is the
        // player's current position alone.
        assertThat(DimensionHandoffDecision.shouldJuggle(NOW, null, true, "NORMAL")).isTrue();
    }

    @Test
    @DisplayName("the live repro: quit inside a portal, fresh transfer, same environment")
    void quitInPortalRepairs() {
        // Resource server portal → /server main. Both sides are NORMAL, so
        // only the inPortal bit distinguishes this join from a harmless one.
        assertThat(DimensionHandoffDecision.shouldJuggle(NOW,
            flag(NOW - 500, "NORMAL", true), false, "NORMAL")).isTrue();
    }

    @Test
    @DisplayName("a cross-environment transfer repairs")
    void environmentMismatchRepairs() {
        assertThat(DimensionHandoffDecision.shouldJuggle(NOW,
            flag(NOW - 500, "NETHER", false), false, "NORMAL")).isTrue();
    }

    @Test
    @DisplayName("an ordinary transfer does nothing")
    void harmlessTransferDoesNothing() {
        // Same environment, not in a portal: the overwhelmingly common case.
        // Repairing here would put two loading screens on every /server.
        assertThat(DimensionHandoffDecision.shouldJuggle(NOW,
            flag(NOW - 500, "NORMAL", false), false, "NORMAL")).isFalse();
    }

    @Test
    @DisplayName("no flag and no portal does nothing")
    void firstJoinDoesNothing() {
        assertThat(DimensionHandoffDecision.shouldJuggle(NOW, null, false, "NORMAL")).isFalse();
    }

    @Test
    @DisplayName("a stale flag is inert even when it says portal")
    void staleFlagIsInert() {
        // Quit in a portal yesterday, rejoined today at spawn: the client
        // re-initialized its dimension at login, there is nothing to repair.
        long stale = NOW - DimensionHandoffDecision.FRESH_WINDOW_MS - 1;
        assertThat(DimensionHandoffDecision.shouldJuggle(NOW,
            flag(stale, "NORMAL", true), false, "NORMAL")).isFalse();
        assertThat(DimensionHandoffDecision.shouldJuggle(NOW,
            flag(stale, "NETHER", false), false, "NORMAL")).isFalse();
    }

    @Test
    @DisplayName("a flag exactly at the freshness boundary still counts")
    void boundaryIsInclusive() {
        assertThat(DimensionHandoffDecision.shouldJuggle(NOW,
            flag(NOW - DimensionHandoffDecision.FRESH_WINDOW_MS, "NORMAL", true),
            false, "NORMAL")).isTrue();
    }

    @Test
    @DisplayName("a flag from the future is treated as corrupt, not fresh")
    void futureFlagIsInert() {
        assertThat(DimensionHandoffDecision.shouldJuggle(NOW,
            flag(NOW + 10_000, "NORMAL", true), false, "NORMAL")).isFalse();
    }

    @Test
    @DisplayName("a flag with no environment cannot claim a mismatch")
    void nullEnvironmentIsNotAMismatch() {
        // A half-written file must degrade to "do nothing", not to a repair
        // triggered by comparing null against every environment.
        assertThat(DimensionHandoffDecision.shouldJuggle(NOW,
            flag(NOW - 500, null, false), false, "NORMAL")).isFalse();
    }
}
