package com.geyserextra.paper.dimension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the file lifecycle the repair depends on: flags are consume-once,
 * pending returns survive reads by the wrong backend, and a corrupt file
 * degrades to "no repair" instead of an exception at join time.
 */
@DisplayName("DimensionHandoffStore")
class DimensionHandoffStoreTest {

    private static final Logger LOGGER = Logger.getLogger("test");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final long NOW = 1_000_000L;

    @TempDir
    Path dir;

    private DimensionHandoffStore store() {
        return new DimensionHandoffStore(dir.resolve("dimension-handoff"), LOGGER);
    }

    @Test
    @DisplayName("a flag round-trips and is deleted by consumption")
    void flagIsConsumeOnce() {
        DimensionHandoffStore store = store();
        store.writeFlag(PLAYER, new DimensionHandoffFlag(NOW, "NORMAL", true));

        DimensionHandoffFlag flag = store.consumeFlag(PLAYER);
        assertThat(flag).isNotNull();
        assertThat(flag.quitAtMs()).isEqualTo(NOW);
        assertThat(flag.environment()).isEqualTo("NORMAL");
        assertThat(flag.inPortal()).isTrue();

        // One risky quit must trigger at most one repair: a second join —
        // or a second backend racing the first — finds nothing.
        assertThat(store.consumeFlag(PLAYER)).isNull();
    }

    @Test
    @DisplayName("consuming a flag that was never written returns null")
    void missingFlagIsNull() {
        assertThat(store().consumeFlag(PLAYER)).isNull();
    }

    @Test
    @DisplayName("a corrupt flag file reads as null and is still deleted")
    void corruptFlagDegradesToNull() throws IOException {
        Path folder = dir.resolve("dimension-handoff");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(PLAYER + ".json"), "{not json");

        DimensionHandoffStore store = store();
        assertThat(store.consumeFlag(PLAYER)).isNull();
        assertThat(Files.exists(folder.resolve(PLAYER + ".json"))).isFalse();
    }

    @Test
    @DisplayName("a pending return survives being read, until it is deleted explicitly")
    void pendingReturnIsNotConsumedByReading() {
        DimensionHandoffStore store = store();
        store.writePendingReturn(PLAYER, new DimensionHandoffStore.PendingReturn(
            "6f9619ff-8b86-4d01-b42d-00c04fc964ff", 1.5, 64.0, -3.5, 90f, 10f, false, NOW));

        // The shared folder serves every backend. A join on a backend that
        // does not own the recorded world reads the file, cannot act on it,
        // and must leave it for the backend that can.
        DimensionHandoffStore.PendingReturn first = store.readPendingReturn(PLAYER, NOW + 1_000);
        DimensionHandoffStore.PendingReturn second = store.readPendingReturn(PLAYER, NOW + 2_000);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.worldUid()).isEqualTo("6f9619ff-8b86-4d01-b42d-00c04fc964ff");
        assertThat(second.x()).isEqualTo(1.5);
        assertThat(second.yaw()).isEqualTo(90f);
        assertThat(second.invulnerable()).isFalse();

        store.deletePendingReturn(PLAYER);
        assertThat(store.readPendingReturn(PLAYER, NOW + 3_000)).isNull();
    }

    @Test
    @DisplayName("an expired pending return is deleted and not returned")
    void expiredPendingReturnIsDropped() {
        DimensionHandoffStore store = store();
        store.writePendingReturn(PLAYER, new DimensionHandoffStore.PendingReturn(
            "6f9619ff-8b86-4d01-b42d-00c04fc964ff", 0, 64, 0, 0f, 0f, true, NOW));

        // An hour later the player has legitimately moved on; teleporting
        // them back to a week-old spot would itself be the bug.
        long later = NOW + DimensionHandoffStore.PENDING_RETURN_MAX_AGE_MS + 1;
        assertThat(store.readPendingReturn(PLAYER, later)).isNull();
        // And it stays gone.
        assertThat(store.readPendingReturn(PLAYER, NOW + 1)).isNull();
    }

    @Test
    @DisplayName("rewriting a flag overwrites the previous one")
    void flagOverwrites() {
        DimensionHandoffStore store = store();
        store.writeFlag(PLAYER, new DimensionHandoffFlag(NOW, "NORMAL", false));
        store.writeFlag(PLAYER, new DimensionHandoffFlag(NOW + 100, "NETHER", true));

        DimensionHandoffFlag flag = store.consumeFlag(PLAYER);
        assertThat(flag).isNotNull();
        assertThat(flag.quitAtMs()).isEqualTo(NOW + 100);
        assertThat(flag.environment()).isEqualTo("NETHER");
    }
}
