package com.geyserextra.core.api;

import java.util.Objects;

/**
 * Immutable record representing a custom item mapping configuration.
 *
 * This record defines how a Java Edition item with CustomModelData
 * or a stable PersistentDataContainer identifier should be mapped to
 * a Bedrock Edition custom item.
 *
 * @param name            Unique identifier for this custom item mapping
 * @param baseItem        The base Java Edition item identifier (e.g., "minecraft:diamond_sword")
 * @param customModelData The CustomModelData predicate value (0 when identifying via PDC only)
 * @param unbreakable     Whether the item should be unbreakable
 * @param displayName     Optional display name for the item (can be null)
 * @param iconPath        Optional path to the icon texture (can be null)
 * @param creativeCategory Creative inventory category (1-5) for recipe book visibility, 0 = none
 * @param creativeGroup   Optional creative group for sub-categorization (can be null)
 * @param register        Whether to register this item with Geyser (false = skip, prevents transparent items)
 * @param pdcIdentifier   Optional stable PersistentDataContainer identifier
 *                        (e.g., {@code "oraxen:fire_sword"}). When non-null, the item is
 *                        identified by PDC rather than (or in addition to) CMD.
 *                        Null for legacy CMD-only mappings.
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
    boolean register,
    String pdcIdentifier,
    ArmorData armor
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
     * - register: false
     * - pdcIdentifier: null (CMD-only mapping)
     *
     * @param name           Unique identifier for this custom item mapping
     * @param baseItem       The base Java Edition item identifier
     * @param customModelData The CustomModelData predicate value
     */
    public CustomItemMapping(String name, String baseItem, int customModelData) {
        this(name, baseItem, customModelData, false, null, null, CREATIVE_CATEGORY_NONE, null, false, null, null);
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
        this(name, baseItem, customModelData, false, null, null, creativeCategory, null, false, null, null);
    }

    /**
     * Backward-compatible 9-argument constructor for legacy CMD-only mappings.
     *
     * <p>Delegates to the canonical 10-argument constructor with
     * {@code pdcIdentifier=null}. Preserves the pre-existing call shape used by
     * {@code ItemMappingRegistry}, {@code GeyserExtraPaper}, and
     * {@code CustomItemScanner} so the addition of the {@code pdcIdentifier}
     * field does not require updates at every construction site.</p>
     */
    public CustomItemMapping(
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
        this(name, baseItem, customModelData, unbreakable, displayName, iconPath,
             creativeCategory, creativeGroup, register, null, null);
    }

    /**
     * Backward-compatible 10-arg constructor (pre-Phase-7a shape). Delegates
     * to the canonical 11-arg constructor with {@code armor=null} so call
     * sites that don't yet carry equippable data keep working.
     */
    public CustomItemMapping(
        String name,
        String baseItem,
        int customModelData,
        boolean unbreakable,
        String displayName,
        String iconPath,
        int creativeCategory,
        String creativeGroup,
        boolean register,
        String pdcIdentifier
    ) {
        this(name, baseItem, customModelData, unbreakable, displayName, iconPath,
             creativeCategory, creativeGroup, register, pdcIdentifier, null);
    }

    /**
     * Creates a new CustomItemMapping with the specified display name.
     *
     * @param newDisplayName The new display name
     * @return A new CustomItemMapping instance with the updated display name
     */
    public CustomItemMapping withDisplayName(String newDisplayName) {
        return new CustomItemMapping(name, baseItem, customModelData, unbreakable, newDisplayName, iconPath, creativeCategory, creativeGroup, register, pdcIdentifier, armor);
    }

    /**
     * Creates a new CustomItemMapping with the specified icon path.
     *
     * @param newIconPath The new icon path
     * @return A new CustomItemMapping instance with the updated icon path
     */
    public CustomItemMapping withIconPath(String newIconPath) {
        return new CustomItemMapping(name, baseItem, customModelData, unbreakable, displayName, newIconPath, creativeCategory, creativeGroup, register, pdcIdentifier, armor);
    }

    /**
     * Creates a new CustomItemMapping with the unbreakable flag set.
     *
     * @param newUnbreakable Whether the item should be unbreakable
     * @return A new CustomItemMapping instance with the updated unbreakable flag
     */
    public CustomItemMapping withUnbreakable(boolean newUnbreakable) {
        return new CustomItemMapping(name, baseItem, customModelData, newUnbreakable, displayName, iconPath, creativeCategory, creativeGroup, register, pdcIdentifier, armor);
    }

    /**
     * Creates a new CustomItemMapping with the specified creative category.
     * Setting this enables the item to appear in the Bedrock recipe book.
     *
     * @param newCreativeCategory Creative category (1-5), use 0 to disable
     * @return A new CustomItemMapping instance with the updated creative category
     */
    public CustomItemMapping withCreativeCategory(int newCreativeCategory) {
        return new CustomItemMapping(name, baseItem, customModelData, unbreakable, displayName, iconPath, newCreativeCategory, creativeGroup, register, pdcIdentifier, armor);
    }

    /**
     * Creates a new CustomItemMapping with the specified creative group.
     *
     * @param newCreativeGroup The creative group name for sub-categorization
     * @return A new CustomItemMapping instance with the updated creative group
     */
    public CustomItemMapping withCreativeGroup(String newCreativeGroup) {
        return new CustomItemMapping(name, baseItem, customModelData, unbreakable, displayName, iconPath, creativeCategory, newCreativeGroup, register, pdcIdentifier, armor);
    }

    /**
     * Creates a new CustomItemMapping with the specified PDC identifier.
     *
     * @param newPdcIdentifier The stable PersistentDataContainer identifier, or null to clear
     * @return A new CustomItemMapping instance with the updated PDC identifier
     */
    public CustomItemMapping withPdcIdentifier(String newPdcIdentifier) {
        return new CustomItemMapping(name, baseItem, customModelData, unbreakable, displayName, iconPath, creativeCategory, creativeGroup, register, newPdcIdentifier, armor);
    }

    /**
     * Creates a new CustomItemMapping with the specified armor data.
     *
     * @param newArmor The {@link ArmorData} describing the equippable slot, or
     *                 {@code null} to clear armor metadata
     * @return A new CustomItemMapping instance with the updated armor data
     */
    public CustomItemMapping withArmor(ArmorData newArmor) {
        return new CustomItemMapping(name, baseItem, customModelData, unbreakable, displayName, iconPath, creativeCategory, creativeGroup, register, pdcIdentifier, newArmor);
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

    /**
     * Checks if this mapping is identified by a PersistentDataContainer key
     * rather than (or in addition to) CustomModelData.
     *
     * @return true if pdcIdentifier is not null and not blank
     */
    public boolean hasPdcIdentifier() {
        return pdcIdentifier != null && !pdcIdentifier.isBlank();
    }

    /**
     * Checks if this mapping carries armor metadata (Phase 7a).
     *
     * @return true if armor is non-null
     */
    public boolean hasArmor() {
        return armor != null;
    }
}
