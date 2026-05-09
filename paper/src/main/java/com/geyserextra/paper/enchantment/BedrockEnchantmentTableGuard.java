package com.geyserextra.paper.enchantment;

import com.geyserextra.paper.GeyserExtraPaper;

import io.papermc.paper.datacomponent.DataComponentTypes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Objects;
import java.util.UUID;

/**
 * Blocks Bedrock players from placing CustomModelData items into the
 * enchantment table input slot.
 *
 * <p>Why: Geyser registers CMD-bearing items as Bedrock-side custom items via the
 * auto-generated pack. When such a custom item is placed into the enchantment
 * table, Bedrock attempts to compute its preview enchantment list against the
 * custom identifier. The custom item lacks the enchantability/tag metadata
 * Bedrock expects, which crashes the client. The Java-side server reports no
 * exception in this scenario.</p>
 *
 * <p>Mitigation: cancel any interaction that would land a CMD item in slot 0
 * of the enchantment table for a Floodgate player and surface a clear notice.
 * Java players and non-CMD items are unaffected.</p>
 */
public final class BedrockEnchantmentTableGuard implements Listener {

    /** Slot index of the item-to-enchant slot in an enchantment table inventory. */
    private static final int ENCHANT_INPUT_SLOT = 0;

    private final GeyserExtraPaper plugin;
    private final FloodgateApi floodgateApi;
    private final boolean enabled;

    public BedrockEnchantmentTableGuard(GeyserExtraPaper plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");

        FloodgateApi api = null;
        boolean isEnabled = false;
        try {
            api = FloodgateApi.getInstance();
            isEnabled = api != null;
            if (isEnabled) {
                plugin.getLogger().info(
                    "BedrockEnchantmentTableGuard: blocking CMD items in enchantment tables for Bedrock players");
            }
        } catch (NoClassDefFoundError | Exception e) {
            plugin.getLogger().info(
                "BedrockEnchantmentTableGuard: Floodgate not available, disabled");
        }

        this.floodgateApi = api;
        this.enabled = isEnabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Cancels click interactions that would deposit a CMD item into the
     * enchantment table input slot for a Bedrock player.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getView().getTopInventory().getType() != InventoryType.ENCHANTING) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!isBedrockPlayer(player)) {
            return;
        }

        ItemStack candidate = resolveDepositCandidate(event, player);
        if (isCustomModelDataItem(candidate)) {
            event.setCancelled(true);
            sendNotice(player);
        }
    }

    /**
     * Cancels drag interactions that span the enchantment table input slot
     * when the dragged item is a CMD item.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getView().getTopInventory().getType() != InventoryType.ENCHANTING) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!isBedrockPlayer(player)) {
            return;
        }
        if (!event.getRawSlots().contains(ENCHANT_INPUT_SLOT)) {
            return;
        }

        if (isCustomModelDataItem(event.getOldCursor())) {
            event.setCancelled(true);
            sendNotice(player);
        }
    }

    /**
     * Determines which item the click would deposit into the enchantment input slot.
     * Returns null when the click does not deposit anything into slot 0.
     */
    private ItemStack resolveDepositCandidate(InventoryClickEvent event, Player player) {
        InventoryAction action = event.getAction();

        // Shift-click from the player's own inventory routes to the table input slot.
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            if (event.getClickedInventory() != null
                && event.getClickedInventory().getType() != InventoryType.ENCHANTING) {
                return event.getCurrentItem();
            }
            return null;
        }

        // All other deposit actions target slot 0 of the top inventory directly.
        if (event.getRawSlot() != ENCHANT_INPUT_SLOT) {
            return null;
        }
        if (event.getClickedInventory() == null
            || event.getClickedInventory().getType() != InventoryType.ENCHANTING) {
            return null;
        }

        return switch (action) {
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR -> event.getCursor();
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                int hotbar = event.getHotbarButton();
                yield hotbar >= 0 ? player.getInventory().getItem(hotbar) : null;
            }
            default -> null;
        };
    }

    private void sendNotice(Player player) {
        player.sendActionBar(Component.text(
                "このアイテムは統合版のエンチャントテーブルでは扱えません(クライアント保護)")
            .color(NamedTextColor.RED));
    }

    private boolean isCustomModelDataItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        try {
            return item.hasData(DataComponentTypes.CUSTOM_MODEL_DATA);
        } catch (Throwable t) {
            // Defensive: older Paper versions may not expose this component API.
            return false;
        }
    }

    private boolean isBedrockPlayer(Player player) {
        try {
            UUID uuid = player.getUniqueId();
            return floodgateApi != null && floodgateApi.isFloodgatePlayer(uuid);
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }
}
