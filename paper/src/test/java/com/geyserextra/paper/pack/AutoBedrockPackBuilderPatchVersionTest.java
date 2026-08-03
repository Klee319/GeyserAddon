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
}
