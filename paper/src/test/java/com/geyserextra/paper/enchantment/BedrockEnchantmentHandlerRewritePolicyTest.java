package com.geyserextra.paper.enchantment;

import org.bukkit.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins who this handler is allowed to rewrite item packets for.
 *
 * <p>The packet plumbing needs a live ProtocolLib pipeline, which this module
 * has no harness for. The one decision that can permanently mutate player items
 * is therefore kept free of Bukkit lookups so it can be asserted here — the same
 * arrangement {@code BedrockSmithingTableCmdStripperTest} uses.</p>
 */
@DisplayName("BedrockEnchantmentHandler rewrite eligibility")
class BedrockEnchantmentHandlerRewritePolicyTest {

    @Test
    @DisplayName("a survival Bedrock player is the case the injection exists for")
    void survivalBedrockPlayerIsRewritten() {
        assertThat(BedrockEnchantmentHandler.shouldRewriteFor(true, GameMode.SURVIVAL)).isTrue();
        assertThat(BedrockEnchantmentHandler.shouldRewriteFor(true, GameMode.ADVENTURE)).isTrue();
    }

    @Test
    @DisplayName("Java players are never touched")
    void javaPlayersAreUntouched() {
        // Their client renders custom names, custom_model_data and enchantment
        // tooltips natively; injecting anything would only be visible as damage.
        for (GameMode mode : GameMode.values()) {
            assertThat(BedrockEnchantmentHandler.shouldRewriteFor(false, mode))
                .as("java player in %s", mode)
                .isFalse();
        }
    }

    @Test
    @DisplayName("creative is excluded because an injected display name burns into the real item")
    void creativeIsExcluded() {
        // Data-loss guard, and the reason this test exists at all.
        //
        // Bedrock's creative inventory is client-authoritative: a stack the
        // client was handed can be echoed back through SET_CREATIVE_SLOT and
        // stored as the real ItemStack. The [GE] lore lines carry a marker and
        // are scrubbed on the way back in, but the display-name fallback that
        // hides raw gmdl_* identifiers carries no marker and has no repair path.
        // An item a creative Bedrock player merely moved therefore kept a
        // custom_name it never had — which is part of the item's component set,
        // so it silently stopped stacking with untouched copies of itself.
        assertThat(BedrockEnchantmentHandler.shouldRewriteFor(true, GameMode.CREATIVE)).isFalse();
    }

    @Test
    @DisplayName("spectators follow the general rule rather than earning an exception")
    void spectatorFollowsTheGeneralRule() {
        // Only creative carries the write-back hazard, so only creative is cut.
        assertThat(BedrockEnchantmentHandler.shouldRewriteFor(true, GameMode.SPECTATOR)).isTrue();
    }
}
