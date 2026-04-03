package com.geyserextra.paper.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;

import java.util.Locale;
import java.util.Map;

/**
 * Utility for rendering Adventure Components to plain text in Japanese.
 *
 * Why: Floodgate SimpleForm accepts only plain String content, not Components.
 * Bedrock Forms do not support Adventure's translatable components directly.
 * This utility uses Paper's GlobalTranslator to render translatable keys
 * into the target locale (Japanese) on the server side, then serializes
 * the result as plain text for form display.
 *
 * Fallback: If GlobalTranslator does not have Japanese translations loaded
 * (the rendered output equals the translation key), the English-rendered
 * version is returned instead, since Paper always has English translations.
 */
public final class TranslationUtil {

    /** Target locale for rendering translatable components. */
    private static final Locale JAPANESE = Locale.JAPANESE;

    /** Fallback locale when Japanese translation is not available. */
    private static final Locale ENGLISH = Locale.ENGLISH;

    /**
     * Fallback Japanese translations for common Minecraft entities, blocks, and items.
     *
     * Why: Paper's GlobalTranslator may not have Japanese translations loaded,
     * causing entity/block/item names to display in English or as raw keys.
     * This HashMap provides a reliable fallback for the most commonly used
     * translation keys in the statistics and advancement forms.
     */
    private static final Map<String, String> JAPANESE_TRANSLATIONS = Map.ofEntries(
        // Entities
        Map.entry("entity.minecraft.zombie", "\u30BE\u30F3\u30D3"),
        Map.entry("entity.minecraft.skeleton", "\u30B9\u30B1\u30EB\u30C8\u30F3"),
        Map.entry("entity.minecraft.creeper", "\u30AF\u30EA\u30FC\u30D1\u30FC"),
        Map.entry("entity.minecraft.spider", "\u30AF\u30E2"),
        Map.entry("entity.minecraft.enderman", "\u30A8\u30F3\u30C0\u30FC\u30DE\u30F3"),
        Map.entry("entity.minecraft.witch", "\u30A6\u30A3\u30C3\u30C1"),
        Map.entry("entity.minecraft.blaze", "\u30D6\u30EC\u30A4\u30BA"),
        Map.entry("entity.minecraft.ghast", "\u30AC\u30B9\u30C8"),
        Map.entry("entity.minecraft.slime", "\u30B9\u30E9\u30A4\u30E0"),
        Map.entry("entity.minecraft.phantom", "\u30D5\u30A1\u30F3\u30C8\u30E0"),
        Map.entry("entity.minecraft.drowned", "\u30C9\u30E9\u30A6\u30F3\u30C9"),
        Map.entry("entity.minecraft.pillager", "\u30D4\u30EA\u30B8\u30E3\u30FC"),
        Map.entry("entity.minecraft.warden", "\u30A6\u30A9\u30FC\u30C7\u30F3"),
        Map.entry("entity.minecraft.wither", "\u30A6\u30A3\u30B6\u30FC"),
        Map.entry("entity.minecraft.ender_dragon", "\u30A8\u30F3\u30C0\u30FC\u30C9\u30E9\u30B4\u30F3"),
        // Blocks / Materials
        Map.entry("block.minecraft.stone", "\u77F3"),
        Map.entry("block.minecraft.dirt", "\u571F"),
        Map.entry("block.minecraft.sand", "\u7802"),
        Map.entry("block.minecraft.coal_ore", "\u77F3\u70AD\u9271\u77F3"),
        Map.entry("block.minecraft.deepslate_coal_ore", "\u6DF1\u5C64\u77F3\u70AD\u9271\u77F3"),
        Map.entry("block.minecraft.iron_ore", "\u9244\u9271\u77F3"),
        Map.entry("block.minecraft.deepslate_iron_ore", "\u6DF1\u5C64\u9244\u9271\u77F3"),
        Map.entry("block.minecraft.gold_ore", "\u91D1\u9271\u77F3"),
        Map.entry("block.minecraft.deepslate_gold_ore", "\u6DF1\u5C64\u91D1\u9271\u77F3"),
        Map.entry("block.minecraft.diamond_ore", "\u30C0\u30A4\u30E4\u30E2\u30F3\u30C9\u9271\u77F3"),
        Map.entry("block.minecraft.deepslate_diamond_ore", "\u6DF1\u5C64\u30C0\u30A4\u30E4\u30E2\u30F3\u30C9\u9271\u77F3"),
        Map.entry("block.minecraft.emerald_ore", "\u30A8\u30E1\u30E9\u30EB\u30C9\u9271\u77F3"),
        Map.entry("block.minecraft.deepslate_emerald_ore", "\u6DF1\u5C64\u30A8\u30E1\u30E9\u30EB\u30C9\u9271\u77F3"),
        Map.entry("block.minecraft.lapis_ore", "\u30E9\u30D4\u30B9\u30E9\u30BA\u30EA\u9271\u77F3"),
        Map.entry("block.minecraft.deepslate_lapis_ore", "\u6DF1\u5C64\u30E9\u30D4\u30B9\u30E9\u30BA\u30EA\u9271\u77F3"),
        Map.entry("block.minecraft.redstone_ore", "\u30EC\u30C3\u30C9\u30B9\u30C8\u30FC\u30F3\u9271\u77F3"),
        Map.entry("block.minecraft.deepslate_redstone_ore", "\u6DF1\u5C64\u30EC\u30C3\u30C9\u30B9\u30C8\u30FC\u30F3\u9271\u77F3"),
        Map.entry("block.minecraft.copper_ore", "\u9285\u9271\u77F3"),
        Map.entry("block.minecraft.deepslate_copper_ore", "\u6DF1\u5C64\u9285\u9271\u77F3"),
        Map.entry("block.minecraft.ancient_debris", "\u53E4\u4EE3\u306E\u6B8B\u9AB8"),
        Map.entry("block.minecraft.obsidian", "\u9ED2\u66DC\u77F3"),
        Map.entry("block.minecraft.netherrack", "\u30CD\u30B6\u30FC\u30E9\u30C3\u30AF"),
        Map.entry("block.minecraft.crafting_table", "\u4F5C\u696D\u53F0"),
        Map.entry("block.minecraft.furnace", "\u304B\u307E\u3069"),
        Map.entry("block.minecraft.chest", "\u30C1\u30A7\u30B9\u30C8"),
        Map.entry("block.minecraft.enchanting_table", "\u30A8\u30F3\u30C1\u30E3\u30F3\u30C8\u30C6\u30FC\u30D6\u30EB"),
        Map.entry("block.minecraft.anvil", "\u91D1\u5E8A"),
        // Items
        Map.entry("item.minecraft.torch", "\u677E\u660E"),
        Map.entry("item.minecraft.stick", "\u68D2"),
        Map.entry("item.minecraft.wooden_pickaxe", "\u6728\u306E\u30C4\u30EB\u30CF\u30B7"),
        Map.entry("item.minecraft.stone_pickaxe", "\u77F3\u306E\u30C4\u30EB\u30CF\u30B7"),
        Map.entry("item.minecraft.iron_pickaxe", "\u9244\u306E\u30C4\u30EB\u30CF\u30B7"),
        Map.entry("item.minecraft.diamond_pickaxe", "\u30C0\u30A4\u30E4\u30E2\u30F3\u30C9\u306E\u30C4\u30EB\u30CF\u30B7"),
        Map.entry("item.minecraft.netherite_pickaxe", "\u30CD\u30B6\u30E9\u30A4\u30C8\u306E\u30C4\u30EB\u30CF\u30B7"),
        Map.entry("item.minecraft.iron_sword", "\u9244\u306E\u5263"),
        Map.entry("item.minecraft.diamond_sword", "\u30C0\u30A4\u30E4\u30E2\u30F3\u30C9\u306E\u5263"),
        Map.entry("item.minecraft.iron_axe", "\u9244\u306E\u65A7"),
        Map.entry("item.minecraft.diamond_axe", "\u30C0\u30A4\u30E4\u30E2\u30F3\u30C9\u306E\u65A7"),
        Map.entry("item.minecraft.iron_shovel", "\u9244\u306E\u30B7\u30E3\u30D9\u30EB"),
        Map.entry("item.minecraft.diamond_shovel", "\u30C0\u30A4\u30E4\u30E2\u30F3\u30C9\u306E\u30B7\u30E3\u30D9\u30EB"),
        Map.entry("item.minecraft.shield", "\u76FE"),
        Map.entry("item.minecraft.bow", "\u5F13"),
        Map.entry("item.minecraft.arrow", "\u77E2"),
        Map.entry("item.minecraft.bread", "\u30D1\u30F3"),
        Map.entry("item.minecraft.golden_apple", "\u91D1\u306E\u30EA\u30F3\u30B4"),
        Map.entry("item.minecraft.rail", "\u30EC\u30FC\u30EB"),
        Map.entry("item.minecraft.powered_rail", "\u30D1\u30EF\u30FC\u30C9\u30EC\u30FC\u30EB"),
        Map.entry("item.minecraft.bucket", "\u30D0\u30B1\u30C4"),
        Map.entry("item.minecraft.compass", "\u30B3\u30F3\u30D1\u30B9"),
        Map.entry("item.minecraft.clock", "\u6642\u8A08")
    );

