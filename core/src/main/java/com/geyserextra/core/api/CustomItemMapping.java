package com.geyserextra.core.api;

import java.util.Objects;

/**
 * Immutable record representing a custom item mapping configuration.
 *
 * This record defines how a Java Edition item with CustomModelData
 * should be mapped to a Bedrock Edition custom item.
 *
 * @param name            Unique identifier for this custom item mapping
 * @param baseItem        The base Java Edition item identifier (e.g., "minecraft:diamond_sword")
 * @param customModelData The CustomModelData predicate value
 * @param unbreakable     Whether the item should be unbreakable
 * @param displayName     Optional display name for the item (can be null)
 * @param iconPath        Optional path to the icon texture (can be null)
 * @param creativeCategory Creative inventory category (1-5) for recipe book visibility, 0 = none
 * @param creativeGroup   Optional creative group for sub-categorization (can be null)
 * @param register        Whether to register this item with Geyser (false = skip, prevents transparent items)
 */
public record CustomItemMapping(
    String name,
    String baseItem,
    int customModelData,
    boolean unbreakable,
    String displayName,
    String iconPath,
    int creativeCategory,
    String creativeGroup,
    boolean register
) {
    /**
     * Bedrock creative inventory categories.
     * These values determine where items appear in Bedrock's creative menu.
     */
    public static final int CREATIVE_CATEGORY_NONE = 0;
    public static final int CREATIVE_CATEGORY_CONSTRUCTION = 1;
    public static final int CREATIVE_CATEGORY_NATURE = 2;
    public static final int CREATIVE_CATEGORY_EQUIPMENT = 3;
    public static final int CREATIVE_CATEGORY_ITEMS = 4;
    public static final int CREATIVE_CATEGORY_COMMANDS = 5;

    /**
     * Compact constructor with validation.
     * Ensures name and baseItem are not null or blank.
     */
    public CustomItemMapping {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(baseItem, "baseItem must not be null");

        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (baseItem.isBlank()) {
            throw new IllegalArgumentException("baseItem must not be blank");
        }
        if (customModelData < 0) {
            throw new IllegalArgumentException("customModelData must be non-negative");
        }
        if (creativeCategory < 0 || creativeCategory > 5) {
            throw new IllegalArgumentException("creativeCategory must be between 0 and 5");
        }
    }

    /**
     * Simplified constructor for basic custom item mappings.
     * Creates a mapping with default values for optional fields:
     * - unbreakable: false
     * - displayName: null
     * - iconPath: null
     * - creativeCategory: 0 (none)
     * - creativeGroup: null
     *
     * @param name           Unique identifier for this custom item mapping
     * @param baseItem       The base Java Edition item identifier
     * @param customModelData The CustomModelData predicate value
     */
    public CustomItemMapping(String name, String baseItem, int customModelData) {
        this(name, baseItem, customModelData, false, null, null, CREATIVE_CATEGORY_NONE, null, false);
    }

    /**
     * Constructor with creative category for recipe book visibility.
     *
     * @param name            Unique identifier for this custom item mapping
     * @param baseItem        The base Java Edition item identifier
     * @param customModelData The CustomModelData predicate value
     * @param creativeCategory Creative category (1-5) for recipe book
     */
    public CustomItemMapping(String name, String baseItem, int customModelData, int creativeCategory) {
        this(name, baseItem, customModelData, false, null, null, creativeCategory, null, false);
    }

    /**
     * Creates a new CustomItemMapping with the specified display name.
     *
     * @param newDisplayName The new display name
     * @return A new CustomItemMapping instance with the updated display name
     */
    public CustomItemMapping withDisplayName(String newDisplayName) {
        return new CustomItemMapping(name, baseItem, customModelData, unbreakable, newDisplayName, iconPath, creativeCategory, creativeGroup, register);
    }

    /**
     * Creates a new CustomItemMapping with the specified icon path.
     *
     * @param newIconPath The new icon path
     * @return A new CustomItemMapping instance with the updated icon path
     */
    public CustomItemMapping withIconPath(String newIconPath) {
        return new CustomItemMapping(name, baseItem, customModelData, unbreakable, displayName, newIconPath, creativeCategory, creativeGroup, register);
    }

    /**
     * Creates a new CustomItemMapping with the unbreakable flag set.
     *
     * @param newUnbreakable Whether the item should be unbreakable
     * @return A new CustomItemMapping instance with the updated unbreakable flag
     */
    public CustomItemMapping withUnbreakable(boolean newUnbreakable) {
        return new CustomItemMapping(name, baseItem, customModelData, newUnbreakable, displayName, iconPath, creativeCategory, creativeGroup, register);
    }

    /**
     * Creates a new CustomItemMapping with the specified creative category.
     * Setting this enables the item to appear in the Bedrock recipe book.
     *
     * @param newCreativeCategory Creative category (1-5), use 0 to disable
     * @return A new CustomItemMapping instance with the updated creative category
     */
    public CustomItemMapping withCreativeCategory(int newCreativeCategory) {
        return new CustomItemMapping(name, baseItem, customModelData, unbreakable, displayName, iconPath, newCreativeCategory, creativeGroup, register);
    }

    /**
     * Creates a new CustomItemMapping with the specified creative group.
     *
     * @param newCreativeGroup The creative group name for sub-categorization
     * @return A new CustomItemMapping instance with the updated creative group
     */
    public CustomItemMapping withCreativeGroup(String newCreativeGroup) {
        return new CustomItemMapping(name, baseItem, customModelData, unbreakable, displayName, iconPath, creativeCategory, newCreativeGroup, register);
    }

    /**
     * Checks if this mapping has a custom display name.
     *
     * @return true if displayName is not null and not blank
     */
    public boolean hasDisplayName() {
        return displayName != null && !displayName.isBlank();
    }

    /**
     * Checks if this mapping has a custom icon path.
     *
     * @return true if iconPath is not null and not blank
     */
    public boolean hasIconPath() {
        return iconPath != null && !iconPath.isBlank();
    }

    /**
     * Checks if this mapping has a creative category set.
     *
     * @return true if creativeCategory is between 1-5
     */
    public boolean hasCreativeCategory() {
        return creativeCategory >= 1 && creativeCategory <= 5;
    }

    /**
     * Checks if this mapping has a creative group set.
     *
     * @return true if creativeGroup is not null and not blank
     */
    public boolean hasCreativeGroup() {
        return creativeGroup != null && !creativeGroup.isBlank();
    }
}
