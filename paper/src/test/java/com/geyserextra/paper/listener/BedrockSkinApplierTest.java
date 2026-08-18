package com.geyserextra.paper.listener;

import com.destroystokyo.paper.profile.ProfileProperty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the single decision that keeps this listener out of Floodgate's way.
 *
 * <p>The applier writes a {@code textures} property only when the profile has
 * none. Get that predicate wrong in one direction and it overwrites a skin some
 * other plugin legitimately set (nick plugins, cosmetics); wrong in the other
 * and a placeholder property suppresses the repair forever, leaving the Bedrock
 * player as Steve with the log insisting everything is fine.</p>
 */
@DisplayName("BedrockSkinApplier texture detection")
class BedrockSkinApplierTest {

    @Test
    @DisplayName("a signed textures property counts as present")
    void signedTexturesArePresent() {
        assertThat(BedrockSkinApplier.hasTextures(
            Set.of(new ProfileProperty("textures", "eyJ0aW1lc3RhbXAiOjF9", "sig")))).isTrue();
    }

    @Test
    @DisplayName("an unsigned textures property still counts as present")
    void unsignedTexturesArePresent() {
        // Whether the property is signed is Mojang's business, not this
        // listener's. Overwriting an unsigned skin an operator's plugin set
        // would be a regression, not a repair.
        assertThat(BedrockSkinApplier.hasTextures(
            Set.of(new ProfileProperty("textures", "eyJ0aW1lc3RhbXAiOjF9")))).isTrue();
    }

    @Test
    @DisplayName("no properties at all means the skin is missing")
    void emptyPropertiesMeanMissing() {
        // This is the exact state every Bedrock player's profile arrives in on
        // this network, and the reason the whole fix exists.
        assertThat(BedrockSkinApplier.hasTextures(Set.of())).isFalse();
    }

    @Test
    @DisplayName("other properties do not stand in for a skin")
    void unrelatedPropertiesDoNotCount() {
        assertThat(BedrockSkinApplier.hasTextures(Set.of(
            new ProfileProperty("uniqueId", "abc"),
            new ProfileProperty("floodgate", "true")))).isFalse();
    }

    @Test
    @DisplayName("an empty textures value is treated as absent")
    void blankTextureValueIsMissing() {
        assertThat(BedrockSkinApplier.hasTextures(
            List.of(new ProfileProperty("textures", "")))).isFalse();
    }

    @Test
    @DisplayName("the property name is matched exactly")
    void propertyNameIsMatchedExactly() {
        // Mojang's property is lowercase "textures"; anything else is a
        // different property and must not satisfy the check.
        assertThat(BedrockSkinApplier.hasTextures(
            List.of(new ProfileProperty("Textures", "value", "sig")))).isFalse();
    }

    @Test
    @DisplayName("a skin found alongside other properties still counts")
    void skinAmongOthersIsFound() {
        assertThat(BedrockSkinApplier.hasTextures(List.of(
            new ProfileProperty("uniqueId", "abc"),
            new ProfileProperty("textures", "value", "sig")))).isTrue();
    }

    @Test
    @DisplayName("a null property set means missing, never a crash on join")
    void nullPropertiesAreMissing() {
        assertThat(BedrockSkinApplier.hasTextures((java.util.Collection<ProfileProperty>) null))
            .isFalse();
    }
}
