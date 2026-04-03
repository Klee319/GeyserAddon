package com.geyserextra.paper.util;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * Utility for formatting enum names to human-readable strings.
 *
 * Why: Multiple commands (tooltip, statistics) need the same formatting logic.
 * Centralizing avoids duplication and ensures consistent display.
 */
public final class EnumFormatUtil {

    private EnumFormatUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Formats a Material enum name to a human-readable string.
     * Example: DIAMOND_SWORD -> Diamond Sword
     *
     * @param material the material to format
     * @return formatted name
     */
    public static String formatMaterial(Material material) {
        return formatEnumName(material.name());
    }

    /**
     * Formats an EntityType enum name to a human-readable string.
     * Example: ZOMBIE_VILLAGER -> Zombie Villager
     *
     * @param entityType the entity type to format
     * @return formatted name
     */
    public static String formatEntity(EntityType entityType) {
        return formatEnumName(entityType.name());
    }

    /**
     * Formats an underscore-separated enum name to title case.
     *
     * @param enumName the raw enum name (e.g., "DIAMOND_SWORD")
     * @return formatted name (e.g., "Diamond Sword")
     */
    public static String formatEnumName(String enumName) {
        String[] parts = enumName.toLowerCase().split("_");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                result.append(" ");
            }
            if (!parts[i].isEmpty()) {
                result.append(Character.toUpperCase(parts[i].charAt(0)))
                    .append(parts[i].substring(1));
            }
        }
        return result.toString();
    }
}
