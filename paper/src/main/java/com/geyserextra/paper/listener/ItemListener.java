package com.geyserextra.paper.listener;

import com.geyserextra.paper.GeyserExtraPaper;
import com.geyserextra.paper.scanner.CustomItemScanner;
import com.geyserextra.paper.scanner.SkullScanner;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Listener for item-related events that trigger custom item scanning.
 *
 * This listener monitors player activities to discover and register
 * custom items with CustomModelData for Bedrock player compatibility.
 */
public final class ItemListener implements Listener {

    private static final long SAVE_COOLDOWN_MS = 5000; // 5 seconds between saves

    private final CustomItemScanner scanner;
    private final SkullScanner skullScanner;
    private final GeyserExtraPaper plugin;
    private final AtomicLong lastItemSaveTime = new AtomicLong(0);
    private final AtomicLong lastSkullSaveTime = new AtomicLong(0);

    /**
     * Creates a new ItemListener.
     *
     * @param scanner      The custom item scanner
     * @param skullScanner The skull scanner for custom skull textures
     * @param plugin       The plugin instance
     * @throws NullPointerException if any parameter is null
     */
    public ItemListener(CustomItemScanner scanner, SkullScanner skullScanner, GeyserExtraPaper plugin) {
        this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
        this.skullScanner = Objects.requireNonNull(skullScanner, "skullScanner must not be null");
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    /**
     * Handles player join events to scan player inventory.
     *
     * When a player joins, their inventory is scanned for custom items
     * to ensure all items are registered before they interact with Bedrock players.
     * For Bedrock players, also triggers inventory update to sync item textures.
     *
     * @param event The player join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Schedule async scan to avoid blocking the join process
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            // Run on main thread to safely access inventory
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                scanPlayerInventory(player);
                // Persist newly discovered mappings for the next restart.
                // The live resource-pack ZIP remains immutable after startup.
                plugin.saveRegistryMetadataAsync();
            });
        }, 20L);  // Delay 1 second to allow inventory to fully load

        // Force inventory update for Bedrock players to sync textures
        // This triggers packet updates that help sync skull textures
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                forceInventoryUpdate(player);
            }
        }, 40L);  // 2 second delay
    }

    /**
     * Forces an inventory update for a player.
     * This helps Bedrock players sync custom item/skull textures.
     *
     * Why: Bedrock clients may not properly display custom textures until
     * inventory packets are re-sent. This simulates the effect of opening inventory.
     *
     * @param player The player to update
     */
    private void forceInventoryUpdate(Player player) {
        if (!player.isOnline()) {
            return;
        }

        // Check if Bedrock player using Geyser API
        boolean isBedrockPlayer = isBedrockPlayer(player);

        // Update the player's inventory (sends inventory packets to client)
        player.updateInventory();

        // For Bedrock players, also update held item which can trigger additional sync
        if (isBedrockPlayer) {
            // Re-set the held item slot to trigger item update
            int heldSlot = player.getInventory().getHeldItemSlot();
            player.getInventory().setHeldItemSlot((heldSlot + 1) % 9);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.getInventory().setHeldItemSlot(heldSlot);
                }
            }, 1L);
        }

        if (plugin.getGeyserExtraConfig().general().debugMode()) {
            plugin.getLogger().fine("[InventorySync] Forced inventory update for: " + player.getName()
                + " (Bedrock: " + isBedrockPlayer + ")");
        }
    }

    /**
     * Checks if a player is a Bedrock player using Geyser API.
     *
     * @param player The player to check
     * @return true if the player is connected via Geyser (Bedrock client)
     */
    private boolean isBedrockPlayer(Player player) {
        try {
            // Try to use Geyser API to check if player is from Bedrock
            org.geysermc.geyser.api.GeyserApi geyserApi = org.geysermc.geyser.api.GeyserApi.api();
            if (geyserApi != null) {
                return geyserApi.isBedrockPlayer(player.getUniqueId());
            }
        } catch (NoClassDefFoundError | Exception e) {
            // Geyser API not available, fall back to floodgate check or assume false
            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().fine("Geyser API not available for Bedrock check: " + e.getMessage());
            }
        }

        // Fallback: check if player name has Bedrock prefix (floodgate style)
        String name = player.getName();
        return name.startsWith(".") || name.startsWith("*");
    }

    /**
     * Handles inventory open events to scan opened inventory.
     *
     * When a player opens any inventory (chests, containers, GUI menus, etc.),
     * it is scanned for custom items and skulls. This catches skulls used
     * in plugin GUIs like menus, shops, etc.
     *
     * @param event The inventory open event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        Inventory inventory = event.getInventory();

        // Scan on main thread for thread safety (delay slightly to ensure GUI is populated)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            int discoveredItems = scanner.scanInventory(inventory);
            int discoveredSkulls = scanInventoryForSkulls(inventory);

            if (discoveredItems > 0 || discoveredSkulls > 0) {
                if (plugin.getGeyserExtraConfig().general().debugMode()) {
                    plugin.getLogger().fine(() -> String.format(
                        "[GUI Scan] Inventory type: %s, discovered %d items, %d skulls",
                        inventory.getType(),
                        discoveredItems,
                        discoveredSkulls
                    ));
                }

                // Save immediately if new skulls were discovered
                if (discoveredItems > 0) {
                    saveItemsAsync();
                }
                if (discoveredSkulls > 0) {
                    saveSkullsAsync();
                }
            }
        }, 2L);  // 2 tick delay to ensure GUI contents are set
    }

    /**
     * Handles prepare craft events to scan the craft result.
     *
     * Why: Some plugins register recipes with basic ItemStacks (without PDC),
     * then set the proper ItemStack with PDC in PrepareItemCraftEvent.
     * By scanning here, we capture items that weren't detected during recipe scanning.
     *
     * @param event The prepare item craft event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null || result.getType() == Material.AIR) {
            return;
        }

        // Scan the craft result - this catches items where PDC is set at craft time
        scanner.scanItem(result).ifPresent(mapping -> {
            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().fine("[CraftScan] Discovered custom item from craft: " + mapping.name());
            }

            // Save immediately when new item is discovered
            saveItemsAsync();
        });
    }

    /**
     * Saves items to shared folder asynchronously with rate limiting.
     * Prevents excessive disk writes when many craft events fire in succession.
     */
    private void saveItemsAsync() {
        long now = System.currentTimeMillis();
        long lastSave = lastItemSaveTime.get();

        // Rate limit: only save if enough time has passed
        if (now - lastSave < SAVE_COOLDOWN_MS) {
            return;
        }

        // Attempt to set the new save time (atomic to prevent race conditions)
        if (!lastItemSaveTime.compareAndSet(lastSave, now)) {
            return; // Another thread is already saving
        }

        plugin.saveRegistryMetadataAsync();
    }

    /**
     * Saves skulls to shared folder asynchronously with rate limiting.
     */
    private void saveSkullsAsync() {
        long now = System.currentTimeMillis();
        long lastSave = lastSkullSaveTime.get();

        if (now - lastSave < SAVE_COOLDOWN_MS) {
            return;
        }

        if (!lastSkullSaveTime.compareAndSet(lastSave, now)) {
            return;
        }

        plugin.saveRegistryMetadataAsync();
    }

    /**
     * Handles player item held change events to scan held item.
     *
     * When a player changes their held item slot, the new held item
     * is scanned for custom model data.
     *
     * @param event The player item held event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        int newSlot = event.getNewSlot();

        // Get the item in the new slot
        PlayerInventory inventory = player.getInventory();
        ItemStack heldItem = inventory.getItem(newSlot);

        if (heldItem == null) {
            return;
        }

        // Scan the held item immediately (lightweight operation)
        scanner.scanItem(heldItem).ifPresent(mapping -> {
            if (plugin.getGeyserExtraConfig().general().debugMode()) {
                plugin.getLogger().fine(() -> String.format(
                    "Player %s held custom item: %s",
                    player.getName(),
                    mapping.name()
                ));
            }
        });
    }

    /**
     * Scans a player's entire inventory for custom items and skulls.
     *
     * @param player The player whose inventory to scan
     */
    private void scanPlayerInventory(Player player) {
        if (!player.isOnline()) {
            return;
        }

        PlayerInventory inventory = player.getInventory();

        if (plugin.getGeyserExtraConfig().general().debugMode()) {
            plugin.getLogger().fine("[ScanDebug] Scanning " + player.getName() + " inventory, size: " + inventory.getSize());
            int itemCount = 0;
            for (ItemStack item : inventory.getContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    itemCount++;
                }
            }
            plugin.getLogger().fine("[ScanDebug] Non-empty slots: " + itemCount);
        }

        int discoveredItems = scanner.scanInventory(inventory);
        int discoveredSkulls = 0;

        // Also scan armor contents
        for (ItemStack armorPiece : inventory.getArmorContents()) {
            if (scanner.scanItem(armorPiece).isPresent()) {
                discoveredItems++;
            }
        }

        // Scan offhand
        if (scanner.scanItem(inventory.getItemInOffHand()).isPresent()) {
            discoveredItems++;
        }

        // Scan for skulls in inventory
        discoveredSkulls = scanInventoryForSkulls(inventory);

        if (plugin.getGeyserExtraConfig().general().debugMode()) {
            if (discoveredItems > 0) {
                int finalDiscoveredItems = discoveredItems;
                plugin.getLogger().fine(() -> String.format(
                    "Scanned player %s inventory, discovered %d custom items",
                    player.getName(),
                    finalDiscoveredItems
                ));
            }
            if (discoveredSkulls > 0) {
                int finalDiscoveredSkulls = discoveredSkulls;
                plugin.getLogger().fine(() -> String.format(
                    "Scanned player %s inventory, discovered %d custom skulls",
                    player.getName(),
                    finalDiscoveredSkulls
                ));
            }
        }
    }

    /**
     * Scans an inventory for custom skull textures.
     *
     * @param inventory The inventory to scan
     * @return The number of skulls discovered
     */
    private int scanInventoryForSkulls(Inventory inventory) {
        if (inventory == null) {
            return 0;
        }

        int discovered = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == Material.PLAYER_HEAD) {
                if (skullScanner.scanSkull(item).isPresent()) {
                    discovered++;
                }
            }
        }
        return discovered;
    }
}