    private TranslationUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Renders a Component to plain text using Japanese locale via GlobalTranslator.
     *
     * Why GlobalTranslator.render: Paper registers Minecraft's translation bundles
     * into the Adventure GlobalTranslator at startup. By calling render() with
     * Locale.JAPANESE, translatable components resolve to their ja_jp translations.
     *
     * @param component the component to render (may be null)
     * @return the plain text representation in Japanese, or English as fallback
     */
    public static String renderJapanese(Component component) {
        if (component == null) {
            return "";
        }

        // Render with Japanese locale
        Component rendered = GlobalTranslator.render(component, JAPANESE);
        String text = PlainTextComponentSerializer.plainText().serialize(rendered);

        // If the result is still a translation key (untranslated), try fallback chain
        if (isTranslationKey(component, text)) {
            // Why: check the local HashMap before falling back to English,
            // because Paper's GlobalTranslator may not have ja_jp loaded
            if (component instanceof TranslatableComponent translatable) {
                String mapResult = JAPANESE_TRANSLATIONS.get(translatable.key());
                if (mapResult != null) {
                    return mapResult;
                }
            }

            Component englishRendered = GlobalTranslator.render(component, ENGLISH);
            String englishText = PlainTextComponentSerializer.plainText().serialize(englishRendered);

            // If English also fails, return the key formatted nicely
            if (isTranslationKey(component, englishText)) {
                return formatKeyAsReadable(englishText);
            }
            return englishText;
        }

        return text;
    }

