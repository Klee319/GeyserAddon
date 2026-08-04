package com.geyserextra.paper.listener;

import com.geyserextra.paper.util.BedrockPlayerUtil;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Item;
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
import org.bukkit.inventory.meta.ItemMeta;
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
     * Records a Bedrock player's drop and turns it into a main-hand ↔ off-hand
     * swap on the next tick.
     *
     * <p>Why HIGHEST priority: every other plugin that inspects or vetoes the
     * drop has run by then, so the state sampled here is the state that will
     * actually settle.</p>
     *
     * <p>Why {@code ignoreCancelled} is <b>off</b>: a plugin that vetoes the
     * drop is vetoing the item <em>leaving the player</em>, which a swap never
     * does. Skipping cancelled events made the gesture silently dead on any
     * server that protects drops (anti-cheat, region flags, spawn protection) —
     * the reported "nothing happens" symptom. The cancelled case is handled
     * explicitly in {@link #completeSneakDropSwap} instead.</p>
     *
     * <p>Nothing is mutated here — not even the item entity. Both facts this
     * handler needs are unreliable at event time:</p>
     * <ul>
     *   <li><b>Sneak state.</b> Bedrock sends sneaking in its own packet, which
     *       Geyser can translate <em>after</em> the drop (the same race
     *       {@code BedrockAnvilSimulator} documents). Hard-gating on
     *       {@code isSneaking()} here therefore rejected genuinely sneaking
     *       players and degraded the gesture into a plain ground drop — the
     *       reported "the item just falls" symptom. The state is sampled both
     *       now and next tick, and either one counts.</li>
     *   <li><b>Inventory contents.</b> Whether the dropped stack has already
     *       been subtracted depends on the code path that produced the drop, so
     *       only the held-slot INDEX is read here; the contents are re-read next
     *       tick against the state that settled.</li>
     * </ul>
     *
     * <p>Deferring {@code itemDrop.remove()} to the next tick is what makes the
     * abort paths lossless: if the swap turns out not to apply, the entity is
     * simply left alone and the gesture degrades to an ordinary drop.</p>
     *
     * <p>The entity cannot change under us during that tick, and the reason is
     * tick ordering rather than the 40-tick pickup delay — that delay only
     * guards {@code ItemEntity.playerTouch}, while hopper suction and
     * item-entity merging ignore it. CraftBukkit runs
     * {@code scheduler.mainThreadHeartbeat()} at the very top of
     * {@code MinecraftServer.tickChildren}, before both the level tick and the
     * connection tick that produced this drop. A ItemEntity spawned during
     * tick N's connection phase therefore never ticks before our task runs at
     * the head of tick N+1. This is an implicit guarantee: switching
     * {@link #schedule} to {@code runTaskLater(…, 1)} or porting to Folia would
     * silently void it, which is why {@link #completeSneakDropSwap} re-reads
     * the entity's contents instead of trusting the snapshot taken here.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
            return;
        }

        PlayerInventory inv = player.getInventory();
        Item entity = event.getItemDrop();
        ItemStack droppedSnapshot = entity.getItemStack().clone();
        int heldSlot = inv.getHeldItemSlot();
        boolean sneakingAtDrop = player.isSneaking();

        // Post-drop count of the held slot, but only when it still holds the
        // same item. The upstream-veto branch uses it to tell "the stack came
        // back to this slot" from "this slot merely happens to hold something
        // similar"; see decide().
        ItemStack heldNow = inv.getItem(heldSlot);
        int heldAmountAtDrop = heldNow != null && heldNow.isSimilar(droppedSnapshot)
            ? heldNow.getAmount()
            : 0;

        if (!tryAcquireOp(player)) {
            // A previous gesture is still in flight; the in-flight mutation
            // already represents the player's intent, so dropping the extra
            // request keeps state consistent.
            return;
        }
        schedule(player, () -> completeSneakDropSwap(
            player, heldSlot, droppedSnapshot, heldAmountAtDrop, entity, sneakingAtDrop));
    }

    /**
     * Next-tick half of the sneak-drop gesture: decides whether the drop was
     * really the swap gesture and, if so, performs it.
     *
     * <p>Every abort path here leaves the world exactly as an ordinary drop
     * would: the item entity is only removed once the swap is committed.</p>
     */
    private void completeSneakDropSwap(
        Player player,
        int heldSlot,
        ItemStack droppedSnapshot,
        int heldAmountAtDrop,
        Item entity,
        boolean sneakingAtDrop
    ) {
        PlayerInventory inv = player.getInventory();
        ItemStack occupant = inv.getItem(heldSlot);

        // What the entity holds RIGHT NOW is what disappears when we remove it,
        // and that is not necessarily what was snapshotted at event time: a
        // MONITOR handler can call setItemStack, and this plugin's own
        // BedrockEnchantmentHandler already rewrites the stack in place at HIGH.
        // Re-reading is what keeps "what we destroy" and "what we hand back" the
        // same items — trusting the snapshot would delete one stack and mint
        // another.
        ItemStack liveDrop = entity.isValid() ? entity.getItemStack() : null;
        boolean entityUsable = !isEmpty(liveDrop);

        Reconstructed original = entityUsable
            ? reconstructOriginalMain(occupant, liveDrop)
            : null;
        // Only meaningful when the entity never reached the world: it says the
        // held slot got the whole pre-drop stack back, which is how CraftBukkit
        // resolves a cancelled hand-thrown drop. The count has to have grown by
        // exactly the dropped amount — Paper's cancel fallback is a plain
        // addItem() that lands in whatever slot is free, so "this slot holds a
        // similar item" on its own would also match a slot that never received
        // anything.
        boolean slotHoldsDrop = !isEmpty(occupant)
            && occupant.isSimilar(droppedSnapshot)
            && heldSlotAbsorbedTheDrop(
                heldAmountAtDrop, occupant.getAmount(), droppedSnapshot.getAmount());

        SwapDecision decision = decide(
            sneakingAtDrop, player.isSneaking(), entityUsable, slotHoldsDrop, original != null);

        switch (decision) {
            case SKIP_NOT_SNEAKING -> plugin.getLogger().fine(
                () -> "Sneak-drop swap skipped for " + player.getName()
                    + ": not sneaking at drop time nor on the following tick");
            case ABORT_ENTITY_GONE -> plugin.getLogger().fine(
                () -> "Sneak-drop swap aborted for " + player.getName()
                    + ": the dropped entity never reached the world and the held slot"
                    + " does not hold the stack back (held=" + describeStack(occupant)
                    + ", dropped=" + describeStack(droppedSnapshot) + ")");
            case ABORT_FOREIGN_SLOT -> plugin.getLogger().fine(() -> String.format(
                "Sneak-drop swap aborted for %s: held slot %d holds %s, dropped %s",
                player.getName(), heldSlot, describeStack(occupant), describeStack(liveDrop)));
            case SWAP_RESTORED_STACK -> {
                plugin.getLogger().fine(() -> "Sneak-drop swap for " + player.getName()
                    + ": drop was cancelled upstream, swapping the restored stack directly");
                swapHeldSlotWithOffhand(player, inv, heldSlot);
            }
            case COMMIT_RECONSTRUCTED -> commitSwap(player, inv, heldSlot, entity, original);
        }
    }

    /** What {@link #completeSneakDropSwap} resolved the drop to. */
    enum SwapDecision {
        /** Not the gesture — leave the drop entirely alone. */
        SKIP_NOT_SNEAKING,
        /** The entity is unusable and the held slot does not hold the stack back. */
        ABORT_ENTITY_GONE,
        /** The entity is live, but the drop did not come from the held slot. */
        ABORT_FOREIGN_SLOT,
        /** The drop was vetoed upstream and restored — exchange the two slots. */
        SWAP_RESTORED_STACK,
        /** Consume the entity and write the reconstructed stacks. */
        COMMIT_RECONSTRUCTED
    }

    /**
     * Branch table of the gesture, extracted free of Bukkit types so every path
     * that decides whether items move can be pinned by tests.
     *
     * <p>Note there is no "was the event cancelled" input. Reading
     * {@code isCancelled()} at HIGHEST misses a veto applied by a later handler,
     * and — more importantly — Paper's cancel path only restores to the held
     * slot for hand-thrown drops; its fallback branch calls {@code addItem},
     * which lands the stack in an arbitrary free slot. So the cancelled case is
     * recognised by observing that the entity never reached the world
     * <em>and</em> the held slot grew by exactly the dropped amount.</p>
     *
     * <p>What that guarantees, precisely: {@code SWAP_RESTORED_STACK} is a pure
     * exchange of two slots, so it can never change the item count whatever the
     * veto did. What it does <b>not</b> guarantee is that the drop originated
     * from the held slot. {@code addItem} fills partial stacks before empty
     * ones and scans the hotbar first, so a drag-out from an inventory window
     * can be merged into a similar held stack and satisfy the test — the player
     * then gets a hand swap they did not ask for. Ruling that out needs a
     * "was this thrown from the hand" signal that Bukkit does not expose. The
     * amount check does rule out the commoner case where the restore landed in
     * some other slot entirely and the held slot merely looked similar.</p>
     */
    static SwapDecision decide(
        boolean sneakingAtDrop,
        boolean sneakingNow,
        boolean entityUsable,
        boolean heldSlotHoldsDroppedItem,
        boolean reconstructable
    ) {
        if (!sneakingAtDrop && !sneakingNow) {
            return SwapDecision.SKIP_NOT_SNEAKING;
        }
        if (!entityUsable) {
            return heldSlotHoldsDroppedItem
                ? SwapDecision.SWAP_RESTORED_STACK
                : SwapDecision.ABORT_ENTITY_GONE;
        }
        return reconstructable
            ? SwapDecision.COMMIT_RECONSTRUCTED
            : SwapDecision.ABORT_FOREIGN_SLOT;
    }

    /**
     * Point of no return: destroys the item entity and writes the reconstructed
     * stacks. Every item the entity carried is accounted for by
     * {@code mainStack} plus {@code overflow}.
     */
    private static void commitSwap(
        Player player,
        PlayerInventory inv,
        int heldSlot,
        Item entity,
        Reconstructed original
    ) {
        // getItemInOffHand hands back a live mirror of slot 40, so it is
        // snapshotted before the first write rather than read across it.
        ItemStack previousOffhand = inv.getItemInOffHand();
        ItemStack newHeld = isEmpty(previousOffhand) ? null : previousOffhand.clone();
        entity.remove();
        inv.setItem(heldSlot, newHeld);
        inv.setItemInOffHand(original.mainStack());
        if (original.overflow() != null) {
            returnToPlayer(player, original.overflow());
        }
        player.updateInventory();
    }

    /**
     * Straight exchange of {@code heldSlot} and the off-hand, with no
     * reconstruction. Used when the drop was cancelled upstream and the slot
     * therefore still holds the untouched pre-drop stack — a pure exchange
     * cannot change the item count no matter how the veto was resolved.
     *
     * <p>Writes to {@code heldSlot} explicitly rather than through
     * {@code setItemInMainHand}: that method resolves the slot at call time,
     * so a player who scrolled during the one-tick delay would have the swap
     * land on their newly-selected slot and lose whatever was in it.</p>
     */
    private static void swapHeldSlotWithOffhand(Player player, PlayerInventory inv, int heldSlot) {
        ItemStack held = inv.getItem(heldSlot);
        ItemStack offhand = inv.getItemInOffHand();
        if (isEmpty(held) && isEmpty(offhand)) {
            return;
        }
        // getItem / getItemInOffHand hand back live mirrors of the underlying
        // slots, so both sides are cloned before either write lands.
        ItemStack newOffhand = isEmpty(held) ? null : held.clone();
        ItemStack newHeld = isEmpty(offhand) ? null : offhand.clone();
        inv.setItem(heldSlot, newHeld);
        inv.setItemInOffHand(newOffhand);
        player.updateInventory();
    }

    /**
     * Gives {@code stack} back to the player, falling back to a ground drop
     * when the inventory is full. Used for the surplus of a merge that hit the
     * stack-size cap: the dropped entity is gone by that point, so discarding
     * the surplus would be item loss.
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
     *
     * <p>Dead players are skipped as well as offline ones: {@code isOnline()}
     * stays true through the death screen, and an inventory written there is
     * discarded on respawn — so a mutation that also consumed an item entity
     * would destroy the items outright.</p>
     */
    private void schedule(Player player, Runnable task) {
        UUID uuid = player.getUniqueId();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (player.isOnline() && !player.isDead()) {
                        task.run();
                    }
                } finally {
                    pendingOffhandOps.remove(uuid);
                }
            });
        } catch (Throwable failedToQueue) {
            // runTask throws IllegalPluginAccessException once the plugin is
            // being disabled (/reload, shutdown). Callers reserve the lock
            // before calling in, so without this the UUID would stay in the
            // pending set for the rest of the session and every later gesture
            // and off-hand click correction for that player would be dropped.
            pendingOffhandOps.remove(uuid);
            throw failedToQueue;
        }
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
     *
     * <p>The match test is {@code isSimilar} widened to ignore lore, because a
     * handler running earlier can rewrite the dropped entity's stack in place:
     * this plugin's own {@code BedrockEnchantmentHandler} strips its injected
     * {@code [GE]} lore from dropped items at HIGH, before this listener's
     * HIGHEST. A plain {@code isSimilar} then rejects an ordinary partial drop
     * of such an item — the gesture aborts for exactly the items players most
     * want it on. Only lore is ignored; enchantments, damage, custom name and
     * every other component still have to match, so a decorated stack can never
     * absorb a plain one and clone its NBT.</p>
     */
    private Reconstructed reconstructOriginalMain(ItemStack remainder, ItemStack droppedItem) {
        boolean slotEmptied = remainder == null || remainder.getType() == Material.AIR;
        if (!slotEmptied
            && !remainder.isSimilar(droppedItem)
            && !similarIgnoringLore(remainder, droppedItem)) {
            return null;
        }
        // The remainder is the stack the server never handed to another
        // handler, so it is the authoritative template when it exists.
        ItemStack template = slotEmptied ? droppedItem : remainder;
        int remainderAmount = slotEmptied ? 0 : remainder.getAmount();
        int droppedAmount = droppedItem.getAmount();
        int cap = template.getMaxStackSize();

        ItemStack mainStack = template.clone();
        mainStack.setAmount(mergedMainAmount(remainderAmount, droppedAmount, cap));

        int surplus = overflowMainAmount(remainderAmount, droppedAmount, cap);
        if (surplus <= 0) {
            return new Reconstructed(mainStack, null);
        }
        ItemStack overflow = template.clone();
        overflow.setAmount(surplus);
        return new Reconstructed(mainStack, overflow);
    }

    /**
     * Whether the held slot's count grew by exactly the dropped amount — what
     * "Paper's cancel handling put the stack back here" looks like from the
     * outside. A slot that never received anything keeps its pre-drop count and
     * fails this even when it holds a similar item.
     *
     * <p>Package-private and free of Bukkit types so the guard can be pinned by
     * tests.</p>
     */
    static boolean heldSlotAbsorbedTheDrop(
        int heldAmountAtDrop, int occupantAmountNow, int droppedAmount) {
        return occupantAmountNow == heldAmountAtDrop + droppedAmount;
    }

    /**
     * {@link ItemStack#isSimilar} with lore excluded from the comparison.
     *
     * <p>Exists so that a handler which rewrites a dropped stack's lore in
     * place before this listener runs cannot make a stack stop matching itself.
     * Both sides are cloned, so neither the inventory nor the item entity is
     * touched.</p>
     */
    private static boolean similarIgnoringLore(ItemStack a, ItemStack b) {
        if (a.getType() != b.getType()) {
            return false;
        }
        ItemStack strippedA = a.clone();
        ItemStack strippedB = b.clone();
        ItemMeta metaA = strippedA.getItemMeta();
        ItemMeta metaB = strippedB.getItemMeta();
        if (metaA == null || metaB == null) {
            return false;
        }
        metaA.lore(null);
        metaB.lore(null);
        strippedA.setItemMeta(metaA);
        strippedB.setItemMeta(metaB);
        return strippedA.isSimilar(strippedB);
    }

    /**
     * Stack count of the reconstructed main-hand stack, capped at
     * {@code maxStackSize}.
     *
     * <p>Belt-and-braces: even though the drop event is no longer cancelled
     * (which previously caused Bukkit to auto-restore the stack and make the
     * total overshoot the cap), the merge is capped so an unstackable item can
     * never end up above its maximum. If a future refactor reintroduces the
     * duplication path, the worst case is a cap rather than an actual exploit —
     * and the capped-off surplus is handed back to the player via
     * {@link #overflowMainAmount} rather than deleted.</p>
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

    /**
     * Items the cap in {@link #mergedMainAmount} left over, which the caller
     * must give back to the player. Silently discarding this was a latent
     * item-loss path: the dropped entity is gone by then, so a capped merge
     * destroyed the difference.
     */
    static int overflowMainAmount(int remainderAmount, int droppedAmount, int maxStackSize) {
        int total = remainderAmount + droppedAmount;
        if (maxStackSize > 0 && total > maxStackSize) {
            return total - maxStackSize;
        }
        return 0;
    }

    /** Result of a cursor → off-hand placement plan. */
    private record PlacementResult(ItemStack newOffhand, ItemStack newCursor) {}

    /**
     * A reconstructed pre-drop main-hand stack, split at the stack-size cap.
     * {@code overflow} is null unless the merge exceeded the cap.
     */
    private record Reconstructed(ItemStack mainStack, ItemStack overflow) {}
}
