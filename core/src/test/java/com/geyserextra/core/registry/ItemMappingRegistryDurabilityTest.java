package com.geyserextra.core.registry;

import com.geyserextra.core.api.CustomItemMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the ledger against the loss path that actually happened: the file is a
 * persistent record whose PDC entries can only be rebuilt by observing live
 * item stacks, so "start empty, then save" is permanent data loss.
 */
@DisplayName("ItemMappingRegistry ledger durability")
class ItemMappingRegistryDurabilityTest {

    private static CustomItemMapping pdcMapping(String name) {
        return new CustomItemMapping(
            name, "minecraft:diamond_sword", 0, false, "テスト", null,
            0, null, true, "trinityforge:" + name, null, null);
    }

    @Test
    @DisplayName("save keeps the previous generation as .bak")
    void saveKeepsBackup(@TempDir Path dir) throws IOException {
        Path ledger = dir.resolve("custom_items.json");
        ItemMappingRegistry registry = new ItemMappingRegistry();

        registry.register(pdcMapping("first"));
        registry.save(ledger);
        assertThat(ledger.resolveSibling("custom_items.json.bak")).doesNotExist();

        registry.register(pdcMapping("second"));
        registry.save(ledger);

        Path backup = ledger.resolveSibling("custom_items.json.bak");
        assertThat(backup).exists();
        assertThat(Files.readString(backup)).contains("first").doesNotContain("second");
        assertThat(Files.readString(ledger)).contains("second");
        assertThat(ledger.resolveSibling("custom_items.json.tmp")).doesNotExist();
    }

    @Test
    @DisplayName("a truncated ledger is recovered from .bak instead of starting empty")
    void recoversFromBackup(@TempDir Path dir) throws IOException {
        Path ledger = dir.resolve("custom_items.json");
        ItemMappingRegistry writer = new ItemMappingRegistry();
        writer.register(pdcMapping("first"));
        writer.save(ledger);
        writer.register(pdcMapping("second"));
        writer.save(ledger);

        // Simulate the interrupted write: the file exists but is half-written.
        Files.writeString(ledger, "{\"items\": {\"minecraft:diamond_swo");

        ItemMappingRegistry reader = new ItemMappingRegistry();
        assertThat(reader.loadIfExists(ledger)).isTrue();
        assertThat(reader.isLoadFailed()).isFalse();
        assertThat(reader.getMappings()).extracting(CustomItemMapping::name)
            .containsExactly("first");
    }

    @Test
    @DisplayName("corrupt ledger with no usable backup suppresses save (fail-closed)")
    void corruptWithoutBackupSuppressesSave(@TempDir Path dir) throws IOException {
        Path ledger = dir.resolve("custom_items.json");
        Files.writeString(ledger, "{\"items\": {\"minecraft:diamond_swo");

        ItemMappingRegistry registry = new ItemMappingRegistry();
        assertThatThrownBy(() -> registry.load(ledger)).isInstanceOf(IOException.class);
        assertThat(registry.isLoadFailed()).isTrue();

        // The startup scan would normally save right after loading. That save
        // must not turn one bad write into a wiped ledger.
        registry.register(pdcMapping("observed"));
        registry.save(ledger);
        assertThat(Files.readString(ledger)).isEqualTo("{\"items\": {\"minecraft:diamond_swo");
    }

    @Test
    @DisplayName("a legitimately empty ledger is not treated as corrupt")
    void emptyLedgerIsNotCorrupt(@TempDir Path dir) throws IOException {
        Path ledger = dir.resolve("custom_items.json");
        Files.writeString(ledger, "{\"items\": {}}");

        ItemMappingRegistry registry = new ItemMappingRegistry();
        registry.load(ledger);
        assertThat(registry.isLoadFailed()).isFalse();

        registry.register(pdcMapping("observed"));
        registry.save(ledger);
        assertThat(Files.readString(ledger)).contains("observed");
    }
}
