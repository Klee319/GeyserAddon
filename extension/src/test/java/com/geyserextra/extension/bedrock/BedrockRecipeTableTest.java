package com.geyserextra.extension.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixes how the proxy reads the corrected recipe tables.
 *
 * <p>The failure this guards against is subtle: if parsing silently yields nothing, the symptom
 * is "Bedrock players still cannot craft" — exactly the bug the feature exists to fix, so it
 * looks like the fix simply did not work rather than like a parsing problem. Every rejection path
 * must therefore report, and a table that cannot be understood must be dropped whole rather than
 * half-applied.
 */
class BedrockRecipeTableTest {

    private static final String COMPRESSION = """
        {"version":1,"source":"TrinityForge","recipes":[
          {"id":"trinityforge:catalog_source_gem_block","type":"shaped","width":3,"height":3,
           "slots":[[{"material":"AMETHYST_SHARD","cmd":100011}],[{"material":"AMETHYST_SHARD","cmd":100011}],
                    [{"material":"AMETHYST_SHARD","cmd":100011}],[{"material":"AMETHYST_SHARD","cmd":100011}],
                    [{"material":"AMETHYST_SHARD","cmd":100011}],[{"material":"AMETHYST_SHARD","cmd":100011}],
                    [{"material":"AMETHYST_SHARD","cmd":100011}],[{"material":"AMETHYST_SHARD","cmd":100011}],
                    [{"material":"AMETHYST_SHARD","cmd":100011}]],
           "result":{"material":"AMETHYST_BLOCK","cmd":100012}}
        ],"skipped":[]}""";

    private static final String SMITHING = """
        {"version":2,"source":"TrinityForge","recipes":[
          {"id":"trinityforge:catalog_star_bow_smithing","type":"smithing",
           "slots":[[{"material":"NETHERITE_UPGRADE_SMITHING_TEMPLATE"}],
                    [{"material":"BOW","cmd":100501}],
                    [{"material":"NETHERITE_INGOT"}]],
           "result":{"material":"BOW","cmd":100502}}
        ],"skipped":[]}""";

    private static List<String> warnings() {
        return new ArrayList<>();
    }

    @Test
    void readsACompressionRecipeWithItsIngredientIdentityIntact() {
        List<String> warnings = warnings();
        List<BedrockRecipeTable.Recipe> recipes =
            BedrockRecipeTable.parse(COMPRESSION, "tf.json", warnings::add);

        assertThat(warnings).isEmpty();
        assertThat(recipes).hasSize(1);
        BedrockRecipeTable.Recipe recipe = recipes.get(0);
        assertThat(recipe.shaped()).isTrue();
        assertThat(recipe.width()).isEqualTo(3);
        assertThat(recipe.slots()).hasSize(9);
        BedrockRecipeTable.ItemRef ingredient = recipe.slots().get(0).get(0);
        assertThat(ingredient.cmd()).isEqualTo(100011);
        assertThat(ingredient.isCustom()).isTrue();
        assertThat(ingredient.javaIdentifier()).isEqualTo("minecraft:amethyst_shard");
        assertThat(recipe.result().cmd()).isEqualTo(100012);
    }

    /** A vanilla ingredient carries no cmd, and must not be mistaken for a custom item. */
    @Test
    void anIngredientWithoutCmdIsVanilla() {
        List<String> warnings = warnings();
        List<BedrockRecipeTable.Recipe> recipes = BedrockRecipeTable.parse(
            "{\"version\":1,\"recipes\":[{\"id\":\"x\",\"type\":\"shapeless\","
                + "\"slots\":[[{\"material\":\"STICK\"}]],"
                + "\"result\":{\"material\":\"STICK\",\"cmd\":5,\"count\":4}}]}",
            "x.json", warnings::add);

        assertThat(warnings).isEmpty();
        BedrockRecipeTable.ItemRef ingredient = recipes.get(0).slots().get(0).get(0);
        assertThat(ingredient.isCustom()).isFalse();
        assertThat(ingredient.count()).isEqualTo(1);
        assertThat(recipes.get(0).result().count()).isEqualTo(4);
    }

    @Test
    void anUnknownFormatVersionIsDroppedWholeAndReported() {
        List<String> warnings = warnings();
        List<BedrockRecipeTable.Recipe> recipes =
            BedrockRecipeTable.parse(COMPRESSION.replace("\"version\":1", "\"version\":99"),
                "tf.json", warnings::add);

        assertThat(recipes).isEmpty();
        assertThat(warnings).singleElement().asString().contains("format version 99");
    }

