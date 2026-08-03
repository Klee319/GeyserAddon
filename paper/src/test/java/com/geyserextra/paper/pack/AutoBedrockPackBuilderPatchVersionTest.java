package com.geyserextra.paper.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AutoBedrockPackBuilder monotonic pack patch version")
class AutoBedrockPackBuilderPatchVersionTest {

    @Test
    @DisplayName("unchanged content hash keeps last patch (byte-stable rebuilds)")
    void unchangedContentKeepsLastPatch() {
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(22454, 26001, 22454))
            .isEqualTo(26001);
    }

    @Test
    @DisplayName("content hash regression still advances past last patch")
    void hashRegressionAdvancesPastLast() {
        // Live incident: hash 22454 after client already saw 26001.
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(22454, 26001, 999))
            .isEqualTo(26002);
    }

    @Test
    @DisplayName("higher content hash wins when above last+1")
    void higherHashWins() {
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(30000, 100, 50))
            .isEqualTo(30000);
    }

    @Test
    @DisplayName("first emission with no history uses content hash")
    void firstEmissionUsesHash() {
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(12345, 0, -1))
            .isEqualTo(12345);
    }

    @Test
    @DisplayName("sequence keeps rising past the 32767 patch ceiling")
    void sequenceRisesPastPatchCeiling() {
        // The live sidecar sat at 32219. Clamping here is what would have
        // frozen every Bedrock client on its cached pack.
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(500, 32767, 999))
            .isEqualTo(32768);
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(500, 40000, 999))
            .isEqualTo(40001);
    }

    @Test
    @DisplayName("overflow carries into version[1] so the array stays increasing")
    void overflowCarriesIntoMinor() {
        assertThat(AutoBedrockPackBuilder.sequenceMinor(32767)).isEqualTo(0);
        assertThat(AutoBedrockPackBuilder.sequencePatch(32767)).isEqualTo(32767);
        assertThat(AutoBedrockPackBuilder.sequenceMinor(32768)).isEqualTo(1);
        assertThat(AutoBedrockPackBuilder.sequencePatch(32768)).isEqualTo(0);
    }

    @Test
    @DisplayName("manifest renders the carried version array")
    void manifestRendersCarriedVersion() {
        String compact = AutoBedrockPackBuilder.buildManifestJson(32768, false)
            .replaceAll("\\s+", "");
        assertThat(compact).contains("\"version\":[1,1,0]");
        assertThat(AutoBedrockPackBuilder.buildManifestJson(32219, false)
            .replaceAll("\\s+", "")).contains("\"version\":[1,0,32219]");
    }
}
