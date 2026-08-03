package com.geyserextra.paper.pack;

/**
 * Mojang client-jar defaults for {@code item/handheld} and {@code item/generated}.
 *
 * <p>Operator resource packs rarely ship these parents, so
 * {@link JavaPackReader#resolveDisplayFromModel(String)} cannot walk them on
 * disk. Without injecting these constants, CMD items that only declare
 * {@code "parent": "item/handheld"} resolve to {@code display == null},
 * skip attachable generation, and still register as {@code geyserextra:*} —
 * Bedrock then loses the vanilla sword hold pose.</p>
 *
 * <p>Values match Minecraft 1.21.11 assets
 * ({@code assets/minecraft/models/item/handheld.json} /
 * {@code generated.json}).</p>
 */
public final class VanillaBuiltinDisplays {

    /** {@code item/handheld} display (right-hand slots only; writer mirrors left). */
    public static final JavaModelDisplay HANDHELD = new JavaModelDisplay(
        transform(0f, -90f, 25f, 1.13f, 3.2f, 1.13f, 0.68f),
        transform(0f, -90f, 55f, 0f, 4f, 0.5f, 0.85f),
        null,
        null,
        null);

    /**
     * {@code item/generated} display. Supplies ground/head and the flatter
     * third-person hold used by non-tool 2D items; handheld overrides the
     * hand slots when both are merged.
     */
    public static final JavaModelDisplay GENERATED = new JavaModelDisplay(
        transform(0f, -90f, 25f, 1.13f, 3.2f, 1.13f, 0.68f),
        transform(0f, 0f, 0f, 0f, 3f, 1f, 0.55f),
        null,
        transform(0f, 0f, 0f, 0f, 2f, 0f, 0.5f),
        transform(0f, 180f, 0f, 0f, 13f, 7f, 1f));

    private VanillaBuiltinDisplays() {}

    /**
     * Returns the builtin display for an unresolved parent ref, or
     * {@code null} when the ref is not a known vanilla item parent.
     */
    public static JavaModelDisplay forUnresolvedParent(String parentRef) {
        String key = normalizeParentKey(parentRef);
        if ("item/handheld".equals(key)) {
            return HANDHELD;
        }
        if ("item/generated".equals(key)) {
            return GENERATED;
        }
        // Any other unresolved vanilla item model — a pack entry that points
        // straight at a client-jar model such as
        // {@code minecraft:item/netherite_spear_in_hand}, which the pack does
        // not ship because Java reads it from the client. Without a fallback
        // the whole display resolves to null, no attachable is written, and
        // the item renders in hand as a flat 2D sprite: Geyser registers it
        // under a custom id, so Bedrock cannot fall back to its own vanilla
        // pose the way Java falls back to the client model.
        //
        // Handheld is the closest generic hold and is strictly better than no
        // attachable at all. The exact vanilla pose for these models is not
        // recoverable from the pack alone.
        if (key.startsWith("item/")) {
            return HANDHELD;
        }
        return null;
    }

    /**
     * Next parent to continue walking after injecting a builtin, or
     * {@code null} when the chain ends ({@code item/generated} →
     * {@code builtin/generated} has no further display we need).
     */
    public static String nextBuiltinParent(String parentRef) {
        String key = normalizeParentKey(parentRef);
        if ("item/generated".equals(key)) {
            return null;
        }
        // handheld and the generic vanilla-item fallback both chain on to
        // generated so ground/head slots still get filled in.
        if (key.startsWith("item/")) {
            return "item/generated";
        }
        return null;
    }

    /**
     * Strips {@code minecraft:} and a trailing {@code .json} so pack parents
     * like {@code minecraft:item/handheld} match the builtin table.
     */
    public static String normalizeParentKey(String parentRef) {
        if (parentRef == null || parentRef.isBlank()) {
            return "";
        }
        String key = parentRef.trim();
        int colon = key.indexOf(':');
        if (colon >= 0) {
            key = key.substring(colon + 1);
        }
        if (key.endsWith(".json")) {
            key = key.substring(0, key.length() - ".json".length());
        }
        return key;
    }

    private static JavaModelDisplay.Transform transform(
        float rx, float ry, float rz,
        float tx, float ty, float tz,
        float scale
    ) {
        return new JavaModelDisplay.Transform(
            new float[]{rx, ry, rz},
            new float[]{tx, ty, tz},
            new float[]{scale, scale, scale});
    }
}
