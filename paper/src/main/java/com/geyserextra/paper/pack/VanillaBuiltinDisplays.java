package com.geyserextra.paper.pack;

/**
 * Mojang client-jar {@code display} defaults for the vanilla item models that
 * operator resource packs inherit from but do not ship.
 *
 * <p>Operator packs rarely copy these parents, so
 * {@link JavaPackReader#resolveDisplayFromModel(String)} cannot walk them on
 * disk. Without injecting them, a pack entry that only declares
 * {@code "parent": "item/handheld"} resolves to {@code display == null},
 * skips attachable generation, and still registers as {@code geyserextra:*} —
 * Bedrock then loses the hold pose entirely, because a Geyser custom id does
 * not inherit Bedrock's own vanilla attachable the way a Java model falls back
 * to the client jar.</p>
 *
 * <p>Values are transcribed verbatim from Minecraft 1.21.11
 * ({@code assets/minecraft/models/item/*.json}). Only the slots each file
 * actually declares are filled in; the rest are left {@code null} so
 * {@link #nextBuiltinParent(String)} can continue the real vanilla parent
 * chain and Mojang's per-slot merge order is reproduced exactly.</p>
 */
public final class VanillaBuiltinDisplays {

    /**
     * {@code item/handheld} display.
     *
     * <p>The left-hand slots are <b>declared, not omitted</b>, and that
     * distinction decides how the item sits in the off hand: Mojang's
     * {@code ItemTransforms.Deserializer} substitutes the right-hand transform
     * only when a {@code *_lefthand} entry is absent, and
     * {@code ItemTransform#apply(leftHand, …)} negates rotation Y/Z either
     * way. So an omitted slot renders mirrored, while handheld's declared
     * {@code [0, 90, -55]} negates back to {@code [0, -90, 55]} — identical to
     * the right hand. Leaving these null therefore turned every tool inherited
     * from {@code item/handheld} 180° about Y in the off hand, blade pointing
     * backwards, for the 84 pack entries that reach this constant.</p>
     */
    public static final JavaModelDisplay HANDHELD = new JavaModelDisplay(
        transform(0f, -90f, 25f, 1.13f, 3.2f, 1.13f, 0.68f),
        transform(0f, -90f, 55f, 0f, 4f, 0.5f, 0.85f),
        null,
        null,
        null,
        transform(0f, 90f, -25f, 1.13f, 3.2f, 1.13f, 0.68f),
        transform(0f, 90f, -55f, 0f, 4f, 0.5f, 0.85f));

    /**
     * {@code item/generated} display. Supplies ground/head and the flatter
     * third-person hold used by non-tool 2D items; handheld overrides the
     * hand slots when both are merged.
     *
     * <p>Left-hand slots stay null on purpose: {@code generated.json} genuinely
     * omits them, so Mojang mirrors the right hand and so must this. Its
     * {@code fixed} slot is dropped because Bedrock renders item frames from the
     * icon sprite, where an attachable cannot reach.</p>
     */
    public static final JavaModelDisplay GENERATED = new JavaModelDisplay(
        transform(0f, -90f, 25f, 1.13f, 3.2f, 1.13f, 0.68f),
        transform(0f, 0f, 0f, 0f, 3f, 1f, 0.55f),
        null,
        transform(0f, 0f, 0f, 0f, 2f, 0f, 0.5f),
        transform(0f, 180f, 0f, 0f, 13f, 7f, 1f));

    /**
     * {@code item/handheld_rod} — fishing rod / carrot on a stick. Same family
     * as handheld but held tip-up rather than tip-forward, so the Y sign is
     * inverted relative to {@link #HANDHELD}.
     */
    public static final JavaModelDisplay HANDHELD_ROD = new JavaModelDisplay(
        transform(0f, 90f, 25f, 0f, 1.6f, 0.8f, 0.68f),
        transform(0f, 90f, 55f, 0f, 4f, 2.5f, 0.85f),
        null,
        null,
        null,
        transform(0f, -90f, -25f, 0f, 1.6f, 0.8f, 0.68f),
        transform(0f, -90f, -55f, 0f, 4f, 2.5f, 0.85f));

    /** {@code item/handheld_mace} — handheld held at full scale, pushed forward. */
    public static final JavaModelDisplay HANDHELD_MACE = new JavaModelDisplay(
        transform(0f, -90f, 25f, 0f, 3f, 0.8f, 0.9f),
        transform(0f, -90f, 55f, 0f, 4f, 1f, 1f),
        null,
        null,
        null,
        transform(0f, 90f, -25f, 0f, 3f, 0.8f, 0.9f),
        transform(0f, 90f, -55f, 0f, 4f, 1f, 1f));

