package com.geyserextra.paper.recipe;

import com.geyserextra.paper.GeyserExtraPaper;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles crafting recipe synchronization for Bedrock players.
 *
 * Why: Bedrock Edition calculates crafting results client-side, which may differ
 * from server-side custom recipes added by plugins. This handler ensures
 * Bedrock players see the correct crafting result preview.
 */
public final class CraftingRecipeHandler implements Listener {

    private final GeyserExtraPaper plugin;
    private final FloodgateApi floodgateApi;
    private final boolean enabled;

    // Cache for Bedrock players' expected crafting results
    private final Map<UUID, CachedCraftResult> craftResultCache;

    /**
     * Creates a new CraftingRecipeHandler.
     *
     * @param plugin the parent plugin instance
     */
    public CraftingRecipeHandler(GeyserExtraPaper plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.craftResultCache = new ConcurrentHashMap<>();

        FloodgateApi api = null;
        boolean isEnabled = false;
        try {
            api = FloodgateApi.getInstance();
            isEnabled = api != null;
            if (isEnabled) {
                plugin.getLogger().info("CraftingRecipeHandler: Floodgate detected, Bedrock crafting sync enabled");
            }
        } catch (NoClassDefFoundError | Exception e) {
            plugin.getLogger().info("CraftingRecipeHandler: Floodgate not available, handler disabled");
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
     * Handles the PrepareItemCraftEvent to sync crafting results for Bedrock players.
     *
     * Why: When items are placed in a crafting grid, the server calculates the result.
     * For Bedrock players, we cache this result and force update their inventory
     * to show the correct preview.
     *
     * @param event the prepare item craft event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!enabled) {
            return;
        }

        CraftingInventory inventory = event.getInventory();
        ItemStack result = inventory.getResult();
        Recipe recipe = event.getRecipe();

        // Check if any viewer is a Bedrock player
        for (HumanEntity viewer : event.getViewers()) {
            if (viewer instanceof Player player && isBedrockPlayer(player)) {
                if (result != null && result.getType() != Material.AIR) {
                    // Cache the correct result for this player
                    ItemStack[] matrix = inventory.getMatrix();
                    craftResultCache.put(player.getUniqueId(), new CachedCraftResult(
                        cloneMatrix(matrix),
                        result.clone(),
                        recipe != null
                    ));

                    if (plugin.getGeyserExtraConfig().general().debugMode()) {
                        plugin.getLogger().info("[CraftingSync] Cached result for " + player.getName()
                            + ": " + result.getType() + " x" + result.getAmount()
                            + " (custom recipe: " + (recipe != null) + ")");
                    }

                    // Force update inventory to show correct result
                    // Use a slight delay to ensure the result is set
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline() && player.getOpenInventory().getTopInventory() == inventory) {
                            // Re-set the result to ensure Bedrock client sees it
                            inventory.setResult(result);
                            player.updateInventory();
                        }
                    }, 1L);
                } else {
                    // No result, clear cache
                    craftResultCache.remove(player.getUniqueId());
                }
            }
        }
    }

    /**
     * Handles inventory click events for Bedrock crafting workarounds.
     *
     * Why: When a Bedrock player clicks the result slot, we ensure they get
     * the correct server-calculated result, not the Bedrock client's calculation.
     *
     * @param event the inventory click event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled) {
            return;
        }

        // Only handle crafting inventories
        InventoryType type = event.getInventory().getType();
        if (type != InventoryType.CRAFTING && type != InventoryType.WORKBENCH) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!isBedrockPlayer(player)) {
            return;
        }

        // Result slot is slot 0 for both 2x2 and 3x3 crafting
        if (event.getRawSlot() == 0) {
            CachedCraftResult cached = craftResultCache.get(player.getUniqueId());

            if (cached != null && cached.result() != null) {
                CraftingInventory craftingInv = (CraftingInventory) event.getInventory();
                ItemStack currentResult = craftingInv.getResult();

                // If current result differs from cached, apply the cached one
                if (currentResult == null || !currentResult.isSimilar(cached.result())) {
                    if (plugin.getGeyserExtraConfig().general().debugMode()) {
                        plugin.getLogger().info("[CraftingSync] Correcting result for " + player.getName()
                            + ": " + cached.result().getType());
                    }

                    // Set the correct result
                    craftingInv.setResult(cached.result());
                }

                // Clear cache after use
                craftResultCache.remove(player.getUniqueId());

                // Force inventory update
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        player.updateInventory();
                    }
                }, 1L);
            }
        }
    }

    /**
     * Clones a crafting matrix.
     */
    private ItemStack[] cloneMatrix(ItemStack[] matrix) {
        if (matrix == null) {
            return null;
        }
        ItemStack[] clone = new ItemStack[matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            clone[i] = matrix[i] != null ? matrix[i].clone() : null;
        }
        return clone;
    }

    /**
     * Clears the cache for a player.
     *
     * @param playerId the player's UUID
     */
    public void clearCache(UUID playerId) {
        craftResultCache.remove(playerId);
    }

    /**
     * Cached crafting result for Bedrock players.
     *
     * @param matrix the crafting matrix
     * @param result the expected result
     * @param isCustomRecipe whether this is a custom recipe
     */
    private record CachedCraftResult(
        ItemStack[] matrix,
        ItemStack result,
        boolean isCustomRecipe
    ) {}
}
