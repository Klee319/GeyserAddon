package com.geyserextra.paper.listener;

import com.geyserextra.paper.util.BedrockPlayerUtil;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
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
    }

    /**
     * Forces server-side handling of every "remove from off-hand" inventory
     * action for Bedrock players, because Bedrock's native UI silently
     * refuses to react to clicks on the off-hand slot for item types it does
     * not consider off-hand-friendly (most weapons, blocks, food, ...). The
     * default Bukkit click resolution does the right thing on the server
     * side, but the Bedrock client does not visualise the resulting state
     * change, so the player perceives the item as stuck. Re-doing the
     * inventory mutation ourselves and then calling {@code updateInventory()}
     * re-syncs the Bedrock client view so the action takes visible effect.
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

        PlayerInventory inv = player.getInventory();
        ItemStack offhand = inv.getItemInOffHand();
        if (offhand == null || offhand.getType() == Material.AIR) {
            return;
        }

        InventoryAction action = event.getAction();
        ItemStack cursor = event.getCursor();
        boolean cursorEmpty = cursor == null || cursor.getType() == Material.AIR;

        switch (action) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME -> {
                // Empty-cursor pick-up: move whole off-hand stack to cursor.
                if (!cursorEmpty) {
                    return;  // shouldn't happen with PICKUP_* but be defensive
                }
                event.setCancelled(true);
                ItemStack toCursor = offhand.clone();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    inv.setItemInOffHand(null);
                    player.setItemOnCursor(toCursor);
                    player.updateInventory();
                });
            }
            case SWAP_WITH_CURSOR -> {
                // Cursor and off-hand swap (cursor holds a different stack).
                if (cursorEmpty) {
                    return;  // collapse-with-empty-cursor is handled above
                }
                event.setCancelled(true);
                ItemStack newOffhand = cursor.clone();
                ItemStack toCursor = offhand.clone();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    inv.setItemInOffHand(newOffhand);
                    player.setItemOnCursor(toCursor);
                    player.updateInventory();
                });
            }
            case MOVE_TO_OTHER_INVENTORY -> {
                // Shift-click: send off-hand stack to the first slot that
                // accepts it. If nothing accepts (inventory full), leave the
                // item where it is so we never lose it silently.
                event.setCancelled(true);
                ItemStack snapshot = offhand.clone();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    inv.setItemInOffHand(null);
                    Map<Integer, ItemStack> overflow = inv.addItem(snapshot);
                    if (!overflow.isEmpty()) {
                        // Couldn't place; restore so the player doesn't lose it.
                        inv.setItemInOffHand(snapshot);
                    }
                    player.updateInventory();
                });
            }
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                // Number-key swap: off-hand <-> chosen hotbar slot.
                int hotbar = event.getHotbarButton();
                if (hotbar < 0 || hotbar > 8) {
                    return;
                }
                event.setCancelled(true);
                ItemStack snapshot = offhand.clone();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    ItemStack hotbarItem = inv.getItem(hotbar);
                    inv.setItemInOffHand(hotbarItem);
                    inv.setItem(hotbar, snapshot);
                    player.updateInventory();
                });
            }
            case DROP_ALL_SLOT, DROP_ONE_SLOT -> {
                // Drop key on the off-hand slot: drop the off-hand item naturally.
                event.setCancelled(true);
                ItemStack toDrop = action == InventoryAction.DROP_ONE_SLOT
                    ? singleItem(offhand)
                    : offhand.clone();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (action == InventoryAction.DROP_ONE_SLOT && offhand.getAmount() > 1) {
                        ItemStack remaining = offhand.clone();
                        remaining.setAmount(remaining.getAmount() - 1);
                        inv.setItemInOffHand(remaining);
                    } else {
                        inv.setItemInOffHand(null);
                    }
                    player.getWorld().dropItemNaturally(player.getLocation(), toDrop);
                    player.updateInventory();
                });
            }
            default -> {
                // PLACE_*, NOTHING, COLLECT_TO_CURSOR, etc. — leave to default
                // Bukkit behaviour. They generally do not strand items in the
                // off-hand for the Bedrock player.
            }
        }
    }

    /**
     * Returns a clone of {@code stack} with amount = 1, for the DROP_ONE_SLOT
     * action where vanilla drops a single item from the slot regardless of
     * stack size.
     */
    private static ItemStack singleItem(ItemStack stack) {
        ItemStack one = stack.clone();
        one.setAmount(1);
        return one;
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
