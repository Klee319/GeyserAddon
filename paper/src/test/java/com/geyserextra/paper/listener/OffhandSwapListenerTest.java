package com.geyserextra.paper.listener;

import com.geyserextra.paper.listener.OffhandSwapListener.SwapDecision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the parts of the sneak-drop off-hand swap that can be exercised
 * without a running server. The listener itself needs live Bukkit inventories,
 * which this module has no harness for, so both the count arithmetic and the
 * branch table are kept free of Bukkit types specifically so they can be
 * pinned here.
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

    @Test
    @DisplayName("an uncapped merge produces no overflow to hand back")
    void mergeWithinCapHasNoOverflow() {
        assertThat(OffhandSwapListener.overflowMainAmount(63, 1, 64)).isZero();
        assertThat(OffhandSwapListener.overflowMainAmount(0, 64, 64)).isZero();
        assertThat(OffhandSwapListener.overflowMainAmount(3, 2, 0)).isZero();
        assertThat(OffhandSwapListener.overflowMainAmount(3, 2, -1)).isZero();
    }

    @Test
    @DisplayName("whatever the cap trims is reported as overflow rather than deleted")
    void cappedMergeReportsTheTrimmedRemainder() {
        // Item-loss guard: the dropped entity is already removed when the merge
        // runs, so anything mergedMainAmount refuses to keep has to come back to
        // the player. merged + overflow must always equal the pre-drop total.
        assertThat(OffhandSwapListener.mergedMainAmount(64, 64, 64)
            + OffhandSwapListener.overflowMainAmount(64, 64, 64)).isEqualTo(128);
        assertThat(OffhandSwapListener.overflowMainAmount(64, 64, 64)).isEqualTo(64);
        assertThat(OffhandSwapListener.overflowMainAmount(1, 1, 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("no (remainder, dropped, cap) triple can create or destroy items")
    void theMergeConservesItemsForEveryInput() {
        // The property that matters: the entity is destroyed at commit time, so
        // whatever goes into the off-hand plus whatever is handed back must
        // always equal what the player had before the drop. Exhaustive over the
        // interesting range rather than sampled, because both the duplication
        // and the item-loss bugs this listener has shipped lived at the edges.
        for (int cap = -1; cap <= 64; cap++) {
            for (int remainder = 0; remainder <= 64; remainder++) {
                for (int dropped = 1; dropped <= 64; dropped++) {
                    int kept = OffhandSwapListener.mergedMainAmount(remainder, dropped, cap);
                    int handedBack = OffhandSwapListener.overflowMainAmount(remainder, dropped, cap);
                    assertThat(kept + handedBack)
                        .as("cap=%d remainder=%d dropped=%d", cap, remainder, dropped)
                        .isEqualTo(remainder + dropped);
                    assertThat(handedBack).isNotNegative();
                    assertThat(kept).isPositive();
                }
            }
        }
    }

    @Nested
    @DisplayName("next-tick branch table")
    class BranchTable {

        @Test
        @DisplayName("a drop from a player who is not sneaking at either sample is left alone")
        void nonSneakDropIsUntouched() {
            assertThat(OffhandSwapListener.decide(false, false, true, true, true))
                .isEqualTo(SwapDecision.SKIP_NOT_SNEAKING);
        }

        @Test
        @DisplayName("either sneak sample is enough, because Bedrock sends sneaking in its own packet")
        void eitherSneakSampleAcceptsTheGesture() {
            // Regression guard for the reported "the item just falls" symptom:
            // gating on the event-time sample alone rejected genuinely sneaking
            // players whose sneak packet Geyser translated after the drop.
            assertThat(OffhandSwapListener.decide(true, false, true, true, true))
                .isEqualTo(SwapDecision.COMMIT_RECONSTRUCTED);
            assertThat(OffhandSwapListener.decide(false, true, true, true, true))
                .isEqualTo(SwapDecision.COMMIT_RECONSTRUCTED);
        }

        @Test
        @DisplayName("an upstream veto that restored the stack is served by a plain exchange")
        void restoredStackIsExchangedNotReconstructed() {
            // Duplication guard: the held slot already holds the whole pre-drop
            // stack here, so reconstructing on top of it mints items. The
            // "reconstructable" input is deliberately true to prove it is
            // ignored once the entity turned out to be unusable.
            assertThat(OffhandSwapListener.decide(true, true, false, true, true))
                .isEqualTo(SwapDecision.SWAP_RESTORED_STACK);
        }

        @Test
        @DisplayName("an entity that vanished without the stack coming back aborts")
        void vanishedEntityAborts() {
            // CraftBukkit's cancel fallback calls addItem, which lands the stack
            // in an arbitrary free slot rather than the held one. Swapping anyway
            // would exchange two unrelated stacks behind the player's back.
            assertThat(OffhandSwapListener.decide(true, true, false, false, true))
                .isEqualTo(SwapDecision.ABORT_ENTITY_GONE);
        }

        @Test
        @DisplayName("a live entity whose stack does not match the held slot aborts")
        void foreignSlotAborts() {
            assertThat(OffhandSwapListener.decide(true, true, true, true, false))
                .isEqualTo(SwapDecision.ABORT_FOREIGN_SLOT);
        }

        @Test
        @DisplayName("a restore is only believed when the held slot grew by the dropped amount")
        void restoreIsRecognisedByTheCountNotJustTheItemType() {
            // Whole-stack drop: the slot was emptied, the cancel put it back.
            assertThat(OffhandSwapListener.heldSlotAbsorbedTheDrop(0, 64, 64)).isTrue();
            // Partial drop: 63 stayed behind, the cancelled 1 came back.
            assertThat(OffhandSwapListener.heldSlotAbsorbedTheDrop(63, 64, 1)).isTrue();
            // The restore landed in some other free slot -- Paper's cancel
            // fallback is a plain addItem(). The held slot still holds a
            // similar stack at its pre-drop count, and must NOT be swapped.
            assertThat(OffhandSwapListener.heldSlotAbsorbedTheDrop(10, 10, 5)).isFalse();
            assertThat(OffhandSwapListener.heldSlotAbsorbedTheDrop(0, 32, 64)).isFalse();
        }

        @Test
        @DisplayName("only one branch ever destroys the item entity")
        void exactlyOneBranchConsumesTheEntity() {
            // Every other outcome must leave the entity on the ground, which is
            // what makes the abort paths degrade to an ordinary drop instead of
            // losing the items.
            int consuming = 0;
            for (int mask = 0; mask < 32; mask++) {
                SwapDecision decision = OffhandSwapListener.decide(
                    (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0,
                    (mask & 8) != 0, (mask & 16) != 0);
                if (decision == SwapDecision.COMMIT_RECONSTRUCTED) {
                    // Committing requires a usable entity to consume and a
                    // reconstruction to write in its place.
                    assertThat((mask & 4) != 0).as("entityUsable for mask %d", mask).isTrue();
                    assertThat((mask & 16) != 0).as("reconstructable for mask %d", mask).isTrue();
                    consuming++;
                }
            }
            assertThat(consuming).isPositive();
        }
    }
}
