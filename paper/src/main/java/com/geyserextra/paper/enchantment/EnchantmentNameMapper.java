package com.geyserextra.paper.enchantment;

import org.bukkit.enchantments.Enchantment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Utility class that maps Minecraft 1.21.x vanilla enchantments to Japanese display names.
 *
 * <p>This mapper provides:
 * <ul>
 *   <li>Japanese name lookup for all vanilla enchantments</li>
 *   <li>Roman numeral conversion for enchantment levels</li>
 *   <li>Formatted enchantment display strings (e.g., "鋭さ V")</li>
 *   <li>Curse detection for enchantments</li>
 * </ul>
 *
 * <p>All data is immutable. This is a utility class and cannot be instantiated.
 */
public final class EnchantmentNameMapper {

    private static final Map<String, String> ENCHANTMENT_NAMES;
    private static final int MAX_ROMAN_NUMERAL = 10;

    private static final String[] ROMAN_NUMERALS = {
        "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"
    };

    static {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("minecraft:aqua_affinity", "水中採掘");
        names.put("minecraft:bane_of_arthropods", "虫殺し");
        names.put("minecraft:binding_curse", "束縛の呪い");
        names.put("minecraft:blast_protection", "爆発耐性");
        names.put("minecraft:breach", "貫通(防具)");
        names.put("minecraft:channeling", "召雷");
        names.put("minecraft:density", "重撃");
        names.put("minecraft:depth_strider", "水中歩行");
        names.put("minecraft:efficiency", "効率");
        names.put("minecraft:feather_falling", "落下耐性");
        names.put("minecraft:fire_aspect", "火属性");
        names.put("minecraft:fire_protection", "火炎耐性");
        names.put("minecraft:flame", "フレイム");
        names.put("minecraft:fortune", "幸運");
        names.put("minecraft:frost_walker", "氷渡り");
        names.put("minecraft:impaling", "水生特効");
        names.put("minecraft:infinity", "無限");
        names.put("minecraft:knockback", "ノックバック");
        names.put("minecraft:looting", "ドロップ増加");
        names.put("minecraft:loyalty", "忠誠");
        names.put("minecraft:luck_of_the_sea", "宝釣り");
        names.put("minecraft:lure", "入れ食い");
        names.put("minecraft:mending", "修繕");
        names.put("minecraft:multishot", "拡散");
        names.put("minecraft:piercing", "貫通");
        names.put("minecraft:power", "射撃ダメージ増加");
        names.put("minecraft:projectile_protection", "飛び道具耐性");
        names.put("minecraft:protection", "ダメージ軽減");
        names.put("minecraft:punch", "衝撃");
        names.put("minecraft:quick_charge", "高速装填");
        names.put("minecraft:respiration", "水中呼吸");
        names.put("minecraft:riptide", "激流");
        names.put("minecraft:sharpness", "鋭さ");
        names.put("minecraft:silk_touch", "シルクタッチ");
        names.put("minecraft:smite", "アンデッド特効");
        names.put("minecraft:soul_speed", "ソウルスピード");
        names.put("minecraft:sweeping_edge", "範囲ダメージ増加");
        names.put("minecraft:swift_sneak", "スニーク速度上昇");
        names.put("minecraft:thorns", "棘の鎧");
        names.put("minecraft:unbreaking", "耐久力");
        names.put("minecraft:vanishing_curse", "消滅の呪い");
        names.put("minecraft:wind_burst", "ウィンドバースト");
        ENCHANTMENT_NAMES = Collections.unmodifiableMap(names);
    }

    private EnchantmentNameMapper() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Returns an unmodifiable map of all vanilla enchantment keys to Japanese names.
     *
     * @return immutable map of enchantment key to Japanese display name
     */
    public static Map<String, String> getAllNames() {
        return ENCHANTMENT_NAMES;
    }

