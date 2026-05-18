package com.geyserextra.core.api;

import java.util.Objects;

/**
 * Immutable armor metadata extracted from a Java item's
 * {@code minecraft:equippable} data component (Paper 1.21.4+).
 *
 * <p>Phase 7a uses this to drive a Bedrock armor attachable: when a
 * {@link CustomItemMapping} carries non-null {@code armor}, the auto-pack
 * generates a Bedrock attachable that references
 * {@code geometry.humanoid.armor.<slot>} and applies the operator's custom
 * equipment texture so Bedrock players see the same armor as Java players
 * when the item is worn.</p>
 *
 * @param slot      one of {@code "head"}, {@code "chest"}, {@code "legs"},
 *                  {@code "feet"} (lowercased). Determines which Bedrock
 *                  humanoid armor geometry the attachable references.
 * @param assetId   the Java {@code equippable.asset_id} pointing at the
 *                  equipment JSON (e.g. {@code "myns:my_helmet"}). The
 *                  Java-pack reader resolves this to a texture file.
 */
public record ArmorData(String slot, String assetId) {

    /** "head" — helmet slot. */
    public static final String SLOT_HEAD = "head";
    /** "chest" — chestplate slot. */
    public static final String SLOT_CHEST = "chest";
    /** "legs" — leggings slot. */
    public static final String SLOT_LEGS = "legs";
    /** "feet" — boots slot. */
    public static final String SLOT_FEET = "feet";

    public ArmorData {
        Objects.requireNonNull(slot, "slot must not be null");
        Objects.requireNonNull(assetId, "assetId must not be null");
        if (slot.isBlank()) {
            throw new IllegalArgumentException("slot must not be blank");
        }
        if (assetId.isBlank()) {
            throw new IllegalArgumentException("assetId must not be blank");
        }
    }

    /**
     * Returns the Bedrock humanoid armor geometry identifier matching this
     * slot. Used by the attachable writer to populate
     * {@code description.geometry.default}.
     */
    public String bedrockGeometry() {
        return switch (slot) {
            case SLOT_HEAD -> "geometry.humanoid.armor.helmet";
            case SLOT_CHEST -> "geometry.humanoid.armor.chestplate";
            case SLOT_LEGS -> "geometry.humanoid.armor.leggings";
            case SLOT_FEET -> "geometry.humanoid.armor.boots";
            // Defensive default: head geometry is the least visually disruptive
            // fallback for unknown slot values, and the WARN log surfaces the
            // typo to the operator.
            default -> "geometry.humanoid.armor.helmet";
        };
    }

    /**
     * Returns the Java equipment-JSON layer key matching this slot. Used by
     * the pack reader to look up the right entry in the equipment JSON's
     * {@code layers} object.
     *
     * <p>Mojang distinguishes leggings from other armor pieces because the
     * leggings render with a thinner mesh that requires a separate texture
     * with shifted UV coordinates ({@code humanoid_leggings} layer).</p>
     */
    public String equipmentLayerKey() {
        return SLOT_LEGS.equals(slot) ? "humanoid_leggings" : "humanoid";
    }
}
