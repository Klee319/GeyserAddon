package com.geyserextra.paper.pack;

import com.geyserextra.core.api.CustomItemMapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code texts/} files the auto-pack ships. Without them the Bedrock
 * client has no source for a custom item's name and prints the raw
 * {@code geyserextra:<name>} identifier instead.
 */
@DisplayName("AutoBedrockPackBuilder texts/ generation")
class AutoBedrockPackLangTest {

    private static CustomItemMapping mapping(String name, String displayName) {
        return new CustomItemMapping(
            name, "minecraft:book", 100004, false, displayName,
            null, CustomItemMapping.CREATIVE_CATEGORY_ITEMS, null, true, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, byte[]> build(List<CustomItemMapping> mappings) throws Exception {
        Method m = AutoBedrockPackBuilder.class.getDeclaredMethod(
            "buildLangFiles", java.util.Collection.class, java.util.logging.Logger.class);
        m.setAccessible(true);
        return (Map<String, byte[]>) m.invoke(null, mappings, null);
    }

    private static String text(Map<String, byte[]> files, String entry) {
        assertThat(files).containsKey(entry);
        return new String(files.get(entry), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("every locale carries the item name under the geyserextra namespace")
    void writesNamesForEveryLocale() throws Exception {
        Map<String, byte[]> files = build(List.of(mapping("custom_book_100004", "広辞苑")));

        for (String entry : List.of("texts/en_US.lang", "texts/ja_JP.lang")) {
            assertThat(text(files, entry))
                .contains("item.geyserextra:custom_book_100004=広辞苑")
                .contains("item.geyserextra:custom_book_100004.name=広辞苑");
        }
        assertThat(text(files, "texts/languages.json"))
            .contains("\"en_US\"").contains("\"ja_JP\"");
    }

    @Test
    @DisplayName("output is byte-identical regardless of mapping order so the patch hash is stable")
    void outputIsOrderIndependent() throws Exception {
        CustomItemMapping a = mapping("custom_a", "あ");
        CustomItemMapping b = mapping("custom_b", "い");

        assertThat(text(build(List.of(a, b)), "texts/en_US.lang"))
            .isEqualTo(text(build(List.of(b, a)), "texts/en_US.lang"));
    }

    @Test
    @DisplayName("a name containing a newline cannot truncate the entry or forge a second key")
    void newlinesAreFlattened() throws Exception {
        String body = text(
            build(List.of(mapping("custom_multiline", "行1\n行2"))), "texts/ja_JP.lang");

        assertThat(body).contains("item.geyserextra:custom_multiline=行1 行2");
        // One comment line + two keys for the single item, nothing else.
        assertThat(body.lines().filter(l -> !l.startsWith("##")).toList()).hasSize(2);
    }

    @Test
    @DisplayName("items without a display name are skipped rather than named after their ID")
    void blankDisplayNamesAreSkipped() throws Exception {
        assertThat(build(List.of(mapping("custom_unnamed", null)))).isEmpty();
        assertThat(build(List.of(mapping("custom_blank", "   ")))).isEmpty();
    }

    @Test
    @DisplayName("the identifier is sanitized the same way the extension sanitizes it")
    void identifierMatchesExtensionSanitisation() throws Exception {
        // CustomItemsHandler runs the mapping name through the same
        // [^a-z0-9_\-./] filter before Identifier.of(). If these two ever
        // disagree the lang key stops matching and names silently revert to
        // showing the ID.
        assertThat(text(build(List.of(mapping("Custom Item#1", "テスト"))), "texts/en_US.lang"))
            .contains("item.geyserextra:custom_item_1=テスト");
    }
}
