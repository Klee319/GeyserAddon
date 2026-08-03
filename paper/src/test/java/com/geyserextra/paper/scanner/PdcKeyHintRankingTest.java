package com.geyserextra.paper.scanner;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins which PDC slot the scanner treats as an item's identity.
 *
 * <p>This ordering was a real outage, not a hypothetical one: the walk used to
 * be key-major, so the winner was whichever <em>matching</em> key sorted
 * earliest. TrinityForge writes both {@code bind_type} and {@code catalog_id},
 * and {@code "b" < "c"}, so all 51 of its ledger entries were identified as
 * {@code trinityforge:tradeable} / {@code :soulbound} — every item sharing a
 * base material collapsed onto one Bedrock mapping.</p>
 *
 * <p>Only key names are exercised here. The paper module has no Bukkit harness
 * (see the note in {@code paper/build.gradle.kts}), so a test may not build an
 * {@code ItemStack}; {@link NamespacedKey} is a plain value type and is safe.</p>
 */
class PdcKeyHintRankingTest {

    private static List<String> rankedNames(String[] hints, NamespacedKey... keys) {
        return CustomItemScanner.rankKeysByHint(hints, List.of(keys)).stream()
            .map(NamespacedKey::getKey)
            .toList();
    }

    @Test
    void catalogIdBeatsBindTypeDespiteSortingLater() {
        // Deliberately passed in alphabetical order — that is exactly the input
        // the old key-major walk got wrong.
        List<String> ranked = rankedNames(
            CustomItemScanner.PDC_ID_KEY_HINTS,
            new NamespacedKey("trinityforge", "bind_type"),
            new NamespacedKey("trinityforge", "catalog_id"));

        assertEquals("catalog_id", ranked.getFirst(),
            "the identity key must win over the category key regardless of spelling");
    }

    @Test
    void displayPathRanksTheSameIdentityKeyFirst() {
        // The two extraction paths must not disagree about which slot is identity;
        // if they do, an item is registered under one id and labelled from another.
        List<String> ranked = rankedNames(
            CustomItemScanner.DISPLAY_ID_KEY_HINTS,
            new NamespacedKey("trinityforge", "bind_type"),
            new NamespacedKey("trinityforge", "catalog_id"),
            new NamespacedKey("trinityforge", "item_name"));

        assertEquals("catalog_id", ranked.getFirst());
    }

    @Test
    void typeStyleKeysRankLastButAreStillOffered() {
        List<String> ranked = rankedNames(
            CustomItemScanner.PDC_ID_KEY_HINTS,
            new NamespacedKey("trinityforge", "bind_type"),
            new NamespacedKey("oraxen", "item_id"));

        assertEquals(List.of("item_id", "bind_type"), ranked,
            "a category key is a last resort, not a disqualification:"
                + " an item carrying only bind_type still needs some identifier");
    }

    @Test
    void keysMatchingNoHintAreDropped() {
        List<String> ranked = rankedNames(
            CustomItemScanner.PDC_ID_KEY_HINTS,
            new NamespacedKey("trinityforge", "quality"),
            new NamespacedKey("trinityforge", "roll_seed"));

        assertTrue(ranked.isEmpty(),
            "attribute slots must not be mistaken for identity just because"
                + " nothing better is present");
    }

    @Test
    void eachKeyAppearsOnceEvenWhenItMatchesSeveralHints() {
        // "item_id" contains both the "item_id" and "id" hints.
        List<String> ranked = rankedNames(
            CustomItemScanner.PDC_ID_KEY_HINTS,
            new NamespacedKey("oraxen", "item_id"));

        assertEquals(List.of("item_id"), ranked);
    }
}