    /**
     * Returns the Japanese display name for the given enchantment.
     *
     * <p>For vanilla enchantments, returns the mapped Japanese name.
     * For custom (non-vanilla) enchantments, formats the key into a readable name
     * by removing the namespace, replacing underscores with spaces, and capitalizing words.
     *
     * @param enchantment the enchantment to look up
     * @return the Japanese display name, or formatted key for custom enchantments
     * @throws NullPointerException if enchantment is null
     */
    public static String getDisplayName(Enchantment enchantment) {
        Objects.requireNonNull(enchantment, "enchantment must not be null");

        String key = enchantment.getKey().toString();
        String japaneseName = ENCHANTMENT_NAMES.get(key);
        if (japaneseName != null) {
            return japaneseName;
        }

        // Custom enchantment: format the key for display
        return formatCustomEnchantmentKey(key);
    }

    /**
     * Converts an integer level to a Roman numeral string.
     *
     * <p>Levels 1-10 are converted to Roman numerals (I-X).
     * Levels 11 and above are returned as plain decimal strings.
     *
     * @param level the enchantment level (must be positive)
     * @return the Roman numeral or decimal string representation
     * @throws IllegalArgumentException if level is not positive
     */
    public static String toRomanNumeral(int level) {
        if (level <= 0) {
            throw new IllegalArgumentException("Level must be positive, got: " + level);
        }

        if (level <= MAX_ROMAN_NUMERAL) {
            return ROMAN_NUMERALS[level];
        }

        return String.valueOf(level);
    }

    /**
     * Formats an enchantment with its level as a display string.
     *
     * <p>Returns strings like "鋭さ V" or "効率 III".
     * For level 1 enchantments with max level 1 (e.g., Silk Touch), only the name is returned.
     *
     * @param enchantment the enchantment to format
     * @param level       the enchantment level (must be positive)
     * @return the formatted enchantment string
     * @throws NullPointerException     if enchantment is null
     * @throws IllegalArgumentException if level is not positive
     */
    public static String formatEnchantment(Enchantment enchantment, int level) {
        Objects.requireNonNull(enchantment, "enchantment must not be null");
        if (level <= 0) {
            throw new IllegalArgumentException("Level must be positive, got: " + level);
        }

        String displayName = getDisplayName(enchantment);

        // For max-level-1 enchantments at level 1, omit the numeral
        if (level == 1 && enchantment.getMaxLevel() == 1) {
            return displayName;
        }

        return displayName + " " + toRomanNumeral(level);
    }

    /**
     * Determines whether the given enchantment is a curse.
     *
     * <p>Checks if the enchantment key contains "curse" as a substring.
     *
     * @param enchantment the enchantment to check
     * @return true if the enchantment is a curse, false otherwise
     * @throws NullPointerException if enchantment is null
     */
    public static boolean isCurse(Enchantment enchantment) {
        Objects.requireNonNull(enchantment, "enchantment must not be null");

        return enchantment.getKey().getKey().contains("curse");
    }

    /**
     * Formats a custom enchantment key into a human-readable name.
     *
     * <p>Removes the namespace prefix (e.g., "myplugin:"), replaces underscores
     * with spaces, and capitalizes the first letter of each word.
     *
     * @param key the full enchantment key (e.g., "myplugin:fire_slash")
     * @return the formatted display name (e.g., "Fire Slash")
     */
    private static String formatCustomEnchantmentKey(String key) {
        if (key == null || key.isEmpty()) {
            return "Unknown Enchantment";
        }

        // Remove namespace prefix
        String rawName = key;
        int colonIndex = key.indexOf(':');
        if (colonIndex >= 0 && colonIndex < key.length() - 1) {
            rawName = key.substring(colonIndex + 1);
        } else if (colonIndex == key.length() - 1) {
            // Key ends with colon (e.g., "namespace:")
            return "Unknown Enchantment";
        }

        if (rawName.isEmpty()) {
            return "Unknown Enchantment";
        }

        // Replace underscores with spaces and capitalize each word
        String[] words = rawName.split("_");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(' ');
            }
            String word = words[i];
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1).toLowerCase());
                }
            }
        }
        return result.isEmpty() ? "Unknown Enchantment" : result.toString();
    }
}
