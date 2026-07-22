package com.geyserextra.paper.recipe;

import com.geyserextra.paper.GeyserExtraPaper;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Handles custom smithing table recipes for Bedrock players.
 *
 * Why: Bedrock Edition has different smithing table mechanics than Java Edition.
 * Custom smithing recipes from plugins/datapacks may not work correctly for
 * Bedrock players through Geyser. This handler intercepts smithing events
 * and ensures custom recipes work for both editions.
 *
 * Smithing Table Slots (1.20+):
 * - Slot 0: Template (netherite upgrade template, etc.)
 * - Slot 1: Base item (diamond sword, etc.)
 * - Slot 2: Addition (netherite ingot, etc.)
 * - Slot 3: Result
 */
public final class SmithingRecipeHandler implements Listener {

    private final GeyserExtraPaper plugin;
    private final FloodgateApi floodgateApi;
    private final Map<String, SmithingRecipe> customRecipes;
    private final boolean enabled;

    /**
     * Creates a new SmithingRecipeHandler.
     *
     * @param plugin the parent plugin instance
     */
    public SmithingRecipeHandler(GeyserExtraPaper plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.customRecipes = new HashMap<>();

        FloodgateApi api = null;
        boolean isEnabled = false;
        try {
            api = FloodgateApi.getInstance();
            isEnabled = api != null;
            if (isEnabled) {
                plugin.getLogger().fine("SmithingRecipeHandler: Floodgate detected, Bedrock smithing support enabled");
            }
        } catch (NoClassDefFoundError | Exception e) {
            plugin.getLogger().fine("SmithingRecipeHandler: Floodgate not available, handler disabled");
        }

        this.floodgateApi = api;
        this.enabled = isEnabled;
    }

    /**
     * Checks if this handler is enabled.
     *
     * @return true if Floodgate is available
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Checks if a player is a Bedrock player.
     *
     * @param player the player to check
     * @return true if the player is a Bedrock player
     */
    private boolean isBedrockPlayer(Player player) {
        if (floodgateApi == null) {
            return false;
        }
        return floodgateApi.isFloodgatePlayer(player.getUniqueId());
    }

    /**
     * Registers a custom smithing recipe.
     *
     * @param id unique recipe identifier
     * @param recipe the recipe to register
     */
    public void registerRecipe(String id, SmithingRecipe recipe) {
        customRecipes.put(id, recipe);
        plugin.getLogger().fine("Registered custom smithing recipe: " + id);
    }

    /**
     * Unregisters a custom smithing recipe.
     *
     * @param id the recipe identifier to remove
     */
    public void unregisterRecipe(String id) {
        customRecipes.remove(id);
    }

    /**
     * Handles the PrepareSmithingEvent to apply custom recipes.
     *
     * Why: This event fires when items are placed in a smithing table,
     * allowing us to modify the result before it's shown to the player.
     *
     * @param event the prepare smithing event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        if (!enabled) {
            return;
        }

        SmithingInventory inventory = event.getInventory();

        // Get items from smithing table slots
        // Note: Slot indices may vary by Minecraft version
        ItemStack template = null;
        ItemStack baseItem = null;
        ItemStack addition = null;

        try {
            // 1.20+ format with template slot
            ItemStack[] contents = inventory.getContents();
            if (contents.length >= 3) {
                template = contents[0];  // Template slot
                baseItem = contents[1];  // Base item slot
                addition = contents[2];  // Addition slot
            }
        } catch (Exception e) {
            // Fallback for older versions or unexpected inventory layouts
            plugin.getLogger().warning("Failed to read smithing inventory: " + e.getMessage());
            return;
        }

        if (baseItem == null) {
            return;
        }

        // Check for matching custom recipes
        for (SmithingRecipe recipe : customRecipes.values()) {
            if (recipe.matches(template, baseItem, addition)) {
                ItemStack result = recipe.getResult(template, baseItem, addition);
                if (result != null) {
                    event.setResult(result);

                    if (plugin.getGeyserExtraConfig().general().debugMode()) {
                        plugin.getLogger().fine("Applied custom smithing recipe: "
                            + (template != null ? template.getType() : "null") + " + "
                            + baseItem.getType() + " + "
                            + (addition != null ? addition.getType() : "null"));
                    }
                    break;
                }
            }
        }
    }

    /**
     * Handles inventory click events for Bedrock smithing workarounds.
     *
     * Why: Bedrock clients may need special handling when clicking
     * on smithing result slots due to protocol differences.
     *
     * @param event the inventory click event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled) {
            return;
        }

        if (event.getInventory().getType() != InventoryType.SMITHING) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Only apply Bedrock-specific fixes for Bedrock players
        if (!isBedrockPlayer(player)) {
            return;
        }

        // Slot 3 is typically the result slot in smithing table
        if (event.getRawSlot() == 3) {
            SmithingInventory smithing = (SmithingInventory) event.getInventory();
            ItemStack result = smithing.getResult();

            if (result != null && result.getType() != Material.AIR) {
                // Force update inventory for Bedrock client
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        player.updateInventory();
                    }
                }, 1L);
            }
        }
    }

    /**
     * Gets the number of registered custom recipes.
     *
     * @return the recipe count
     */
    public int getRecipeCount() {
        return customRecipes.size();
    }

