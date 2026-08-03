package com.geyserextra.core.registry;

import com.geyserextra.core.api.CustomItemMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the per-base ordering written by {@link ItemMappingRegistry#save(Path)}.
 *
 * <p>Why this matters: Geyser v2 registers the first definition that claims a
 * base item, and the PDC predicate can only test that {@code custom_data} is
 * <em>present</em> — it cannot inspect the value. So every PDC mapping on one
 * material matches the same stacks and the written order alone decides which
 * definition an item gets. Nothing else in the suite fails if the ordering
 * regresses; the symptom is only visible on a Bedrock client.</p>
 */
class ItemMappingRegistrySpecificityOrderTest {

    @TempDir
    Path tempDir;

    private static CustomItemMapping pdcMapping(String name, String baseItem, String pdcId) {
        return new CustomItemMapping(
            name, baseItem, 0, false, name, null, 0, null, true, pdcId);
    }

    private static int indexOfName(String json, String name) {
        int index = json.indexOf("\"name\": \"" + name + "\"");
        assertTrue(index >= 0, "mapping '" + name + "' missing from saved json:\n" + json);
        return index;
    }

    @Test
    void categoryWidePdcIdentifierIsWrittenAfterTheItemSpecificOne() throws Exception {
        ItemMappingRegistry registry = new ItemMappingRegistry();

        // "tradeable" is an attribute value, not an identity: it lands on many
        // unrelated materials. Three distinct base items is what marks it generic.
        registry.register(pdcMapping("tf_tradeable_hoe", "minecraft:netherite_hoe", "trinityforge:tradeable"));
        registry.register(pdcMapping("tf_tradeable_axe", "minecraft:netherite_axe", "trinityforge:tradeable"));
        registry.register(pdcMapping("tf_tradeable_bow", "minecraft:bow", "trinityforge:tradeable"));

        registry.register(pdcMapping(
            "tf_winter_grim_reaper", "minecraft:netherite_hoe", "trinityforge:winter_grim_reaper"));

        Path file = tempDir.resolve("custom_items.json");
        registry.save(file);
        String json = Files.readString(file);

        assertTrue(
            indexOfName(json, "tf_winter_grim_reaper") < indexOfName(json, "tf_tradeable_hoe"),
            "the item-specific PDC mapping must be registered before the category-wide one,"
                + " otherwise the category entry swallows it on Bedrock");
    }

    @Test
    void customModelDataAndItemModelMappingsOutrankPdcOnesOnTheSameBase() throws Exception {
        ItemMappingRegistry registry = new ItemMappingRegistry();
        String base = "minecraft:blaze_rod";

        registry.register(pdcMapping("pdc_only", base, "trinityforge:abyss_cane"));
        registry.register(new CustomItemMapping(
            "item_model_only", base, 0, false, "Item Model", null, 0, null, true,
            null, null, "trinityforge:item/abyss_cane"));
        registry.register(new CustomItemMapping(
            "cmd_only", base, 41, false, "Cmd", null, 0, null, true));

        Path file = tempDir.resolve("custom_items.json");
        registry.save(file);
        String json = Files.readString(file);

        int cmd = indexOfName(json, "cmd_only");
        int itemModel = indexOfName(json, "item_model_only");
        int pdc = indexOfName(json, "pdc_only");

        assertTrue(cmd < itemModel,
            "CMD matches a single value and must be registered first");
        assertTrue(itemModel < pdc,
            "item_model matches a single model id and must outrank the value-blind PDC predicate");
    }

    @Test
    void identifierSharedByJustTwoBaseItemsIsNotDemoted() throws Exception {
        ItemMappingRegistry registry = new ItemMappingRegistry();

        // A genuine id can legitimately span a couple of materials (an armour
        // set written with one id). Demoting those would be the opposite error.
        registry.register(pdcMapping("set_helmet", "minecraft:netherite_helmet", "trinityforge:abyss_set"));
        registry.register(pdcMapping("set_boots", "minecraft:netherite_boots", "trinityforge:abyss_set"));
        registry.register(pdcMapping("other_helmet", "minecraft:netherite_helmet", "trinityforge:zzz_other"));

        Path file = tempDir.resolve("custom_items.json");
        registry.save(file);
        String json = Files.readString(file);

        // Same rank, so the tiebreak is the mapping name: "other_helmet" < "set_helmet".
        assertTrue(indexOfName(json, "other_helmet") < indexOfName(json, "set_helmet"),
            "a two-material identifier must keep its normal rank and sort by name");
    }
}
