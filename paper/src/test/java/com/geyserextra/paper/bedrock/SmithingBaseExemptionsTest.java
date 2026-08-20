package com.geyserextra.paper.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins which items the smithing CMD stripper must leave alone.
 *
 * <p><b>Why this is worth a test of its own.</b> Stripping and recipe injection are two cures for
 * the same disease and cancel each other out per item: the injected smithing recipe names the
 * item by the identifier it only has <em>while</em> it still carries its CustomModelData. If this
 * set comes back empty, the stripper keeps removing the CMD, the injected recipe can never match,
 * and the symptom is <b>exactly the original bug</b> — a Bedrock player who cannot netherite-
 * upgrade a custom bow. Nothing in the logs distinguishes that from "not deployed yet", so the
 * extraction has to be pinned here rather than discovered in game.
 */
class SmithingBaseExemptionsTest {

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static final String SMITHING_ENTRY = """
        {"id":"trinityforge:catalog_netherite_bow_smithing","type":"smithing","slots":[
          [{"material":"NETHERITE_UPGRADE_SMITHING_TEMPLATE"}],
          [{"material":"BOW","cmd":10042}],
          [{"material":"NETHERITE_INGOT"}]],
         "result":{"material":"BOW","cmd":10043}}""";

    @Test
    void takesTheBaseSlotOfEverySmithingEntry() {
        Set<String> keys = SmithingBaseExemptions.fromMergedTable(parse(
            "{\"version\":2,\"recipes\":[" + SMITHING_ENTRY + "]}"));

        assertThat(keys).containsExactly("BOW#10042");
    }

    @Test
    void ignoresTheTemplateAndAdditionSlotsAndTheResult() {
        Set<String> keys = SmithingBaseExemptions.fromMergedTable(parse(
            "{\"version\":2,\"recipes\":[" + SMITHING_ENTRY + "]}"));

        // Exempting the result would keep a finished netherite bow un-stripped in a window where
        // nothing needs to match it; exempting the template/addition is meaningless (no CMD).
        assertThat(keys).doesNotContain("BOW#10043", "NETHERITE_INGOT#0");
    }

    @Test
    void ignoresCraftingRecipes() {
        // Crafting-table entries are corrected by a completely different mechanism and their
        // ingredients must keep being stripped, or the enchantment/anvil workarounds regress.
        Set<String> keys = SmithingBaseExemptions.fromMergedTable(parse("""
            {"version":2,"recipes":[
              {"id":"a","type":"shapeless","slots":[[{"material":"BOW","cmd":10042}]],
               "result":{"material":"STONE"}},
              {"id":"b","type":"shaped","width":1,"height":1,
               "slots":[[{"material":"DIAMOND_SWORD","cmd":1}]],"result":{"material":"STONE"}}]}"""));

        assertThat(keys).isEmpty();
    }

    @Test
    void ignoresAVanillaBaseBecauseItHasNoCmdToStrip() {
        Set<String> keys = SmithingBaseExemptions.fromMergedTable(parse("""
            {"version":2,"recipes":[
              {"id":"a","type":"smithing","slots":[
                [{"material":"NETHERITE_UPGRADE_SMITHING_TEMPLATE"}],
                [{"material":"DIAMOND_SWORD"}],
                [{"material":"NETHERITE_INGOT"}]],
               "result":{"material":"NETHERITE_SWORD"}}]}"""));

        assertThat(keys).isEmpty();
    }

    @Test
    void oneMalformedEntryDoesNotEmptyTheWholeSet() {
        // Emptying the set on a bad entry would silently re-enable stripping for items the proxy
        // is still shipping recipes for, i.e. break exactly what this exists to protect.
        Set<String> keys = SmithingBaseExemptions.fromMergedTable(parse(
            "{\"version\":2,\"recipes\":[{\"id\":\"broken\",\"type\":\"smithing\"},"
                + SMITHING_ENTRY + "]}"));

        assertThat(keys).containsExactly("BOW#10042");
    }

    @Test
    void aTableWithoutRecipesIsEmptyRatherThanAFailure() {
        assertThat(SmithingBaseExemptions.fromMergedTable(parse("{\"version\":2}"))).isEmpty();
        assertThat(SmithingBaseExemptions.fromMergedTable(null)).isEmpty();
    }

    @Test
    void theKeyMatchesTheMaterialNameSpelling() {
        // Material#name() is upper case on the writing side; normalising here means a lower-cased
        // table would still line up instead of exempting nothing.
        assertThat(SmithingBaseExemptions.key("bow", 10042)).isEqualTo("BOW#10042");
        assertThat(SmithingBaseExemptions.fromMergedTable(parse("""
            {"version":2,"recipes":[{"id":"a","type":"smithing","slots":[
              [{"material":"NETHERITE_UPGRADE_SMITHING_TEMPLATE"}],
              [{"material":"bow","cmd":10042}],
              [{"material":"NETHERITE_INGOT"}]],"result":{"material":"BOW","cmd":1}}]}""")))
            .containsExactly("BOW#10042");
    }

    @Test
    void anEmptySetMeansNothingIsExempt() {
        SmithingBaseExemptions exemptions = new SmithingBaseExemptions();
        assertThat(exemptions.size()).isZero();
        // The null-safe path matters: a failed collect must leave "strip everything", which is
        // the behaviour that shipped before recipe injection existed.
        exemptions.update(null);
        assertThat(exemptions.size()).isZero();
        exemptions.update(Set.of("BOW#10042"));
        assertThat(exemptions.size()).isEqualTo(1);
    }
}