    /**
     * Checks whether the serialized text is likely an untranslated translation key.
     *
     * Why: When GlobalTranslator does not have a translation for the given locale,
     * it returns the component unchanged. Serializing an untranslated TranslatableComponent
     * yields the raw key string (e.g., "advancements.story.mine_stone.title").
     *
     * @param original the original component before rendering
     * @param text     the serialized plain text after rendering
     * @return true if the text appears to be an unresolved translation key
     */
    private static boolean isTranslationKey(Component original, String text) {
        if (!(original instanceof TranslatableComponent translatable)) {
            return false;
        }
        // If the serialized text equals the translation key, it was not translated
        return text.equals(translatable.key());
    }

    /**
     * Formats a raw translation key into a more human-readable form.
     *
     * Why: If both Japanese and English translations fail, showing the raw key
     * (e.g., "advancements.story.mine_stone.title") is unfriendly. This method
     * extracts the meaningful part and formats it with title case.
     *
     * @param key the raw translation key
     * @return a formatted, readable version of the key
     */
    private static String formatKeyAsReadable(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }

        // Extract the last meaningful segment (e.g., "mine_stone" from
        // "advancements.story.mine_stone.title")
        String[] parts = key.split("\\.");
        // Skip common suffixes like "title", "description"
        String meaningful = parts.length >= 2
                ? parts[parts.length - 2]
                : parts[parts.length - 1];

        // Convert underscore_case to Title Case
        String[] words = meaningful.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            if (!words[i].isEmpty()) {
                sb.append(Character.toUpperCase(words[i].charAt(0)));
                if (words[i].length() > 1) {
                    sb.append(words[i].substring(1));
                }
            }
        }
        return sb.toString();
    }
}