    /**
     * v1 tables must keep working. The writers live in other repositories deployed by other
     * scripts, so a backend that has not been redeployed still ships v1 — rejecting it would
     * silently un-fix every crafting recipe with the symptom "the fix stopped working".
     */
    @Test
    void aVersionOneTableIsStillAccepted() {
        List<String> warnings = warnings();
        assertThat(BedrockRecipeTable.parse(COMPRESSION, "tf.json", warnings::add)).hasSize(1);
        assertThat(warnings).isEmpty();
        assertThat(BedrockRecipeTable.SUPPORTED_FORMAT_VERSIONS).contains(1, 2);
    }

    /**
     * An unrecognised {@code type} must be skipped, never guessed at. A v1 build guessed
     * "not shaped therefore shapeless", which would have turned a three-slot smithing entry into
     * a three-ingredient crafting-table recipe — a recipe the client believes in and the server
     * will never honour.
     */
    @Test
    void anUnknownRecipeTypeIsSkippedRatherThanGuessed() {
        List<String> warnings = warnings();
        List<BedrockRecipeTable.Recipe> recipes = BedrockRecipeTable.parse(
            "{\"version\":2,\"recipes\":[{\"id\":\"x\",\"type\":\"brewing\","
                + "\"slots\":[[{\"material\":\"STICK\",\"cmd\":1}]],"
                + "\"result\":{\"material\":\"STICK\"}}]}",
            "x.json", warnings::add);

        assertThat(recipes).isEmpty();
        assertThat(warnings).singleElement().asString().contains("unknown type 'brewing'");
    }

    /** A smithing entry keeps its three slots in template / base / addition order. */
    @Test
    void readsASmithingRecipeWithItsBaseIdentityIntact() {
        List<String> warnings = warnings();
        List<BedrockRecipeTable.Recipe> recipes =
            BedrockRecipeTable.parse(SMITHING, "tf.json", warnings::add);

        assertThat(warnings).isEmpty();
        assertThat(recipes).hasSize(1);
        BedrockRecipeTable.Recipe recipe = recipes.get(0);
        assertThat(recipe.type()).isEqualTo(BedrockRecipeTable.Type.SMITHING);
        assertThat(recipe.shaped()).isFalse();
        assertThat(recipe.smithing()).isTrue();
        assertThat(recipe.slots()).hasSize(BedrockRecipeTable.SMITHING_SLOT_COUNT);
        assertThat(recipe.slots().get(BedrockRecipeTable.SMITHING_TEMPLATE_SLOT).get(0).javaIdentifier())
            .isEqualTo("minecraft:netherite_upgrade_smithing_template");
        BedrockRecipeTable.ItemRef base =
            recipe.slots().get(BedrockRecipeTable.SMITHING_BASE_SLOT).get(0);
        assertThat(base.isCustom()).isTrue();
        assertThat(base.cmd()).isEqualTo(100501);
        assertThat(recipe.slots().get(BedrockRecipeTable.SMITHING_ADDITION_SLOT).get(0).javaIdentifier())
            .isEqualTo("minecraft:netherite_ingot");
        assertThat(recipe.result().cmd()).isEqualTo(100502);
    }

    /** Two slots would misalign template/base/addition; reject rather than shift them. */
    @Test
    void aSmithingRecipeWithTheWrongSlotCountIsRejected() {
        List<String> warnings = warnings();
        List<BedrockRecipeTable.Recipe> recipes = BedrockRecipeTable.parse(
            "{\"version\":2,\"recipes\":[{\"id\":\"x\",\"type\":\"smithing\","
                + "\"slots\":[[{\"material\":\"BOW\",\"cmd\":1}],[{\"material\":\"BOW\"}]],"
                + "\"result\":{\"material\":\"BOW\",\"cmd\":2}}]}",
            "x.json", warnings::add);

        assertThat(recipes).isEmpty();
        assertThat(warnings).singleElement().asString().contains("smithing with 2 slots");
    }

    /** Bedrock's smithing screen needs all three slots filled; a hole can never be satisfied. */
    @Test
    void aSmithingRecipeWithAnEmptySlotIsRejected() {
        List<String> warnings = warnings();
        List<BedrockRecipeTable.Recipe> recipes = BedrockRecipeTable.parse(
            "{\"version\":2,\"recipes\":[{\"id\":\"x\",\"type\":\"smithing\","
                + "\"slots\":[[{\"material\":\"NETHERITE_UPGRADE_SMITHING_TEMPLATE\"}],null,"
                + "[{\"material\":\"NETHERITE_INGOT\"}]],"
                + "\"result\":{\"material\":\"BOW\",\"cmd\":2}}]}",
            "x.json", warnings::add);

        assertThat(recipes).isEmpty();
        assertThat(warnings).singleElement().asString().contains("empty slot at index 1");
    }

