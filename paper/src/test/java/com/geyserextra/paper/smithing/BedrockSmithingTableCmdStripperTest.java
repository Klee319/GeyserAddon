package com.geyserextra.paper.smithing;

import org.bukkit.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the eligibility policy of the smithing-table CMD strip. The packet
 * handling itself needs a live ProtocolLib pipeline, which this module has no
 * harness for, so the one decision that can silently destroy player data is
 * kept free of Bukkit lookups specifically so it can be asserted here.
 */
@DisplayName("BedrockSmithingTableCmdStripper eligibility")
class BedrockSmithingTableCmdStripperTest {

    @Test
    @DisplayName("a survival Bedrock player is the case the fix exists for")
    void survivalBedrockPlayerIsStripped() {
        assertThat(BedrockSmithingTableCmdStripper.shouldStripFor(true, GameMode.SURVIVAL)).isTrue();
        assertThat(BedrockSmithingTableCmdStripper.shouldStripFor(true, GameMode.ADVENTURE)).isTrue();
    }

    @Test
    @DisplayName("Java players are never touched")
    void javaPlayersAreUntouched() {
        // Their client understands custom_model_data natively; stripping would
        // remove the artwork for no benefit at all.
        for (GameMode mode : GameMode.values()) {
            assertThat(BedrockSmithingTableCmdStripper.shouldStripFor(false, mode))
                .as("java player in %s", mode)
                .isFalse();
        }
    }

    @Test
    @DisplayName("creative is excluded because the client can write the stripped stack back")
    void creativeIsExcluded() {
        // Data-loss guard. Bedrock's creative inventory is client-authoritative:
        // the client may echo a stack it was given back to the server through
        // SET_CREATIVE_SLOT, which would erase CUSTOM_MODEL_DATA from the real
        // item rather than only from the outbound packet. Same reasoning, and
        // the same exclusion, as BedrockDurabilityBarScaler.
        assertThat(BedrockSmithingTableCmdStripper.shouldStripFor(true, GameMode.CREATIVE)).isFalse();
    }

    @Test
    @DisplayName("spectators are eligible but cannot reach a smithing table anyway")
    void spectatorFollowsTheGeneralRule() {
        // Documented rather than special-cased: only creative carries the
        // write-back hazard, so nothing else earns an exception.
        assertThat(BedrockSmithingTableCmdStripper.shouldStripFor(true, GameMode.SPECTATOR)).isTrue();
    }
}
