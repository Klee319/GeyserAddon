package com.geyserextra.core.registry;

import com.geyserextra.core.api.CustomItemMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemMappingRegistryItemModelTest {

    @TempDir
    Path tempDir;

    @Test
    void savesLoadsAndIndexesDirectItemModel() throws Exception {
        ItemMappingRegistry registry = new ItemMappingRegistry();
        registry.register(new CustomItemMapping(
            "itemmodel_trinityforge_gui_lightweapons",
            "minecraft:paper",
            0,
            false,
            "Light Weapons",
            null,
            4,
            null,
            true,
            null,
            null,
            "trinityforge:gui/lightweapons"
        ));
        Path file = tempDir.resolve("custom_items.json");
        registry.save(file);

        ItemMappingRegistry loaded = new ItemMappingRegistry();
        loaded.load(file);

        assertTrue(loaded.getByItemModel(
            "minecraft:paper", "trinityforge:gui/lightweapons").isPresent());
        assertEquals("trinityforge:gui/lightweapons",
            loaded.getByName("itemmodel_trinityforge_gui_lightweapons")
                .orElseThrow().itemModelId());
    }
}
