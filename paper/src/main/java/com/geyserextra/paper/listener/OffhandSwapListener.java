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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Swaps the main-hand and off-hand stacks for a Bedrock player when they
 * press the drop key while sneaking, and re-applies off-hand inventory
 * mutations the Bedrock client refuses to visualise.
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
 *
 * <p><b>Concurrency model:</b> every mutation runs on the next tick to keep
 * Bukkit inventory writes on the primary thread. A per-player pending-op
 * flag ({@link #pendingOffhandOps}) prevents a second event from queueing
 * another mutation against stale inventory state — without this guard, two
 * fast clicks on the off-hand slot could schedule two next-tick jobs that
 * both observe the pre-first-click state and write conflicting amounts,
 * yielding duplicated or lost items.</p>
 */
public final class OffhandSwapListener implements Listener {

    private final Plugin plugin;

    /**
     * Players whose off-hand mutation has been scheduled but not yet executed.
     * A click that arrives while the flag is set is ignored — the queued
     * mutation will run first and the player can issue a fresh click after
     * the next tick. Cleared when the next-tick task finishes (success or
     * skip) and when the player disconnects.
     */
    private final Set<UUID> pendingOffhandOps = ConcurrentHashMap.newKeySet();

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
        if (!tryAcquireOp(player)) {
            // A previous gesture is still in flight; the in-flight mutation
            // already represents the player's intent, so dropping the extra
            // request keeps state consistent.
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

        schedule(player, () -> swapMainHandWithOffhand(player, droppedItem));
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
        ItemStack cursor = event.getCursor();
        boolean cursorEmpty = isEmpty(cursor);
        boolean offhandEmpty = isEmpty(offhand);

        // No-op clicks: cursor empty + offhand empty has nothing to mutate.
        if (cursorEmpty && offhandEmpty) {
            return;
        }

        InventoryAction action = event.getAction();
        switch (action) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME -> {
                // Empty-cursor pick-up: move whole off-hand stack to cursor.
                if (!cursorEmpty || offhandEmpty) {
                    return;
                }
                if (!tryAcquireOp(player)) return;
                event.setCancelled(true);
                ItemStack toCursor = offhand.clone();
                schedule(player, () -> {
                    inv.setItemInOffHand(null);
                    player.setItemOnCursor(toCursor);
                    player.updateInventory();
                });
            }
            case SWAP_WITH_CURSOR -> {
                // Cursor and off-hand swap (cursor holds a different stack).
                if (cursorEmpty || offhandEmpty) {
                    return;
                }
                if (!tryAcquireOp(player)) return;
                event.setCancelled(true);
                ItemStack newOffhand = cursor.clone();
                ItemStack toCursor = offhand.clone();
                schedule(player, () -> {
                    inv.setItemInOffHand(newOffhand);
                    player.setItemOnCursor(toCursor);
                    player.updateInventory();
                });
            }
            case PLACE_ALL, PLACE_ONE, PLACE_SOME -> {
                // Cursor → off-hand placement. Bedrock's UI does not refresh
                // when the client deemed the placement invalid, so we recompute
                // the merged state server-side and push it back.
                if (cursorEmpty) {
                    return;
                }
                int requested = switch (action) {
                    case PLACE_ONE -> 1;
                    case PLACE_ALL, PLACE_SOME -> cursor.getAmount();
                    default -> 0;
                };
                if (requested <= 0) return;

                PlacementResult plan = planPlacement(cursor, offhand, requested);
                if (plan == null) {
                    // Nothing legal to place (e.g. dissimilar full-stack offhand
                    // with no room). Let Bukkit handle / cancel naturally.
                    return;
                }

                if (!tryAcquireOp(player)) return;
                event.setCancelled(true);
                final ItemStack newOffhandFinal = plan.newOffhand();
                final ItemStack newCursorFinal = plan.newCursor();
                schedule(player, () -> {
                    inv.setItemInOffHand(newOffhandFinal);
                    player.setItemOnCursor(newCursorFinal);
                    player.updateInventory();
                });
            }
            case MOVE_TO_OTHER_INVENTORY -> {
                // Shift-click: send off-hand stack to the first slot that
                // accepts it. If nothing accepts (inventory full), leave the
                // item where it is so we never lose it silently.
                if (offhandEmpty) return;
                if (!tryAcquireOp(player)) return;
                event.setCancelled(true);
                ItemStack snapshot = offhand.clone();
                schedule(player, () -> {
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
                if (offhandEmpty && isEmpty(inv.getItem(hotbar))) {
                    return;
                }
                if (!tryAcquireOp(player)) return;
                event.setCancelled(true);
                ItemStack snapshot = offhandEmpty ? null : offhand.clone();
                schedule(player, () -> {
                    ItemStack hotbarItem = inv.getItem(hotbar);
                    inv.setItemInOffHand(hotbarItem);
                    inv.setItem(hotbar, snapshot);
                    player.updateInventory();
                });
            }
            case DROP_ALL_SLOT, DROP_ONE_SLOT -> {
                // Drop key on the off-hand slot: drop the off-hand item naturally.
                if (offhandEmpty) return;
                if (!tryAcquireOp(player)) return;
                event.setCancelled(true);
                final ItemStack toDrop = action == InventoryAction.DROP_ONE_SLOT
                    ? singleItem(offhand)
                    : offhand.clone();
                final InventoryAction finalAction = action;
                final int currentAmount = offhand.getAmount();
                schedule(player, () -> {
                    if (finalAction == InventoryAction.DROP_ONE_SLOT && currentAmount > 1) {
                        ItemStack remaining = offhand.clone();
                        remaining.setAmount(currentAmount - 1);
                        inv.setItemInOffHand(remaining);
                    } else {
                        inv.setItemInOffHand(null);
                    }
                    player.getWorld().dropItemNaturally(player.getLocation(), toDrop);
                    player.updateInventory();
                });
            }
            case COLLECT_TO_CURSOR -> {
                // Double-click: vanilla collects matching items into the cursor.
                // We let Bukkit handle this — its default scanner already walks
                // the whole inventory, and we have no Bedrock-specific issue
                // here that warrants a re-do.
            }
            default -> {
                // NOTHING and any future-added enum values fall through to
                // default Bukkit behaviour. They do not strand items in the
                // off-hand for the Bedrock player.
            }
        }
    }

    /**
     * Releases any per-player op lock when the player disconnects, so the
     * pending-flag set never accumulates stale UUIDs.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingOffhandOps.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Returns true and reserves the lock, or false if the player already has
     * a pending op queued. Callers that get {@code false} must skip without
     * cancelling the event — Bukkit's default resolution is at least
     * consistent, while a half-applied mutation is not.
     */
    private boolean tryAcquireOp(Player player) {
        return pendingOffhandOps.add(player.getUniqueId());
    }

    /**
     * Schedules {@code task} for the next tick, automatically releasing the
     * per-player lock on completion regardless of whether the task ran or the
     * player went offline first.
     */
    private void schedule(Player player, Runnable task) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (player.isOnline()) {
                    task.run();
                }
            } finally {
                pendingOffhandOps.remove(uuid);
            }
        });
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0;
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
     * Computes the (newOffhand, newCursor) pair for a cursor → off-hand
     * placement. Returns {@code null} when nothing can legally be placed.
     *
     * <p>Three sub-cases:
     * <ol>
     *   <li>Empty off-hand: move up to {@code requested} (capped to stack
     *       size) of cursor into off-hand.</li>
     *   <li>Off-hand similar to cursor: top up off-hand by min(requested,
     *       remaining space), and trim cursor accordingly.</li>
     *   <li>Off-hand dissimilar: treated as a SWAP only when {@code requested}
     *       covers the whole cursor stack (otherwise vanilla would refuse).</li>
     * </ol>
     * </p>
     */
    private static PlacementResult planPlacement(
        ItemStack cursor,
        ItemStack offhand,
        int requested
    ) {
        int maxStackSize = cursor.getMaxStackSize();
        if (maxStackSize <= 0) {
            maxStackSize = 64;
        }
        int cursorAmount = cursor.getAmount();
        int amountToPlace = Math.min(Math.max(1, requested), cursorAmount);

        if (isEmpty(offhand)) {
            int placed = Math.min(amountToPlace, maxStackSize);
            if (placed <= 0) return null;
            ItemStack newOffhand = cursor.clone();
            newOffhand.setAmount(placed);
            int leftover = cursorAmount - placed;
            ItemStack newCursor = leftover > 0 ? cursor.clone() : null;
            if (newCursor != null) {
                newCursor.setAmount(leftover);
            }
            return new PlacementResult(newOffhand, newCursor);
        }

        if (offhand.isSimilar(cursor)) {
            int existing = offhand.getAmount();
            int space = Math.max(0, maxStackSize - existing);
            if (space <= 0) return null;
            int placed = Math.min(amountToPlace, space);
            if (placed <= 0) return null;
            ItemStack newOffhand = offhand.clone();
            newOffhand.setAmount(existing + placed);
            int leftover = cursorAmount - placed;
            ItemStack newCursor = leftover > 0 ? cursor.clone() : null;
            if (newCursor != null) {
                newCursor.setAmount(leftover);
            }
            return new PlacementResult(newOffhand, newCursor);
        }

        // Dissimilar off-hand contents: vanilla refuses partial placement, so
        // only honour the request when the whole cursor stack is being moved.
        if (amountToPlace != cursorAmount) {
            return null;
        }
        return new PlacementResult(cursor.clone(), offhand.clone());
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

    /** Result of a cursor → off-hand placement plan. */
    private record PlacementResult(ItemStack newOffhand, ItemStack newCursor) {}
}
