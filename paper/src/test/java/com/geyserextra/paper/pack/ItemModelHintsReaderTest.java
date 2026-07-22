package com.geyserextra.paper.pack;

import com.geyserextra.core.api.CustomItemMapping;
import com.geyserextra.core.registry.ItemMappingRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemModelHintsReaderTest {

    private static final Logger LOGGER = Logger.getLogger("ItemModelHintsReaderTest");

    @TempDir
    Path tempDir;

    private Path hintsDir() throws Exception {
        Path dir = tempDir.resolve("item_model_hints");
        Files.createDirectories(dir);
        return dir;
    }

    // ------------------------------------------------------------------
    // readAll
    // ------------------------------------------------------------------

    @Test
    void missingDirectoryYieldsEmptyList() {
        List<ItemModelHintsReader.ItemModelHint> hints =
            ItemModelHintsReader.readAll(tempDir.resolve("does_not_exist"), LOGGER);
        assertTrue(hints.isEmpty());
    }

    @Test
    void readsValidEntriesWithDisplayName() throws Exception {
        Path dir = hintsDir();
        Files.writeString(dir.resolve("myplugin.json"), """
            {
              "entries": [
                {"item_model": "myplugin:gui/icon_a", "base_item": "minecraft:paper",
                 "display_name": "Icon A"},
                {"item_model": "myplugin:gui/icon_b", "base_item": "minecraft:arrow"}
              ]
            }
            """);

        List<ItemModelHintsReader.ItemModelHint> hints =
            ItemModelHintsReader.readAll(dir, LOGGER);

        assertEquals(2, hints.size());
        assertEquals("myplugin:gui/icon_a", hints.get(0).itemModelId());
        assertEquals("minecraft:paper", hints.get(0).baseItem());
        assertEquals("Icon A", hints.get(0).displayName());
        assertNull(hints.get(1).displayName());
    }

    @Test
    void normalizesBareBaseItemToMinecraftNamespace() throws Exception {
        Path dir = hintsDir();
        Files.writeString(dir.resolve("a.json"), """
            {"entries": [{"item_model": "ns:m", "base_item": "DIAMOND_PICKAXE"}]}
            """);

        List<ItemModelHintsReader.ItemModelHint> hints =
            ItemModelHintsReader.readAll(dir, LOGGER);

        assertEquals(1, hints.size());
        assertEquals("minecraft:diamond_pickaxe", hints.get(0).baseItem());
    }

    @Test
    void skipsMinecraftNamespaceItemModels() throws Exception {
        Path dir = hintsDir();
        Files.writeString(dir.resolve("a.json"), """
            {"entries": [
              {"item_model": "minecraft:bow", "base_item": "minecraft:bow"},
              {"item_model": "ns:ok", "base_item": "minecraft:paper"}
            ]}
            """);

        List<ItemModelHintsReader.ItemModelHint> hints =
            ItemModelHintsReader.readAll(dir, LOGGER);

        assertEquals(1, hints.size());
        assertEquals("ns:ok", hints.get(0).itemModelId());
    }

    @Test
    void skipsEntriesMissingRequiredFields() throws Exception {
        Path dir = hintsDir();
        Files.writeString(dir.resolve("a.json"), """
            {"entries": [
              {"item_model": "", "base_item": "minecraft:paper"},
              {"item_model": "ns:no_base"},
              {"base_item": "minecraft:paper"},
              {"item_model": "ns:ok", "base_item": "minecraft:paper"}
            ]}
            """);

        List<ItemModelHintsReader.ItemModelHint> hints =
            ItemModelHintsReader.readAll(dir, LOGGER);

        assertEquals(1, hints.size());
        assertEquals("ns:ok", hints.get(0).itemModelId());
    }

    @Test
    void malformedFileDoesNotAbortOtherFiles() throws Exception {
        Path dir = hintsDir();
        Files.writeString(dir.resolve("a_broken.json"), "{not json!!");
        Files.writeString(dir.resolve("b_valid.json"), """
            {"entries": [{"item_model": "ns:m", "base_item": "minecraft:paper"}]}
            """);

        List<ItemModelHintsReader.ItemModelHint> hints =
            ItemModelHintsReader.readAll(dir, LOGGER);

        assertEquals(1, hints.size());
        assertEquals("ns:m", hints.get(0).itemModelId());
    }

    @Test
    void duplicateBasePlusModelFirstWins() throws Exception {
        Path dir = hintsDir();
        Files.writeString(dir.resolve("a.json"), """
            {"entries": [
              {"item_model": "ns:m", "base_item": "minecraft:paper", "display_name": "First"},
              {"item_model": "ns:m", "base_item": "minecraft:paper", "display_name": "Second"}
            ]}
            """);

        List<ItemModelHintsReader.ItemModelHint> hints =
            ItemModelHintsReader.readAll(dir, LOGGER);

        assertEquals(1, hints.size());
        assertEquals("First", hints.get(0).displayName());
    }

    @Test
    void sameModelOnDifferentBaseItemsBothKept() throws Exception {
        Path dir = hintsDir();
        Files.writeString(dir.resolve("a.json"), """
            {"entries": [
              {"item_model": "ns:m", "base_item": "minecraft:paper"},
              {"item_model": "ns:m", "base_item": "minecraft:arrow"}
            ]}
            """);

        List<ItemModelHintsReader.ItemModelHint> hints =
            ItemModelHintsReader.readAll(dir, LOGGER);

        assertEquals(2, hints.size());
    }

    @Test
    void ignoresNonJsonFiles() throws Exception {
        Path dir = hintsDir();
        Files.writeString(dir.resolve("readme.txt"), "not a hint");
        Files.writeString(dir.resolve("a.json"), """
            {"entries": [{"item_model": "ns:m", "base_item": "minecraft:paper"}]}
            """);

        List<ItemModelHintsReader.ItemModelHint> hints =
            ItemModelHintsReader.readAll(dir, LOGGER);

        assertEquals(1, hints.size());
    }

    // ------------------------------------------------------------------
    // prepopulate
    // ------------------------------------------------------------------

    private static ItemModelHintsReader.ItemModelHint hint(
        String model, String base, String displayName
    ) {
        return new ItemModelHintsReader.ItemModelHint(model, base, displayName, "test.json");
    }

    @Test
    void prepopulateRegistersMappingWithItemModelId() {
        ItemMappingRegistry registry = new ItemMappingRegistry();

        int added = ItemModelHintsReader.prepopulate(
            registry,
            List.of(hint("ns:gui/icon", "minecraft:paper", "Icon")),
            Set.of("ns:gui/icon"),
            LOGGER);

        assertEquals(1, added);
        CustomItemMapping mapping = registry
            .getByItemModel("minecraft:paper", "ns:gui/icon").orElseThrow();
        assertEquals("ns:gui/icon", mapping.itemModelId());
        assertEquals(0, mapping.customModelData());
        assertTrue(mapping.register());
        assertEquals("Icon", mapping.displayName());
    }

    @Test
    void prepopulateSkipsModelsAbsentFromConfiguredPacks() {
        ItemMappingRegistry registry = new ItemMappingRegistry();

        int added = ItemModelHintsReader.prepopulate(
            registry,
            List.of(hint("ns:gui/missing", "minecraft:paper", null)),
            Set.of("ns:gui/other"),
            LOGGER);

        assertEquals(0, added);
        assertFalse(registry.getByItemModel("minecraft:paper", "ns:gui/missing").isPresent());
    }

    @Test
    void prepopulateSkipsAlreadyRegisteredPairs() {
        ItemMappingRegistry registry = new ItemMappingRegistry();
        registry.register(new CustomItemMapping(
            "existing", "minecraft:paper", 0, false, "Existing", null,
            CustomItemMapping.CREATIVE_CATEGORY_ITEMS, null, true, null, null,
            "ns:gui/icon"));

        int added = ItemModelHintsReader.prepopulate(
            registry,
            List.of(hint("ns:gui/icon", "minecraft:paper", "Hint Name")),
            Set.of("ns:gui/icon"),
            LOGGER);

        assertEquals(0, added);
        assertEquals("Existing", registry
            .getByItemModel("minecraft:paper", "ns:gui/icon").orElseThrow().displayName());
    }

    @Test
    void prepopulateSameModelOnTwoBaseItemsGetsDistinctNames() {
        ItemMappingRegistry registry = new ItemMappingRegistry();

        int added = ItemModelHintsReader.prepopulate(
            registry,
            List.of(
                hint("ns:gui/icon", "minecraft:paper", null),
                hint("ns:gui/icon", "minecraft:arrow", null)),
            Set.of("ns:gui/icon"),
            LOGGER);

        assertEquals(2, added);
        assertTrue(registry.getByItemModel("minecraft:paper", "ns:gui/icon").isPresent());
        assertTrue(registry.getByItemModel("minecraft:arrow", "ns:gui/icon").isPresent());
    }
}