    /**
     * {@code item/bow}. The third-person left hand is <b>not</b> a sign flip of
     * the right ({@code y = -280} against {@code +260}); after Mojang's Y/Z
     * negation the off hand still sits 20° apart from the main hand, which is
     * exactly why the pose has to be carried rather than mirrored.
     */
    public static final JavaModelDisplay BOW = new JavaModelDisplay(
        transform(0f, -90f, 25f, 1.13f, 3.2f, 1.13f, 0.68f),
        transform(-80f, 260f, -40f, -1f, -2f, 2.5f, 0.9f),
        null,
        null,
        null,
        transform(0f, 90f, -25f, 1.13f, 3.2f, 1.13f, 0.68f),
        transform(-80f, -280f, 40f, -1f, -2f, 2.5f, 0.9f));

    /** {@code item/crossbow} (also the {@code crossbow_pulling_*} chain). */
    public static final JavaModelDisplay CROSSBOW = new JavaModelDisplay(
        transform(-90f, 0f, -55f, 1.13f, 3.2f, 1.13f, 0.68f),
        transform(-90f, 0f, -60f, 2f, 0.1f, -3f, 0.9f),
        null,
        null,
        null,
        transform(-90f, 0f, 35f, 1.13f, 3.2f, 1.13f, 0.68f),
        transform(-90f, 0f, 30f, 2f, 0.1f, -3f, 0.9f));

    /**
     * {@code item/spear_in_hand} (1.21.11). The only builtin here with a
     * non-uniform scale — the blade is stretched 2:1 in X/Y against Z, so a
     * scalar scale would render every spear stubby.
     */
    public static final JavaModelDisplay SPEAR_IN_HAND = new JavaModelDisplay(
        transform(-20f, 90f, -35f, 3.13f, 2f, 0.13f, 1.36f, 1.36f, 0.68f),
        transform(5f, 270f, -40f, 0f, 2f, 2f, 1.7f, 1.7f, 0.85f),
        null,
        null,
        null,
        transform(-20f, -90f, 35f, 3.13f, 2f, 0.13f, 1.36f, 1.36f, 0.68f),
        transform(5f, -270f, 40f, 0f, 2f, 2f, 1.7f, 1.7f, 0.85f));

    /**
     * {@code item/trident_in_hand}. Has no parent in the client jar, so it
     * declares its own ground/gui and inherits nothing — hence
     * {@link #nextBuiltinParent(String)} ends the chain here.
     */
    public static final JavaModelDisplay TRIDENT_IN_HAND = new JavaModelDisplay(
        transform(0f, -90f, 25f, -3f, 17f, 1f, 1f),
        transform(0f, 60f, 0f, 11f, 17f, -2f, 1f),
        transform(15f, -25f, -5f, 2f, 3f, 0f, 0.65f),
        transform(0f, 0f, 0f, 4f, 4f, 2f, 0.25f),
        null,
        transform(0f, 90f, -25f, 13f, 17f, 1f, 1f),
        transform(0f, 60f, 0f, 3f, 17f, 12f, 1f));

    /** {@code item/shield}. Parentless like {@code trident_in_hand}. */
    public static final JavaModelDisplay SHIELD = new JavaModelDisplay(
        transform(0f, 180f, 5f, -10f, 2f, -10f, 1.25f),
        transform(0f, 90f, 0f, 10f, 6f, -4f, 1f),
        transform(15f, -25f, -5f, 2f, 3f, 0f, 0.65f),
        transform(0f, 0f, 0f, 2f, 4f, 2f, 0.25f),
        null,
        transform(0f, 180f, 5f, 10f, 0f, -10f, 1.25f),
        transform(0f, 90f, 0f, 10f, 6f, 12f, 1f));

    private VanillaBuiltinDisplays() {}

    /**
     * One rung of the vanilla parent chain: the slots that model declares and
     * the parent to continue walking. Keeping both in one lookup is what stops
     * {@link #forUnresolvedParent(String)} and {@link #nextBuiltinParent(String)}
     * from drifting apart — a mismatch there silently truncates the merge and
     * leaves ground/head unset.
     */
    private record Builtin(JavaModelDisplay display, String nextParent) {}

    private static final String ITEM_PREFIX = "item/";
    private static final Builtin TO_GENERATED_ONLY =
        new Builtin(GENERATED, null);

