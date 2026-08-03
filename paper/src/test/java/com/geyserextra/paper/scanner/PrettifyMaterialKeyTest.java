package com.geyserextra.paper.scanner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the last-resort display name the scanner invents for a mapping whose
 * item carries no name of its own.
 *
 * <p>This value reaches Bedrock players verbatim: a registered custom item is
 * named solely from the generated pack's {@code texts/*.lang}, so whatever
 * this produces is what shows in the hotbar. Only CMD / item_model mappings
 * can reach it now — art-less PDC mappings are no longer registered at all,
 * precisely so that vanilla items keep the client's own localised name — but
 * for the mappings that do reach it, a malformed string here is a malformed
 * item name in game with nothing downstream to catch it.</p>
 */
@DisplayName("CustomItemScanner.prettifyMaterialKey")
class PrettifyMaterialKeyTest {

    @Test
    @DisplayName("title-cases an underscored material key")
    void titleCasesMaterialKeys() {
        assertThat(CustomItemScanner.prettifyMaterialKey("wooden_sword")).isEqualTo("Wooden Sword");
        assertThat(CustomItemScanner.prettifyMaterialKey("netherite_chestplate"))
            .isEqualTo("Netherite Chestplate");
        assertThat(CustomItemScanner.prettifyMaterialKey("shears")).isEqualTo("Shears");
    }

    @Test
    @DisplayName("degrades safely on empty input rather than throwing")
    void handlesEmptyInput() {
        assertThat(CustomItemScanner.prettifyMaterialKey(null)).isEqualTo("Unknown");
        assertThat(CustomItemScanner.prettifyMaterialKey("")).isEqualTo("Unknown");
    }
}