    @Test
    void malformedJsonIsReportedRatherThanReadAsEmpty() {
        List<String> warnings = warnings();
        assertThat(BedrockRecipeTable.parse("{\"version\":1,\"recipes\":[", "tf.json", warnings::add))
            .isEmpty();
        assertThat(warnings).isNotEmpty();
    }

    /** One broken entry must not cost the rest of the file — those recipes are still correct. */
    @Test
    void oneMalformedRecipeDoesNotDiscardTheOthers() {
        List<String> warnings = warnings();
        List<BedrockRecipeTable.Recipe> recipes = BedrockRecipeTable.parse(
            "{\"version\":1,\"recipes\":[{\"id\":\"bad\"},"
                + "{\"id\":\"good\",\"type\":\"shapeless\","
                + "\"slots\":[[{\"material\":\"STICK\",\"cmd\":1}]],"
                + "\"result\":{\"material\":\"STICK\"}}]}",
            "x.json", warnings::add);

        assertThat(recipes).extracting(BedrockRecipeTable.Recipe::id).containsExactly("good");
        assertThat(warnings).hasSize(1);
    }

    /** A shaped grid whose slot count disagrees with its size would misplace every ingredient. */
    @Test
    void aShapedGridWithTheWrongSlotCountIsRejected() {
        List<String> warnings = warnings();
        List<BedrockRecipeTable.Recipe> recipes = BedrockRecipeTable.parse(
            "{\"version\":1,\"recipes\":[{\"id\":\"x\",\"type\":\"shaped\",\"width\":3,\"height\":3,"
                + "\"slots\":[[{\"material\":\"STICK\",\"cmd\":1}]],"
                + "\"result\":{\"material\":\"STICK\"}}]}",
            "x.json", warnings::add);

        assertThat(recipes).isEmpty();
        assertThat(warnings).singleElement().asString().contains("3x3");
    }

    @Test
    void unionsEveryBackendFileAndDedupesById(@TempDir Path root) throws IOException {
        Path directory = root.resolve(BedrockRecipeTable.DIRECTORY);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("main.json"), COMPRESSION, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("resource.json"), COMPRESSION, StandardCharsets.UTF_8);

        List<String> warnings = warnings();
        BedrockRecipeTable table = BedrockRecipeTable.readAll(root, warnings::add);

        assertThat(warnings).isEmpty();
        assertThat(table.recipes()).hasSize(1);
    }

    @Test
    void aMissingDirectoryIsNotAnError(@TempDir Path root) {
        List<String> warnings = warnings();
        assertThat(BedrockRecipeTable.readAll(root, warnings::add).isEmpty()).isTrue();
        assertThat(warnings).isEmpty();
    }

    /**
     * Every expanded row must be full length with {@code null} for empty squares — a short row
     * would shift the whole shaped pattern one square left and produce a different recipe.
     */
    @Test
    void expandingKeepsEveryRowAlignedWithTheGrid() {
        BedrockRecipeTable.ItemRef a = new BedrockRecipeTable.ItemRef("IRON_INGOT", null, 1);
        BedrockRecipeTable.ItemRef b = new BedrockRecipeTable.ItemRef("GOLD_INGOT", null, 1);
        BedrockRecipeTable.ItemRef c = new BedrockRecipeTable.ItemRef("STICK", 7, 1);
        List<List<BedrockRecipeTable.ItemRef>> slots =
            List.of(List.of(a, b), List.of(), List.of(c));

        assertThat(BedrockRecipeTable.combinationCount(slots)).isEqualTo(2);
        List<List<BedrockRecipeTable.ItemRef>> rows = BedrockRecipeTable.expand(slots, 32);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row).hasSize(3);
            assertThat(row.get(1)).isNull();
            assertThat(row.get(2)).isEqualTo(c);
        });
        assertThat(rows).extracting(row -> row.get(0)).containsExactlyInAnyOrder(a, b);
    }

    /** The cap truncates the number of rows, never the length of one. */
    @Test
    void theCombinationCapTruncatesRowsNotColumns() {
        BedrockRecipeTable.ItemRef a = new BedrockRecipeTable.ItemRef("A", null, 1);
        BedrockRecipeTable.ItemRef b = new BedrockRecipeTable.ItemRef("B", null, 1);
        List<List<BedrockRecipeTable.ItemRef>> slots =
            List.of(List.of(a, b), List.of(a, b), List.of(a, b), List.of(a, b));

        assertThat(BedrockRecipeTable.combinationCount(slots)).isEqualTo(16);
        List<List<BedrockRecipeTable.ItemRef>> rows = BedrockRecipeTable.expand(slots, 3);
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(4).doesNotContainNull());
        assertThat(rows).doesNotHaveDuplicates();
    }
}
