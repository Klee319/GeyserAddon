package com.geyserextra.paper.scanner;

import com.geyserextra.paper.GeyserExtraPaper;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmithingTrimRecipe;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * Scanner for detecting custom items from server recipes.
 *
 * Why: Instead of waiting for items to appear in player inventories,
 * this scanner proactively scans all registered server recipes to discover
 * custom items with CustomModelData immediately after plugins load.
 */
public final class RecipeScanner {

    private final CustomItemScanner itemScanner;
    private final GeyserExtraPaper plugin;

    /**
     * Creates a new RecipeScanner.
     *
     * @param itemScanner The custom item scanner to use for item detection
     * @param plugin      The plugin instance
     */
    public RecipeScanner(CustomItemScanner itemScanner, GeyserExtraPaper plugin) {
        this.itemScanner = Objects.requireNonNull(itemScanner, "itemScanner must not be null");
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    /**
     * Scans all server recipes for custom items.
     * Should be called after all plugins have loaded their recipes.
     *
     * @return The number of custom items discovered
     */
    public int scanAllRecipes() {
        Set<String> scannedItems = new HashSet<>();
        int discovered = 0;
        int totalRecipes = 0;

        Iterator<Recipe> recipeIterator = Bukkit.recipeIterator();

        while (recipeIterator.hasNext()) {
            Recipe recipe = recipeIterator.next();
            totalRecipes++;

            // Scan recipe result
            ItemStack result = recipe.getResult();
            if (result != null && scanItemIfNew(result, scannedItems)) {
                discovered++;
            }

            // Scan recipe ingredients for shaped recipes.
            // Why getChoiceMap rather than getIngredientMap: the legacy
            // ItemStack-only map is deprecated; getChoiceMap returns the same
            // ingredients in the modern RecipeChoice form, which is the only
            // shape capable of expressing ExactChoice (the one that can carry
            // CMD-bearing custom items).
            if (recipe instanceof ShapedRecipe shapedRecipe) {
                for (RecipeChoice choice : shapedRecipe.getChoiceMap().values()) {
                    discovered += scanChoice(choice, scannedItems);
                }
            }

            // Scan recipe ingredients for shapeless recipes (same reasoning).
            if (recipe instanceof ShapelessRecipe shapelessRecipe) {
                for (RecipeChoice choice : shapelessRecipe.getChoiceList()) {
                    discovered += scanChoice(choice, scannedItems);
                }
            }

            // Scan furnace-type recipe inputs via getInputChoice (modern
            // RecipeChoice form). Why: CookingRecipe#getInput is deprecated
            // because it can only express a single ItemStack, while the actual
            // recipe internally stores a RecipeChoice that may carry multiple
            // accepted items.
            if (recipe instanceof FurnaceRecipe furnaceRecipe) {
                discovered += scanChoice(furnaceRecipe.getInputChoice(), scannedItems);
            }
            if (recipe instanceof BlastingRecipe blastingRecipe) {
                discovered += scanChoice(blastingRecipe.getInputChoice(), scannedItems);
            }
            if (recipe instanceof SmokingRecipe smokingRecipe) {
                discovered += scanChoice(smokingRecipe.getInputChoice(), scannedItems);
            }
            if (recipe instanceof CampfireRecipe campfireRecipe) {
                discovered += scanChoice(campfireRecipe.getInputChoice(), scannedItems);
            }

            // Scan stonecutting recipe input via getInputChoice (same reasoning).
            if (recipe instanceof StonecuttingRecipe stonecuttingRecipe) {
                discovered += scanChoice(stonecuttingRecipe.getInputChoice(), scannedItems);
            }

            // Scan smithing recipes
            if (recipe instanceof SmithingTransformRecipe smithingRecipe) {
                scanItemIfNew(smithingRecipe.getResult(), scannedItems);
            }
        }

        if (plugin.getGeyserExtraConfig().general().debugMode()) {
            plugin.getLogger().fine("[RecipeScanner] Scanned " + totalRecipes + " recipes, discovered " + discovered + " custom items");
        }

        return discovered;
    }

    /**
     * Scans every concrete ItemStack carried by a {@link RecipeChoice} and
     * returns how many newly-discovered custom items the scan recorded.
     *
     * <p>Only {@link RecipeChoice.ExactChoice} can carry CMD-bearing custom
     * items — {@link RecipeChoice.MaterialChoice} stores vanilla materials
     * only (no CustomModelData / displayName / lore), so it has no custom
     * items to discover and is skipped to avoid unnecessary work. Future
     * {@code RecipeChoice} subtypes fall through to {@code getItemStack()} as
     * a best-effort representative scan.</p>
     *
     * <p>{@code @SuppressWarnings("deprecation")}: {@code RecipeChoice#getItemStack()}
     * is marked deprecated in current Paper but is the only public way to
     * obtain a representative ItemStack from a generic RecipeChoice when the
     * concrete subtype is unknown. A future Paper release may publish a typed
     * accessor; until then we silence the warning at method level so the
     * class-wide deprecation guard is not loosened.</p>
     */
    @SuppressWarnings("deprecation")
    private int scanChoice(RecipeChoice choice, Set<String> scannedItems) {
        if (choice == null) {
            return 0;
        }
        int found = 0;
        if (choice instanceof RecipeChoice.ExactChoice exact) {
            for (ItemStack item : exact.getChoices()) {
                if (item != null && scanItemIfNew(item, scannedItems)) {
                    found++;
                }
            }
            return found;
        }
        if (choice instanceof RecipeChoice.MaterialChoice) {
            // vanilla materials only — no custom items possible
            return 0;
        }
        // Unknown RecipeChoice subtype: probe its representative ItemStack.
        // getItemStack() is the only guaranteed accessor on the interface.
        try {
            ItemStack representative = choice.getItemStack();
            if (representative != null && scanItemIfNew(representative, scannedItems)) {
                found++;
            }
        } catch (Throwable ignored) {
            // some choice implementations may not support getItemStack();
            // skip silently — at worst we miss a single custom item that the
            // legacy ItemStack-only path would also have failed to expose.
        }
        return found;
    }

    /**
     * Scans an item if it hasn't been scanned before.
     *
     * @param item         The item to scan
     * @param scannedItems Set of already scanned item identifiers
     * @return true if a new custom item was discovered
     */
    private boolean scanItemIfNew(ItemStack item, Set<String> scannedItems) {
        if (item == null) {
            return false;
        }

        // Create a unique identifier for this item type + CMD combination
        String itemKey = createItemKey(item);
        if (itemKey == null || scannedItems.contains(itemKey)) {
            return false;
        }

        scannedItems.add(itemKey);

        return itemScanner.scanItem(item).isPresent();
    }

    /**
     * Creates a unique key for an item based on type and CustomModelData,
     * falling back to a stable PersistentDataContainer identifier when the
     * item carries no CMD data.
     *
     * <p>Why both forms: the legacy CMD path keys recipes off the CMD floats /
     * strings tuple, which uniquely identifies any CMD-bearing custom item.
     * PDC-only items have neither, so the {@code scanItemIfNew} short-circuit
     * previously returned {@code null} and silently dropped the recipe result
     * before {@link CustomItemScanner#scanItem(ItemStack)} ever ran. We now
     * fall through to a {@code "pdc:<base>:<pdcId>"} key so the scanner gets
     * its chance, while keeping a hard separator ({@code "pdc:"}) so PDC keys
     * cannot collide with CMD keys (which start with the material name).</p>
     *
     * @param item The item stack
     * @return A unique key string, or null if the item is neither CMD-bearing
     *         nor carries a usable PDC identifier (i.e., a regular vanilla item)
     */
    private String createItemKey(ItemStack item) {
        if (item == null) {
            return null;
        }

        try {
            if (item.isDataOverridden(
                io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL)) {
                var itemModel = item.getData(
                    io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL);
                if (itemModel != null && !"minecraft".equals(itemModel.namespace())) {
                    return "item_model:" + item.getType().getKey()
                        + ":" + itemModel.asString();
                }
            }
            if (item.hasData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA)) {
                var cmd = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA);
                if (cmd != null) {
                    // Create key from material + CMD data (legacy form, unchanged).
                    StringBuilder key = new StringBuilder();
                    key.append(item.getType().name());
                    key.append(":");
                    key.append(cmd.floats().toString());
                    key.append(":");
                    key.append(cmd.strings().toString());
                    return key.toString();
                }
                // CMD component present but null contents — fall through to
                // the PDC path. Matches the scanItem dispatch order so the
                // dedupe key tracks whatever scanItem will actually register.
            }

            // PDC fallback: extract namespace-qualified identifier (e.g.
            // "oraxen:fire_sword"). When neither CMD nor a recognised PDC slot
            // is present, return null so the item is treated as vanilla and
            // skipped silently (no registration, no log spam).
            String pdcId = CustomItemScanner.extractStableIdFromPDC(item);
            if (pdcId == null) {
                return null;
            }
            return "pdc:" + item.getType().getKey() + ":" + pdcId;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Schedules a recipe scan after a delay to ensure all plugins have loaded.
     *
     * @param delayTicks The delay in ticks before scanning
     */
    public void scheduleDelayedScan(long delayTicks) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.getLogger().fine("Scanning server recipes for custom items...");
            int discovered = scanAllRecipes();
            plugin.getLogger().fine("Recipe scan complete. Discovered " + discovered + " custom items from recipes.");

            // Save through the shared serializer without rebuilding the live ZIP.
            plugin.saveRegistryMetadataAsync();
        }, delayTicks);
    }
}
