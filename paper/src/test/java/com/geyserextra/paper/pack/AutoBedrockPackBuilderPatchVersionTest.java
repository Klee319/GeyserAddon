package com.geyserextra.paper.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AutoBedrockPackBuilder monotonic pack patch version")
class AutoBedrockPackBuilderPatchVersionTest {

    @Test
    @DisplayName("unchanged content hash keeps last patch (byte-stable rebuilds)")
    void unchangedContentKeepsLastPatch() {
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(22454, 26001, 22454, true))
            .isEqualTo(26001);
    }

    @Test
    @DisplayName("content hash regression still advances past last patch")
    void hashRegressionAdvancesPastLast() {
        // Live incident: hash 22454 after client already saw 26001.
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(22454, 26001, 999, true))
            .isEqualTo(26002);
    }

    @Test
    @DisplayName("higher content hash wins when above last+1")
    void higherHashWins() {
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(30000, 100, 50, true))
            .isEqualTo(30000);
    }

    @Test
    @DisplayName("first emission with no history uses content hash")
    void firstEmissionUsesHash() {
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(12345, 0, -1, false))
            .isEqualTo(12345);
    }

    @Test
    @DisplayName("unknown last hash bumps the version even though content is identical")
    void unknownHashAlwaysBumps() {
        // This is the promote-then-restart regression: the pending sidecar
        // was moved away by GeyserExtraExtension's promote step, so the
        // caller genuinely does not know what hash the last emitted patch
        // corresponds to. Treating "unknown" as "unchanged" would let a
        // real content change go undetected, so unknown must bump.
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(22454, 26001, 22454, false))
            .isEqualTo(26002);
    }

    @Test
    @DisplayName("content hash of exactly 32767 with unknown last hash still bumps")
    void sentinelCollisionHashStillBumps() {
        // Regression test for the fixed sentinel bug: Math.floorMod(-1,
        // 32768) == 32767, so the old code (which stored "unknown" as
        // lastContentHash=-1 and compared via floorMod) could not tell a
        // real content hash of 32767 apart from "unknown" and would
        // wrongly report "unchanged" here, silently freezing clients on a
        // stale pack. With hasLastContentHash=false, "unknown" must always
        // be treated as changed regardless of what the raw hash value is.
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(32767, 100, 999, false))
            .isEqualTo(32767);
    }

    @Test
    @DisplayName("changed content always advances past last patch")
    void changedContentAdvances() {
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(500, 100, 999, true))
            .isEqualTo(500);
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(50, 100, 999, true))
            .isEqualTo(101);
    }

    @Test
    @DisplayName("sequence keeps rising past the 32767 patch ceiling")
    void sequenceRisesPastPatchCeiling() {
        // The live sidecar sat at 32219. Clamping here is what would have
        // frozen every Bedrock client on its cached pack.
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(500, 32767, 999, true))
            .isEqualTo(32768);
        assertThat(AutoBedrockPackBuilder.nextMonotonicPatchVersion(500, 40000, 999, true))
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

    @Test
    @DisplayName("pending-zip fallback carries the active sidecar's hash when it is authoritative")
    void pendingFallbackCarriesActiveHash(@TempDir Path dir) throws IOException {
        // Reproduces the promote-then-restart path: GeyserExtraExtension
        // moves "geyserextra_auto.pending.zip.pack_version" onto
        // "geyserextra_auto.zip.pack_version" as part of promoting a build
        // (extension/.../GeyserExtraExtension.java:369-375), so on the next
        // Paper start there is an active sidecar but no pending one.
        Path pendingZip = dir.resolve("geyserextra_auto.pending.zip");
        Path activeSidecar = dir.resolve("geyserextra_auto.zip.pack_version");
        Files.writeString(activeSidecar, "500\n12345\n", StandardCharsets.UTF_8);

        AutoBedrockPackBuilder.PackVersionState state =
            AutoBedrockPackBuilder.readPackVersionState(pendingZip, null);

        assertThat(state.hasContentHash()).isTrue();
        assertThat(state.lastContentHash()).isEqualTo(12345);
        assertThat(state.lastPatch()).isEqualTo(500);
    }

    @Test
    @DisplayName("pending-zip fallback with no active sidecar at all yields no known hash")
    void pendingFallbackWithNoSidecarYieldsUnknownHash(@TempDir Path dir) {
        Path pendingZip = dir.resolve("geyserextra_auto.pending.zip");

        AutoBedrockPackBuilder.PackVersionState state =
            AutoBedrockPackBuilder.readPackVersionState(pendingZip, null);

        assertThat(state.hasContentHash()).isFalse();
        assertThat(state).isEqualTo(AutoBedrockPackBuilder.PackVersionState.EMPTY);
    }
}
