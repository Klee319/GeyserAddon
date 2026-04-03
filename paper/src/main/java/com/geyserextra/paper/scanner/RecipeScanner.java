package com.geyserextra.paper.scanner;

import com.geyserextra.paper.GeyserExtraPaper;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
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

            // Scan recipe ingredients for shaped recipes
            if (recipe instanceof ShapedRecipe shapedRecipe) {
                for (ItemStack ingredient : shapedRecipe.getIngredientMap().values()) {
                    if (ingredient != null && scanItemIfNew(ingredient, scannedItems)) {
                        discovered++;
                    }
                }
            }

            // Scan recipe ingredients for shapeless recipes
            if (recipe instanceof ShapelessRecipe shapelessRecipe) {
                for (ItemStack ingredient : shapelessRecipe.getIngredientList()) {
                    if (ingredient != null && scanItemIfNew(ingredient, scannedItems)) {
                        discovered++;
                    }
                }
            }

            // Scan furnace-type recipe inputs
            if (recipe instanceof FurnaceRecipe furnaceRecipe) {
                scanItemIfNew(furnaceRecipe.getInput(), scannedItems);
            }
            if (recipe instanceof BlastingRecipe blastingRecipe) {
                scanItemIfNew(blastingRecipe.getInput(), scannedItems);
            }
            if (recipe instanceof SmokingRecipe smokingRecipe) {
                scanItemIfNew(smokingRecipe.getInput(), scannedItems);
            }
            if (recipe instanceof CampfireRecipe campfireRecipe) {
                scanItemIfNew(campfireRecipe.getInput(), scannedItems);
            }

            // Scan stonecutting recipe input
            if (recipe instanceof StonecuttingRecipe stonecuttingRecipe) {
                scanItemIfNew(stonecuttingRecipe.getInput(), scannedItems);
            }

            // Scan smithing recipes
            if (recipe instanceof SmithingTransformRecipe smithingRecipe) {
                scanItemIfNew(smithingRecipe.getResult(), scannedItems);
            }
        }

        if (plugin.getGeyserExtraConfig().general().debugMode()) {
            plugin.getLogger().info("[RecipeScanner] Scanned " + totalRecipes + " recipes, discovered " + discovered + " custom items");
        }

        return discovered;
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
     * Creates a unique key for an item based on type and CustomModelData.
     *
     * @param item The item stack
     * @return A unique key string, or null if no CMD
     */
    private String createItemKey(ItemStack item) {
        if (item == null) {
            return null;
        }

        try {
            if (!item.hasData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA)) {
                return null;
            }

            var cmd = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA);
            if (cmd == null) {
                return null;
            }

            // Create key from material + CMD data
            StringBuilder key = new StringBuilder();
            key.append(item.getType().name());
            key.append(":");
            key.append(cmd.floats().toString());
            key.append(":");
            key.append(cmd.strings().toString());

            return key.toString();
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
            plugin.getLogger().info("Scanning server recipes for custom items...");
            int discovered = scanAllRecipes();
            plugin.getLogger().info("Recipe scan complete. Discovered " + discovered + " custom items from recipes.");

            // Save to shared folder
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    plugin.getItemMappingRegistry().save(
                        plugin.getSharedFolder().resolve("custom_items.json")
                    );
                    plugin.getLogger().info("Saved custom items to shared folder.");
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to save custom items: " + e.getMessage());
                }
            });
        }, delayTicks);
    }
}
