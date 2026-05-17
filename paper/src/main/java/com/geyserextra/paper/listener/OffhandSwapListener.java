package com.geyserextra.paper.listener;

import com.geyserextra.paper.util.BedrockPlayerUtil;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * Swaps the main-hand and off-hand stacks for a Bedrock player when they
 * press the drop key while sneaking, mirroring the Java F-key behaviour
 * without requiring the {@code /offhand} command.
 *
 * <p>Why this trigger: Bedrock has no native off-hand swap gesture. Sneak +
 * drop is rarely a deliberate "drop while sneaking" action (sneak already
 * prevents many drop-side-effects), making it the lowest-conflict reusable
 * gesture available on every Bedrock platform/touchscreen.</p>
 *
 * <p>Semantics: matches Java F-key — whole-stack swap regardless of whether
 * the drop event captured one item (single-tap drop) or the full stack
 * (hold-to-drop). The original main-hand stack is reconstructed from
 * {@code currentMain + droppedItem} before performing the swap.</p>
 *
 * <p>Trade-off: a Bedrock player can no longer drop items while sneaking.
 * They must un-sneak briefly to drop. The {@code /offhand} command remains
 * available for players who prefer not to use the gesture.</p>
 */
public final class OffhandSwapListener implements Listener {

    private final Plugin plugin;

    public OffhandSwapListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    /**
     * Cancels a sneak-drop from a Bedrock player and swaps main-hand with
     * off-hand on the next tick instead.
     *
     * <p>Why HIGHEST priority: we want to run after other plugins have had
     * a chance to cancel the drop (e.g. anti-cheat, region protection).
     * {@code ignoreCancelled = true} skips this handler if anyone above us
     * already cancelled.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
            return;
        }
        if (!player.isSneaking()) {
            return;
        }

        ItemStack droppedItem = event.getItemDrop().getItemStack().clone();
        // Remove the dropped Item entity so it does not actually appear on the
        // ground. Intentionally NOT cancelling the event: Paper / Spigot may
        // automatically re-add the dropped stack to the player's inventory
        // when a PlayerDropItemEvent is cancelled, which previously caused
        // duplication — currentMain ended the tick still holding the full
        // pre-drop stack, and our reconstruction layered the droppedItem on
        // top, doubling the count and bypassing the stack-size cap. Letting
        // the drop proceed (entity-less) ensures Bukkit subtracts the item
        // from the inventory normally, so reconstructOriginalMain only adds
        // back what was actually removed.
        event.getItemDrop().remove();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            swapMainHandWithOffhand(player, droppedItem);
        });
    }

    /**
     * Reconstructs the player's main-hand stack as it was before the drop
     * removed items, then swaps it with the off-hand.
     */
    private void swapMainHandWithOffhand(Player player, ItemStack droppedItem) {
        PlayerInventory inv = player.getInventory();
        ItemStack currentMain = inv.getItemInMainHand();
        ItemStack offhand = inv.getItemInOffHand();

        ItemStack originalMain = reconstructOriginalMain(currentMain, droppedItem);

        inv.setItemInMainHand(offhand);
        inv.setItemInOffHand(originalMain);
        player.updateInventory();

        // Why surface the hint: many items (anything that isn't a shield, totem,
        // map, firework, book, etc.) cannot be removed from Bedrock's off-hand
        // slot via the standard inventory UI — the client just won't react to
        // clicks on those item types. Without an explicit reminder, a player
        // who put a sword or block into the off-hand by accident appears stuck
        // and assumes the plugin is broken. The action-bar message points at
        // the working escape hatch: another sneak + drop, or /offhand.
        if (originalMain != null && originalMain.getType() != Material.AIR
            && !isBedrockOffhandFriendly(originalMain.getType())) {
            player.sendActionBar(Component.text(
                "オフハンドへ移動。元に戻すには再度スニーク+ドロップ、または /offhand")
                .color(NamedTextColor.GRAY));
        }
    }

    /**
     * Pick-up assist for Bedrock players whose client refuses to react to
     * inventory clicks on the off-hand slot. When the player clicks slot 45
     * (off-hand in the default crafting view) with an empty cursor, we
     * explicitly move the off-hand item onto the cursor and force an
     * inventory update. For items the Bedrock UI handles natively (shield,
     * totem, ...) this is a no-op duplicate of the default behaviour; for
     * items it does not, this is the only way to get them out without using
     * the {@code /offhand} command.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getType() != InventoryType.CRAFTING) {
            return;  // not the player's own inventory view
        }
        if (event.getRawSlot() != 45) {
            return;  // not the off-hand slot
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
            return;
        }

        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand == null || offhand.getType() == Material.AIR) {
            return;
        }

        ItemStack cursor = event.getCursor();
        boolean cursorEmpty = cursor == null || cursor.getType() == Material.AIR;
        if (!cursorEmpty) {
            // Player has something on the cursor — let the default place
            // behaviour run unchanged. We only intercept the empty-cursor
            // pick-up case that the Bedrock client may otherwise ignore.
            return;
        }

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.getInventory().setItemInOffHand(null);
            player.setItemOnCursor(offhand);
            player.updateInventory();
        });
    }

    /**
     * Whether Bedrock's native UI is known to permit dragging this item type
     * out of the off-hand slot. Used only to decide whether the action-bar
     * hint about the retrieval workaround is worth showing.
     */
    private static boolean isBedrockOffhandFriendly(Material type) {
        return switch (type) {
            case SHIELD, TOTEM_OF_UNDYING, FILLED_MAP, MAP,
                FIREWORK_ROCKET, WRITABLE_BOOK, WRITTEN_BOOK -> true;
            default -> false;
        };
    }

    /**
     * Combines {@code currentMain} (post-drop remainder) with
     * {@code droppedItem} to recover the pre-drop stack. Handles three cases:
     *
     * <ul>
     *   <li>Empty currentMain (the drop emptied the slot): original was the
     *       dropped stack alone.</li>
     *   <li>currentMain similar to dropped (single-item drop from a larger
     *       stack): merge counts.</li>
     *   <li>currentMain differs from dropped (defensive — shouldn't happen
     *       in normal play): use the dropped item as the reconstructed
     *       stack so the swap still functions.</li>
     * </ul>
     */
    private ItemStack reconstructOriginalMain(ItemStack currentMain, ItemStack droppedItem) {
        if (currentMain == null || currentMain.getType() == Material.AIR) {
            return droppedItem.clone();
        }
        if (currentMain.isSimilar(droppedItem)) {
            ItemStack merged = currentMain.clone();
            int total = currentMain.getAmount() + droppedItem.getAmount();
            // Belt-and-braces: even though we no longer cancel the drop event
            // (which previously caused Bukkit to auto-restore the stack and
            // make `total` overshoot the cap), guard the merge so an unstacked
            // item can never end up with amount > maxStackSize. If a future
            // refactor reintroduces the duplication path, the worst case is
            // a silent cap rather than an actual exploit.
            int max = merged.getMaxStackSize();
            if (max > 0 && total > max) {
                total = max;
            }
            merged.setAmount(total);
            return merged;
        }
        return droppedItem.clone();
    }
}
