package com.geyserextra.paper.bedrock;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which items the smithing-table CMD stripper must <b>leave alone</b>, because the proxy is
 * already shipping the Bedrock client a smithing recipe that names them by their real identity.
 *
 * <h2>Why two mechanisms would cancel each other out</h2>
 * There are two independent ways to let a Bedrock player netherite-upgrade a custom item, and
 * <b>they are mutually exclusive on any single item</b>:
 * <ul>
 *   <li><b>CMD strip</b> ({@link com.geyserextra.paper.smithing.BedrockSmithingTableCmdStripper}):
 *       present the item to the client as its plain vanilla base. The client then accepts it in
 *       the base slot and matches the <em>vanilla</em> netherite recipe it already holds.
 *       <b>Only works when a vanilla recipe for that base material exists</b> — i.e. the diamond
 *       tools. A custom bow stripped to {@code minecraft:bow} is still refused, because
 *       {@code minecraft:bow} carries no {@code minecraft:transformable_items} tag.</li>
 *   <li><b>tag + injected recipe</b>: the extension tags every registered custom item with
 *       {@code minecraft:transformable_items} (so the base slot accepts it) and sends a
 *       {@code SmithingTransformRecipeData} naming that custom identifier. Works for
 *       <b>any</b> base material, and the result preview shows the real custom artwork.</li>
 * </ul>
 *
 * <p>The second path addresses the item by {@code geyser_custom:*}. If the stripper has already
 * removed the CMD, the client is holding plain {@code minecraft:bow} and <b>neither the tag nor
 * the injected recipe applies to it</b> — the strip silently disables the very mechanism that is
 * the only one able to help. That is why the two must not both act on the same item.
 *
 * <h2>Why the exemption set is derived from the shipped table</h2>
 * The set is filled from the <b>same merged recipe table</b> that the extension injects, so the
 * two sides cannot disagree:
 * <ul>
 *   <li>Table has no smithing entries (an older TrinityForge, or the guard rejected them all)
 *       → nothing is exempt → the stripper behaves exactly as it did before this class existed.
 *       <b>No regression is possible from a partial deploy.</b></li>
 *   <li>Table has them → those exact items stop being stripped, and the injected recipe takes
 *       over for them. Every other custom item is still stripped, so items that relied on the
 *       vanilla-recipe-after-strip behaviour (a CMD sword that is not a catalog source, for
 *       instance) keep working untouched.</li>
 * </ul>
 *
 * <p>Read on the packet thread, written by the collector's async poll, hence the volatile
 * snapshot swap rather than a mutable set.
 */
public final class SmithingBaseExemptions {

    private volatile Set<String> keys = Set.of();

    /** Replaces the exemption set. {@code null} is treated as "nothing exempt". */
    public void update(Set<String> newKeys) {
        this.keys = newKeys == null || newKeys.isEmpty() ? Set.of() : Set.copyOf(newKeys);
    }

    /** How many items are currently exempt. For logging only. */
    public int size() {
        return keys.size();
    }

    /**
     * Whether this stack is a base of a smithing recipe the proxy ships, and must therefore keep
     * its CustomModelData so the client can match that recipe.
     */
    public boolean isExempt(ItemStack item) {
        Set<String> snapshot = keys;
        if (snapshot.isEmpty() || item == null || item.getType() == Material.AIR) {
            return false;
        }
        Integer cmd = primaryCustomModelData(item);
        return cmd != null && snapshot.contains(key(item.getType().name(), cmd));
    }

    /**
     * The key both sides agree on: the material name as {@code Material#name} spells it, plus the
     * primary CustomModelData value.
     *
     * <p>Uppercased on the way in because the table is written from {@code Material#name()} on the
     * backend but nothing stops a future writer from lower-casing it.
     */
    public static String key(String materialName, int customModelData) {
        return materialName.toUpperCase(Locale.ROOT) + "#" + customModelData;
    }

    /**
     * The item's primary CustomModelData, or {@code null} when it has none.
     *
     * <p>Reads the same field {@code CustomItemScanner} uses to mint the Bedrock identifier — the
     * first {@code floats()} entry — so an item is exempt exactly when the recipe that names it
     * could actually address it.
     */
    private static Integer primaryCustomModelData(ItemStack item) {
        try {
            if (!item.hasData(DataComponentTypes.CUSTOM_MODEL_DATA)) {
                return null;
            }
            CustomModelData data = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
            if (data == null) {
                return null;
            }
            List<Float> floats = data.floats();
            if (floats.isEmpty()) {
                return null;
            }
            return floats.getFirst().intValue();
        } catch (RuntimeException e) {
            // Reading a component must never cost the player their inventory packet. Treating a
            // failure as "not exempt" keeps the previous behaviour (strip), which is the safe
            // direction: the worst case is the old symptom, not a new one.
            return null;
        }
    }

    /** Collects the exemption keys out of a merged table's recipe array. Never throws. */
    static Set<String> fromMergedTable(com.google.gson.JsonObject merged) {
        Set<String> found = new HashSet<>();
        if (merged == null || !merged.has("recipes") || !merged.get("recipes").isJsonArray()) {
            return found;
        }
        for (com.google.gson.JsonElement element : merged.getAsJsonArray("recipes")) {
            try {
                com.google.gson.JsonObject recipe = element.getAsJsonObject();
                if (!recipe.has("type") || !"smithing".equals(recipe.get("type").getAsString())) {
                    continue;
                }
                com.google.gson.JsonArray slots = recipe.getAsJsonArray("slots");
                if (slots == null || slots.size() <= BASE_SLOT_INDEX) {
                    continue;
                }
                com.google.gson.JsonElement baseSlot = slots.get(BASE_SLOT_INDEX);
                if (!baseSlot.isJsonArray()) {
                    continue;
                }
                for (com.google.gson.JsonElement candidate : baseSlot.getAsJsonArray()) {
                    com.google.gson.JsonObject ref = candidate.getAsJsonObject();
                    if (!ref.has("cmd")) {
                        // A vanilla base needs no exemption: it never had a CMD to strip.
                        continue;
                    }
                    found.add(key(ref.get("material").getAsString(), ref.get("cmd").getAsInt()));
                }
            } catch (RuntimeException e) {
                // One odd entry must not empty the whole exemption set: that would re-enable the
                // strip for items the proxy is still shipping recipes for, i.e. break them.
                // The extension applies its own validation to the same file.
            }
        }
        return found;
    }

    /** Index of the base slot inside a smithing entry (template, base, addition). */
    private static final int BASE_SLOT_INDEX = 1;
}