    /**
     * Represents a custom smithing recipe.
     */
    public interface SmithingRecipe {
        /**
         * Checks if this recipe matches the given items.
         *
         * @param template the template item (can be null for recipes without template)
         * @param baseItem the base item
         * @param addition the addition item (can be null)
         * @return true if the recipe matches
         */
        boolean matches(ItemStack template, ItemStack baseItem, ItemStack addition);

        /**
         * Gets the result of this recipe.
         *
         * @param template the template item
         * @param baseItem the base item
         * @param addition the addition item
         * @return the resulting item
         */
        ItemStack getResult(ItemStack template, ItemStack baseItem, ItemStack addition);
    }

    /**
     * Simple smithing recipe implementation for transform recipes.
     * Transform recipes change the base item's material while preserving enchantments.
     */
    public static class TransformSmithingRecipe implements SmithingRecipe {
        private final Material templateMaterial;
        private final Material baseMaterial;
        private final Material additionMaterial;
        private final Material resultMaterial;

        public TransformSmithingRecipe(
            Material templateMaterial,
            Material baseMaterial,
            Material additionMaterial,
            Material resultMaterial
        ) {
            this.templateMaterial = templateMaterial;
            this.baseMaterial = baseMaterial;
            this.additionMaterial = additionMaterial;
            this.resultMaterial = resultMaterial;
        }

        @Override
        public boolean matches(ItemStack template, ItemStack baseItem, ItemStack addition) {
            // Check template
            if (templateMaterial != null) {
                if (template == null || template.getType() != templateMaterial) {
                    return false;
                }
            }

            // Check base item
            if (baseItem == null || baseItem.getType() != baseMaterial) {
                return false;
            }

            // Check addition
            if (additionMaterial != null) {
                if (addition == null || addition.getType() != additionMaterial) {
                    return false;
                }
            }

            return true;
        }

        @Override
        public ItemStack getResult(ItemStack template, ItemStack baseItem, ItemStack addition) {
            ItemStack result = new ItemStack(resultMaterial);

            // Copy enchantments and other meta from base item
            if (baseItem.hasItemMeta()) {
                result.setItemMeta(baseItem.getItemMeta().clone());
            }

            return result;
        }
    }

    /**
     * Simple smithing recipe implementation for trim recipes.
     * Trim recipes add armor trim patterns without changing the base item.
     */
    public static class TrimSmithingRecipe implements SmithingRecipe {
        private final Material templateMaterial;
        private final Material trimMaterial;
        private final java.util.function.BiFunction<ItemStack, ItemStack, ItemStack> trimFunction;

        public TrimSmithingRecipe(
            Material templateMaterial,
            Material trimMaterial,
            java.util.function.BiFunction<ItemStack, ItemStack, ItemStack> trimFunction
        ) {
            this.templateMaterial = templateMaterial;
            this.trimMaterial = trimMaterial;
            this.trimFunction = trimFunction;
        }

        @Override
        public boolean matches(ItemStack template, ItemStack baseItem, ItemStack addition) {
            if (template == null || template.getType() != templateMaterial) {
                return false;
            }
            if (baseItem == null) {
                return false;
            }
            if (addition == null || addition.getType() != trimMaterial) {
                return false;
            }
            // Check if base item is armor
            String name = baseItem.getType().name();
            return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
        }

        @Override
        public ItemStack getResult(ItemStack template, ItemStack baseItem, ItemStack addition) {
            return trimFunction.apply(baseItem.clone(), addition);
        }
    }
}
