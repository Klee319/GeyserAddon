package com.geyserextra.paper.durability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the damage rescale that makes a custom-durability item draw the right
 * bar on Bedrock.
 *
 * <p>The arithmetic is the whole fix, and every way of getting it wrong is
 * quiet. Off by a factor and the bar lies in the other direction; forget the
 * upper clamp and a nearly-worn item reports damage past the vanilla maximum,
 * which Bedrock renders as an empty or wrapped bar; forget the lower clamp and
 * light wear on a 3000-durability sword rounds to zero, so the bar vanishes and
 * the item reads as pristine.</p>
 *
 * <p>Numbers below use the real case: a diamond sword (vanilla 1561) that
 * TrinityForge gave 3000 durability.</p>
 */
@DisplayName("Bedrock durability bar rescale")
class BedrockDurabilityBarScalerTest {

    private static final int DIAMOND_SWORD = 1561;

    @Nested
    @DisplayName("proportional rescale")
    class ProportionalRescale {

        @Test
        @DisplayName("half worn reports half of the vanilla maximum")
        void halfWornMapsToHalf() {
            // 1500/3000 spent. Unrescaled, Bedrock would draw 1500/1561 — a red
            // sliver — for an item that is exactly half fresh.
            assertThat(BedrockDurabilityBarScaler.scaledDamage(1500, 3000, DIAMOND_SWORD))
                .isEqualTo(781); // round(1500 * 1561 / 3000) = 780.5 -> 781
        }

        @Test
        @DisplayName("a quarter worn reports a quarter")
        void quarterWornMapsToQuarter() {
            assertThat(BedrockDurabilityBarScaler.scaledDamage(750, 3000, DIAMOND_SWORD))
                .isEqualTo(390); // round(390.25)
        }

        @Test
        @DisplayName("a reduced maximum is rescaled upward, not just downward")
        void reducedMaximumScalesUp() {
            // A cursed blade with 500 durability, half gone. Bedrock dividing
            // 250 by 1561 would draw an almost-full bar on a half-dead item.
            assertThat(BedrockDurabilityBarScaler.scaledDamage(250, 500, DIAMOND_SWORD))
                .isEqualTo(781); // round(780.5)
        }

        @Test
        @DisplayName("a reduced maximum makes the scaled damage exceed the override")
        void reducedMaximumProducesDamagePastTheOverride() {
            // The invariant that broke production. The scaled value is expressed
            // against the VANILLA maximum, so whenever the override is smaller
            // than vanilla it legitimately lands far above the override. Writing
            // it onto a meta that still carries that override throws
            // "Damage cannot exceed max damage" from CraftMetaItem#setDamage,
            // which killed the whole packet listener — repeatedly, in a tick
            // loop. rescaled() must therefore clear the override BEFORE writing
            // the damage. If this assertion ever flips to "within the override",
            // that ordering has stopped being load-bearing.
            // Worn almost through: the reported damage then approaches the
            // vanilla maximum, which is above the override by construction.
            for (int realMax : new int[] {50, 100, 500, 1000, 1500}) {
                int scaled = BedrockDurabilityBarScaler.scaledDamage(
                    realMax - 1, realMax, DIAMOND_SWORD);
                assertThat(scaled).as("realMax %d", realMax).isGreaterThan(realMax);
                assertThat(scaled).as("realMax %d", realMax).isLessThan(DIAMOND_SWORD);
            }
        }

        @Test
        @DisplayName("the reported ratio matches the real ratio within a percent")
        void ratioIsPreserved() {
            for (int damage : new int[] {1, 300, 900, 1500, 2400, 2999}) {
                int scaled = BedrockDurabilityBarScaler.scaledDamage(damage, 3000, DIAMOND_SWORD);
                double real = 1.0 - (double) damage / 3000;
                double shown = 1.0 - (double) scaled / DIAMOND_SWORD;
                assertThat(shown).as("damage %d", damage).isCloseTo(real, org.assertj.core.data.Offset.offset(0.01));
            }
        }
    }

    @Nested
    @DisplayName("clamping")
    class Clamping {

        @Test
        @DisplayName("undamaged stays undamaged")
        void undamagedStaysZero() {
            assertThat(BedrockDurabilityBarScaler.scaledDamage(0, 3000, DIAMOND_SWORD)).isZero();
        }

        @Test
        @DisplayName("the faintest wear still shows a bar")
        void minimalWearIsNeverRoundedAway() {
            // round(1 * 1561 / 100000) is 0. Reporting 0 would tell the client
            // the item is untouched, so the bar would disappear entirely.
            assertThat(BedrockDurabilityBarScaler.scaledDamage(1, 100000, DIAMOND_SWORD))
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a fully spent item stops one short of the maximum")
        void spentItemClampsBelowMaximum() {
            assertThat(BedrockDurabilityBarScaler.scaledDamage(3000, 3000, DIAMOND_SWORD))
                .isEqualTo(DIAMOND_SWORD - 1);
            assertThat(BedrockDurabilityBarScaler.scaledDamage(9999, 3000, DIAMOND_SWORD))
                .isEqualTo(DIAMOND_SWORD - 1);
        }

        @Test
        @DisplayName("rounding never pushes the result to the maximum")
        void roundingNeverReachesMaximum() {
            // 2999/3000 rounds to 1560.5 -> 1561, which equals the vanilla
            // maximum and would read as destroyed.
            assertThat(BedrockDurabilityBarScaler.scaledDamage(2999, 3000, DIAMOND_SWORD))
                .isEqualTo(DIAMOND_SWORD - 1);
        }
    }

    @Nested
    @DisplayName("cases that must not be touched")
    class Untouched {

        @Test
        @DisplayName("a maximum equal to vanilla is left exactly alone")
        void matchingMaximumIsUnchanged() {
            // The overwhelmingly common case. Returning the input unchanged is
            // what lets the caller skip copying the stack.
            assertThat(BedrockDurabilityBarScaler.scaledDamage(
                600, DIAMOND_SWORD, DIAMOND_SWORD)).isEqualTo(600);
        }

        @Test
        @DisplayName("a non-durable or unknown maximum is left alone")
        void nonsenseMaximaAreIgnored() {
            assertThat(BedrockDurabilityBarScaler.scaledDamage(5, 0, DIAMOND_SWORD)).isEqualTo(5);
            assertThat(BedrockDurabilityBarScaler.scaledDamage(5, -1, DIAMOND_SWORD)).isEqualTo(5);
            assertThat(BedrockDurabilityBarScaler.scaledDamage(5, 3000, 0)).isEqualTo(5);
            assertThat(BedrockDurabilityBarScaler.scaledDamage(5, 3000, -1)).isEqualTo(5);
        }

        @Test
        @DisplayName("negative damage is treated as undamaged, never as a crash")
        void negativeDamageIsZero() {
            assertThat(BedrockDurabilityBarScaler.scaledDamage(-3, 3000, DIAMOND_SWORD)).isZero();
        }

        @Test
        @DisplayName("a huge maximum cannot overflow the multiply")
        void hugeMaximumDoesNotOverflow() {
            // damage * vanillaMax as ints would overflow well before this; the
            // computation runs in double/long for that reason.
            int scaled = BedrockDurabilityBarScaler.scaledDamage(
                Integer.MAX_VALUE - 1, Integer.MAX_VALUE, DIAMOND_SWORD);
            assertThat(scaled).isBetween(0, DIAMOND_SWORD - 1);
        }
    }
}
