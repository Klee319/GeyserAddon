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
 *
 * <p>Inventory clicks are handled for both Survival ({@link InventoryType#CRAFTING},
 * raw slot 45) and Creative ({@link InventoryType#CREATIVE}, player-inventory
 * slot 40 or raw slot 45). When Bedrock reports {@link InventoryAction#NOTHING}
 * for an off-hand click the client refused to visualise, the listener forces
 * the equivalent pick-up, swap, or place-all mutation server-side.</p>
 */
public final class OffhandSwapListener implements Listener {

    /** Raw slot index of the off-hand in a player inventory view. */
    private static final int OFFHAND_RAW_SLOT = 45;

    /** {@link PlayerInventory} slot index of the off-hand stack. */
    private static final int OFFHAND_PLAYER_SLOT = 40;

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

        ItemStack droppedItem = event.getItemDrop().getItemStack().clone();
        PlayerInventory inv = player.getInventory();

        // Only the held-slot INDEX is read here. Reading the slot's CONTENTS at
        // event time and re-checking them next tick does not work: whether the
        // dropped stack has already been subtracted from the inventory by the
        // time this event fires depends on the code path that produced the drop
        // (a Geyser-translated Bedrock drop lands after us). A snapshot taken
        // here therefore mismatched the real post-drop slot on every gesture,
        // and the swap aborted every time — the gesture degraded into a plain
        // drop. The contents check still happens, just next tick against the
        // state that actually settled.
        int heldSlot = inv.getHeldItemSlot();
        if (!tryAcquireOp(player)) {
            // A previous gesture is still in flight; the in-flight mutation
            // already represents the player's intent, so dropping the extra
            // request keeps state consistent.
            return;
        }

        // Remove the dropped Item entity so it does not actually appear on the
        // ground. Intentionally NOT cancelling the event: Paper / Spigot may
        // automatically re-add the dropped stack to the player's inventory
        // when a PlayerDropItemEvent is cancelled, which previously caused
        // duplication — the main hand ended the tick still holding the full
        // pre-drop stack, and our reconstruction layered the droppedItem on
        // top, doubling the count and bypassing the stack-size cap. Letting
        // the drop proceed (entity-less) ensures Bukkit subtracts the item
        // from the inventory normally, so reconstructOriginalMain only adds
        // back what was actually removed.
        event.getItemDrop().remove();

        schedule(player, () -> swapHeldSlotWithOffhand(player, heldSlot, droppedItem));
    }

    /**
     * Moves the reconstructed main-hand stack into the off-hand and the
     * off-hand stack into the slot the drop came from.
     *
     * <p>Writes to {@code heldSlot} explicitly rather than through
     * {@code setItemInMainHand}: that method resolves the slot at call time,
     * so a player who scrolled during the one-tick delay would have the swap
     * land on their newly-selected slot and lose whatever was in it.</p>
     *
     * <p>The slot must be overwritten rather than left alone: on a partial drop
     * the leftover sitting there is part of {@code originalMain}, which is
     * about to be written into the off-hand, so leaving it would duplicate it.
     * That makes the occupant check load-bearing — it is what stops the
     * overwrite from destroying an unrelated stack. The slot is accepted only
     * when it is empty (whole-stack drop) or still holds the same item that was
     * dropped (partial drop); anything else means the drop did not come from
     * this slot, and since the dropped entity is already gone the only safe
     * move is to hand the stack back.</p>
     */
    private void swapHeldSlotWithOffhand(Player player, int heldSlot, ItemStack droppedItem) {
        PlayerInventory inv = player.getInventory();
        ItemStack occupant = inv.getItem(heldSlot);
        ItemStack originalMain = reconstructOriginalMain(occupant, droppedItem);
        if (originalMain == null) {
            returnToPlayer(player, droppedItem);
            player.updateInventory();
            return;
        }
        inv.setItem(heldSlot, inv.getItemInOffHand());
        inv.setItemInOffHand(originalMain);
        player.updateInventory();
    }

    /**
     * Gives {@code stack} back to the player, falling back to a ground drop
     * when the inventory is full. Used on the abort paths, where the item
     * entity has already been removed and silently discarding the stack would
     * be item loss.
     */
    private static void returnToPlayer(Player player, ItemStack stack) {
        for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
            if (leftover != null && !isEmpty(leftover)) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    /**
     * Forces server-side handling of off-hand inventory actions for Bedrock
     * players, because Bedrock's native UI silently refuses to react to clicks
     * on the off-hand slot for item types it does not consider off-hand-friendly
     * (most weapons, blocks, food, ...). The default Bukkit click resolution
     * does the right thing on the server side, but the Bedrock client does not
     * visualise the resulting state change, so the player perceives the item as
     * stuck. Re-doing the inventory mutation ourselves and then calling
     * {@code updateInventory()} re-syncs the Bedrock client view so the action
     * takes visible effect.
     *
     * <p>Creative inventory uses player-inventory slot 40 (or raw slot 45) for
     * the off-hand. When Bedrock emits {@link InventoryAction#NOTHING}, this
     * handler infers pick-up, swap, or place-all from cursor/off-hand contents.</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isOffhandSlotClick(event)) {
            return;
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

        plugin.getLogger().fine(() -> String.format(
            "Bedrock offhand click: view=%s rawSlot=%d slot=%d action=%s cursor=%s offhand=%s",
            event.getView().getType(),
            event.getRawSlot(),
            event.getSlot(),
            event.getAction(),
            describeStack(cursor),
            describeStack(offhand)
        ));

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
                applyPickupAll(event, player, inv, offhand);
            }
            case SWAP_WITH_CURSOR -> {
                // Cursor and off-hand swap (cursor holds a different stack).
                if (cursorEmpty || offhandEmpty) {
                    return;
                }
                applySwapWithCursor(event, player, inv, cursor, offhand);
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

                applyPlacement(event, player, inv, cursor, offhand, requested);
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
                        // addItem partially succeeded — sum the overflow
                        // ItemStacks and restore only that amount to the
                        // off-hand. Returning the full snapshot here would
                        // duplicate the portion addItem already deposited
                        // into the main inventory (exploitable: a 64-stack
                        // shift-click into a near-full inventory would leave
                        // the deposited 32 in storage AND 64 back in the
                        // off-hand, yielding a 32-item duplication).
                        int overflowAmount = 0;
                        for (ItemStack leftover : overflow.values()) {
                            if (leftover != null) {
                                overflowAmount += leftover.getAmount();
                            }
                        }
                        if (overflowAmount > 0) {
                            ItemStack remaining = snapshot.clone();
                            remaining.setAmount(overflowAmount);
                            inv.setItemInOffHand(remaining);
                        }
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
            default -> applyForcedNothingAction(
                event, player, inv, cursor, offhand, cursorEmpty, offhandEmpty
            );
        }
    }

    /**
     * Returns true when the click targets the player's off-hand slot in either
     * Survival ({@link InventoryType#CRAFTING}) or Creative
     * ({@link InventoryType#CREATIVE}) inventory views.
     */
    private static boolean isOffhandSlotClick(InventoryClickEvent event) {
        InventoryType viewType = event.getView().getType();
        if (viewType == InventoryType.CRAFTING) {
            return event.getRawSlot() == OFFHAND_RAW_SLOT;
        }
        if (viewType == InventoryType.CREATIVE) {
            return event.getRawSlot() == OFFHAND_RAW_SLOT
                || (event.getClickedInventory() instanceof PlayerInventory
                    && event.getSlot() == OFFHAND_PLAYER_SLOT);
        }
        return false;
    }

    /**
     * When Bedrock reports {@link InventoryAction#NOTHING} (or an unhandled
     * action) for an off-hand click, infer the intended mutation from cursor
     * and off-hand contents so items do not appear stuck client-side.
     */
    private void applyForcedNothingAction(
        InventoryClickEvent event,
        Player player,
        PlayerInventory inv,
        ItemStack cursor,
        ItemStack offhand,
        boolean cursorEmpty,
        boolean offhandEmpty
    ) {
        if (cursorEmpty && offhandEmpty) {
            return;
        }
        if (!cursorEmpty && offhandEmpty) {
            applyPlacement(event, player, inv, cursor, offhand, cursor.getAmount());
            return;
        }
        if (!cursorEmpty) {
            applySwapWithCursor(event, player, inv, cursor, offhand);
            return;
        }
        applyPickupAll(event, player, inv, offhand);
    }

    private void applyPickupAll(
        InventoryClickEvent event,
        Player player,
        PlayerInventory inv,
        ItemStack offhand
    ) {
        if (!tryAcquireOp(player)) {
            return;
        }
        event.setCancelled(true);
        ItemStack toCursor = offhand.clone();
        schedule(player, () -> {
            inv.setItemInOffHand(null);
            player.setItemOnCursor(toCursor);
            player.updateInventory();
        });
    }

    private void applySwapWithCursor(
        InventoryClickEvent event,
        Player player,
        PlayerInventory inv,
        ItemStack cursor,
        ItemStack offhand
    ) {
        if (!tryAcquireOp(player)) {
            return;
        }
        event.setCancelled(true);
        ItemStack newOffhand = cursor.clone();
        ItemStack toCursor = offhand.clone();
        schedule(player, () -> {
            inv.setItemInOffHand(newOffhand);
            player.setItemOnCursor(toCursor);
            player.updateInventory();
        });
    }

    private void applyPlacement(
        InventoryClickEvent event,
        Player player,
        PlayerInventory inv,
        ItemStack cursor,
        ItemStack offhand,
        int requested
    ) {
        PlacementResult plan = planPlacement(cursor, offhand, requested);
        if (plan == null) {
            // Nothing legal to place (e.g. dissimilar full-stack offhand
            // with no room). Let Bukkit handle / cancel naturally.
            return;
        }
        if (!tryAcquireOp(player)) {
            return;
        }
        event.setCancelled(true);
        final ItemStack newOffhandFinal = plan.newOffhand();
        final ItemStack newCursorFinal = plan.newCursor();
        schedule(player, () -> {
            inv.setItemInOffHand(newOffhandFinal);
            player.setItemOnCursor(newCursorFinal);
            player.updateInventory();
        });
    }

    private static String describeStack(ItemStack stack) {
        if (isEmpty(stack)) {
            return "empty";
        }
        return stack.getType().name() + "x" + stack.getAmount();
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
     * Combines {@code remainder} (what the drop left in the main hand) with
     * {@code droppedItem} to recover the pre-drop stack, or returns
     * {@code null} when the drop demonstrably did not come from the main hand.
     *
     * <ul>
     *   <li>Empty remainder (the drop emptied the slot): the original was the
     *       dropped stack alone.</li>
     *   <li>Remainder similar to dropped (single-item drop from a larger
     *       stack): merge counts.</li>
     *   <li>Remainder holds something else: <b>not our gesture</b>. An earlier
     *       revision returned the dropped item here and swapped anyway, which
     *       overwrote the held slot with the off-hand stack and destroyed
     *       whatever the player was really holding.</li>
     * </ul>
     */
    private ItemStack reconstructOriginalMain(ItemStack remainder, ItemStack droppedItem) {
        if (remainder == null || remainder.getType() == Material.AIR) {
            return droppedItem.clone();
        }
        if (!remainder.isSimilar(droppedItem)) {
            return null;
        }
        ItemStack merged = remainder.clone();
        merged.setAmount(mergedMainAmount(
            remainder.getAmount(), droppedItem.getAmount(), merged.getMaxStackSize()));
        return merged;
    }

    /**
     * Stack count of the reconstructed main-hand stack, capped at
     * {@code maxStackSize}.
     *
     * <p>Belt-and-braces: even though the drop event is no longer cancelled
     * (which previously caused Bukkit to auto-restore the stack and make the
     * total overshoot the cap), the merge is capped so an unstackable item can
     * never end up above its maximum. If a future refactor reintroduces the
     * duplication path, the worst case is a silent cap rather than an actual
     * exploit.</p>
     *
     * <p>Package-private and free of Bukkit types so the arithmetic can be
     * covered without a running server.</p>
     */
    static int mergedMainAmount(int remainderAmount, int droppedAmount, int maxStackSize) {
        int total = remainderAmount + droppedAmount;
        if (maxStackSize > 0 && total > maxStackSize) {
            return maxStackSize;
        }
        return total;
    }

    /** Result of a cursor → off-hand placement plan. */
    private record PlacementResult(ItemStack newOffhand, ItemStack newCursor) {}
}
