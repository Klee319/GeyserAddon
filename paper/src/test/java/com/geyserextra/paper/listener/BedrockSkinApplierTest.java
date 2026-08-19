package com.geyserextra.paper.listener;

import com.destroystokyo.paper.profile.ProfileProperty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how the applier locates the property it has to judge.
 *
 * <p>The listener no longer stands down on mere presence — that is what made it
 * a silent no-op on a network where {@code floodgate-spigot} puts <em>a</em>
 * property on the profile while every Bedrock player still renders as Steve. It
 * now returns the property so the caller can compare it against the
 * authoritative skin. Getting this lookup wrong in one direction hands the
 * comparison a placeholder to match against; wrong in the other it reports
 * "nothing delivered" for a profile that has a perfectly good skin.</p>
 */
@DisplayName("BedrockSkinApplier texture detection")
class BedrockSkinApplierTest {

    @Test
    @DisplayName("a signed textures property is returned for comparison")
    void signedTexturesAreFound() {
        ProfileProperty signed = new ProfileProperty("textures", "eyJ0aW1lc3RhbXAiOjF9", "sig");

        assertThat(BedrockSkinApplier.texturesOf(Set.of(signed))).isSameAs(signed);
    }

    @Test
    @DisplayName("an unsigned textures property is returned too, not skipped")
    void unsignedTexturesAreFound() {
        // Signedness is the caller's decision, not this lookup's: an unsigned
        // property is exactly one of the two states worth replacing, so it has
        // to come back rather than read as absent.
        ProfileProperty unsigned = new ProfileProperty("textures", "eyJ0aW1lc3RhbXAiOjF9");

        assertThat(BedrockSkinApplier.texturesOf(Set.of(unsigned))).isSameAs(unsigned);
    }

    @Test
    @DisplayName("no properties at all means nothing was delivered")
    void emptyPropertiesMeanMissing() {
        assertThat(BedrockSkinApplier.texturesOf(Set.of())).isNull();
    }

    @Test
    @DisplayName("other properties do not stand in for a skin")
    void unrelatedPropertiesDoNotCount() {
        assertThat(BedrockSkinApplier.texturesOf(Set.of(
            new ProfileProperty("uniqueId", "abc"),
            new ProfileProperty("floodgate", "true")))).isNull();
    }

    @Test
    @DisplayName("an empty textures value is treated as absent")
    void blankTextureValueIsMissing() {
        // A placeholder must be replaced outright rather than decoded and
        // compared, which would only ever yield a null texture id.
        assertThat(BedrockSkinApplier.texturesOf(
            List.of(new ProfileProperty("textures", "")))).isNull();
    }

    @Test
    @DisplayName("the property name is matched exactly")
    void propertyNameIsMatchedExactly() {
        // Mojang's property is lowercase "textures"; anything else is a
        // different property and must not satisfy the check.
        assertThat(BedrockSkinApplier.texturesOf(
            List.of(new ProfileProperty("Textures", "value", "sig")))).isNull();
    }

    @Test
    @DisplayName("a skin found alongside other properties is still returned")
    void skinAmongOthersIsFound() {
        ProfileProperty skin = new ProfileProperty("textures", "value", "sig");

        assertThat(BedrockSkinApplier.texturesOf(List.of(
            new ProfileProperty("uniqueId", "abc"), skin))).isSameAs(skin);
    }

    @Test
    @DisplayName("a null property set means missing, never a crash on join")
    void nullPropertiesAreMissing() {
        assertThat(BedrockSkinApplier.texturesOf(null)).isNull();
    }
}
