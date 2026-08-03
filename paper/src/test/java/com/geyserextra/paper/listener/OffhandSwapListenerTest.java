package com.geyserextra.paper.listener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the parts of the sneak-drop off-hand swap that can be exercised
 * without a running server. The listener itself needs live Bukkit inventories,
 * which this module has no harness for, so the count arithmetic is kept free
 * of Bukkit types specifically so it can be pinned here.
 */
@DisplayName("OffhandSwapListener merge arithmetic")
class OffhandSwapListenerTest {

    @Test
    @DisplayName("a single-item drop from a stack is merged back to the pre-drop count")
    void singleItemDropIsMergedBack() {
        assertThat(OffhandSwapListener.mergedMainAmount(63, 1, 64)).isEqualTo(64);
    }

    @Test
    @DisplayName("a whole-stack drop from an empty remainder keeps the dropped count")
    void wholeStackDropKeepsCount() {
        assertThat(OffhandSwapListener.mergedMainAmount(0, 64, 64)).isEqualTo(64);
    }

    @Test
    @DisplayName("the merge never exceeds the stack cap")
    void mergeIsCappedAtMaxStackSize() {
        // Guards the duplication path: if a future change makes Bukkit restore
        // the stack before this runs, the total overshoots and must be clamped
        // rather than minting items.
        assertThat(OffhandSwapListener.mergedMainAmount(64, 64, 64)).isEqualTo(64);
        assertThat(OffhandSwapListener.mergedMainAmount(1, 1, 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("a non-positive stack cap is treated as no cap rather than zeroing the stack")
    void nonPositiveCapDoesNotDestroyTheStack() {
        // getMaxStackSize() should never be <= 0, but returning 0 here would
        // silently delete the player's items, so the guard is asserted.
        assertThat(OffhandSwapListener.mergedMainAmount(3, 2, 0)).isEqualTo(5);
        assertThat(OffhandSwapListener.mergedMainAmount(3, 2, -1)).isEqualTo(5);
    }
}
