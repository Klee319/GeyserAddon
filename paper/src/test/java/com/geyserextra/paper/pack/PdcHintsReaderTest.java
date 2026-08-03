package com.geyserextra.paper.pack;

import com.geyserextra.core.api.CustomItemMapping;
import com.geyserextra.core.registry.ItemMappingRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the pre-registration path for PDC-identified items — the one route that
 * lets an item exist on Bedrock before a player has held it.
 */
@DisplayName("PdcHintsReader")
class PdcHintsReaderTest {

    private static final Logger LOG = Logger.getLogger(PdcHintsReaderTest.class.getName());

    private static Path writeHints(Path dir, String name, String json) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, json);
        return file;
    }

    @Test
    @DisplayName("a missing directory is the unused-feature case, not an error")
    void missingDirectoryYieldsNothing(@TempDir Path dir) {
        assertThat(PdcHintsReader.readAll(dir.resolve("absent"), LOG)).isEmpty();
    }

    @Test
    @DisplayName("valid entries are read and the base item is normalised")
    void readsAndNormalises(@TempDir Path dir) throws IOException {
        writeHints(dir, "tf.json", """
            {"entries": [
              {"pdc_identifier": "trinityforge:flame_sword",
               "base_item": "DIAMOND_SWORD",
               "display_name": "炎の剣"},
              {"pdc_identifier": "trinityforge:mage_robe",
               "base_item": "minecraft:leather_chestplate",
               "unbreakable": true}
            ]}
            """);

        List<PdcHintsReader.PdcHint> hints = PdcHintsReader.readAll(dir, LOG);

        assertThat(hints).hasSize(2);
        assertThat(hints.get(0).baseItem()).isEqualTo("minecraft:diamond_sword");
        assertThat(hints.get(0).displayName()).isEqualTo("炎の剣");
        assertThat(hints.get(0).unbreakable()).isFalse();
        assertThat(hints.get(1).unbreakable()).isTrue();
    }

    @Test
    @DisplayName("one malformed file does not disable the others")
    void malformedFileIsSkipped(@TempDir Path dir) throws IOException {
        writeHints(dir, "a-broken.json", "{ not json");
        writeHints(dir, "b-good.json", """
            {"entries": [{"pdc_identifier": "tf:ok", "base_item": "stick"}]}
            """);

        assertThat(PdcHintsReader.readAll(dir, LOG))
            .extracting(PdcHintsReader.PdcHint::pdcIdentifier)
            .containsExactly("tf:ok");
    }

    @Test
    @DisplayName("entries missing a required field are skipped, not registered blank")
    void incompleteEntriesSkipped(@TempDir Path dir) throws IOException {
        writeHints(dir, "tf.json", """
            {"entries": [
              {"base_item": "stick"},
              {"pdc_identifier": "tf:no_base"},
              {"pdc_identifier": "tf:ok", "base_item": "stick"}
            ]}
            """);

        assertThat(PdcHintsReader.readAll(dir, LOG)).hasSize(1);
    }

    @Test
    @DisplayName("prepopulate registers the item so Bedrock sees it before anyone holds one")
    void prepopulateRegisters(@TempDir Path dir) throws IOException {
        writeHints(dir, "tf.json", """
            {"entries": [{"pdc_identifier": "trinityforge:flame_sword",
                          "base_item": "minecraft:diamond_sword",
                          "display_name": "炎の剣"}]}
            """);
        ItemMappingRegistry registry = new ItemMappingRegistry();

        int added = PdcHintsReader.prepopulate(
            registry, PdcHintsReader.readAll(dir, LOG), LOG);

        assertThat(added).isEqualTo(1);
        CustomItemMapping mapping = registry
            .getByPdc("minecraft:diamond_sword", "trinityforge:flame_sword")
            .orElseThrow();
        assertThat(mapping.displayName()).isEqualTo("炎の剣");
        // register=true is what grants the Bedrock off-hand permission.
        assertThat(mapping.register()).isTrue();
        assertThat(mapping.customModelData()).isZero();
    }

    @Test
    @DisplayName("prepopulate never overwrites an entry the scanner already observed")
    void prepopulateKeepsObservedEntry(@TempDir Path dir) throws IOException {
        writeHints(dir, "tf.json", """
            {"entries": [{"pdc_identifier": "trinityforge:flame_sword",
                          "base_item": "minecraft:diamond_sword",
                          "display_name": "hint name"}]}
            """);
        ItemMappingRegistry registry = new ItemMappingRegistry();
        registry.register(new CustomItemMapping(
            "observed", "minecraft:diamond_sword", 0, false, "observed name", null,
            0, null, true, "trinityforge:flame_sword", null, null));

        assertThat(PdcHintsReader.prepopulate(
            registry, PdcHintsReader.readAll(dir, LOG), LOG)).isZero();
        assertThat(registry.getByPdc("minecraft:diamond_sword", "trinityforge:flame_sword")
            .orElseThrow().displayName()).isEqualTo("observed name");
    }
}