    /**
     * Returns the builtin display for an unresolved parent ref, or
     * {@code null} when the ref is not a vanilla item parent at all.
     */
    public static JavaModelDisplay forUnresolvedParent(String parentRef) {
        Builtin builtin = classify(normalizeParentKey(parentRef));
        return builtin == null ? null : builtin.display();
    }

    /**
     * Next parent to continue walking after injecting a builtin, or
     * {@code null} when the vanilla model has no parent left to contribute.
     */
    public static String nextBuiltinParent(String parentRef) {
        Builtin builtin = classify(normalizeParentKey(parentRef));
        return builtin == null ? null : builtin.nextParent();
    }

    /**
     * Maps a vanilla {@code item/*} model name onto the client-jar display it
     * would resolve to.
     *
     * <p>The classification is by <em>model family</em>, not by item: every
     * {@code *_sword}/{@code *_axe}/{@code *_pickaxe}/{@code *_shovel}/{@code *_hoe}
     * in the client jar inherits {@code item/handheld}, every
     * {@code bow_pulling_*} inherits {@code item/bow}, and so on. Anything not
     * matched falls through to {@code item/generated}, which is what the vast
     * majority of vanilla item models actually inherit — armour, ingots,
     * gems, shards, tridents and spears (their held pose lives in the separate
     * {@code *_in_hand} model) all land there.</p>
     *
     * <p>The previous revision returned {@link #HANDHELD} for every unmatched
     * {@code item/*} name. That is the wrong default and it was not a small
     * error: it put bows, tridents, maces and full armour sets into a sword
     * grip, 48 entries in the reference pack alone.</p>
     */
    private static Builtin classify(String key) {
        if (key == null || !key.startsWith(ITEM_PREFIX)) {
            return null;
        }
        String name = key.substring(ITEM_PREFIX.length());
        Builtin exact = classifyExact(name);
        if (exact != null) {
            return exact;
        }
        if (name.startsWith("bow_pulling_")) {
            return new Builtin(BOW, "item/generated");
        }
        if (name.startsWith("crossbow_")) {
            return new Builtin(CROSSBOW, "item/generated");
        }
        if (name.endsWith("_spear_in_hand")) {
            return new Builtin(SPEAR_IN_HAND, "item/generated");
        }
        if (isHandheldTool(name)) {
            return new Builtin(HANDHELD, "item/generated");
        }
        return TO_GENERATED_ONLY;
    }

    private static Builtin classifyExact(String name) {
        return switch (name) {
            case "generated" -> TO_GENERATED_ONLY;
            case "handheld" -> new Builtin(HANDHELD, "item/generated");
            case "handheld_rod", "fishing_rod", "carrot_on_a_stick",
                 "warped_fungus_on_a_stick" ->
                new Builtin(HANDHELD_ROD, "item/handheld");
            case "handheld_mace", "mace" ->
                new Builtin(HANDHELD_MACE, "item/handheld");
            case "bow" -> new Builtin(BOW, "item/generated");
            case "crossbow" -> new Builtin(CROSSBOW, "item/generated");
            case "spear_in_hand" -> new Builtin(SPEAR_IN_HAND, "item/generated");
            case "trident_in_hand" -> new Builtin(TRIDENT_IN_HAND, null);
            case "shield" -> new Builtin(SHIELD, null);
            // Rod-shaped items that are handheld without carrying a tool
            // suffix. Verified against 1.21.11: these five are the whole set
            // outside the tool families.
            case "stick", "debug_stick", "blaze_rod", "breeze_rod", "bone",
                 "bamboo" ->
                new Builtin(HANDHELD, "item/generated");
            default -> null;
        };
    }

    /**
     * True for the five tool suffixes that inherit {@code item/handheld} across
     * every material tier in the client jar. Matching on the suffix rather than
     * enumerating tiers keeps modded and future materials
     * ({@code copper_sword}, added in 1.21.9) classified correctly.
     */
    private static boolean isHandheldTool(String name) {
        return name.endsWith("_sword")
            || name.endsWith("_axe")
            || name.endsWith("_pickaxe")
            || name.endsWith("_shovel")
            || name.endsWith("_hoe");
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
        return transform(rx, ry, rz, tx, ty, tz, scale, scale, scale);
    }

    private static JavaModelDisplay.Transform transform(
        float rx, float ry, float rz,
        float tx, float ty, float tz,
        float sx, float sy, float sz
    ) {
        return new JavaModelDisplay.Transform(
            new float[]{rx, ry, rz},
            new float[]{tx, ty, tz},
            new float[]{sx, sy, sz});
    }
}
