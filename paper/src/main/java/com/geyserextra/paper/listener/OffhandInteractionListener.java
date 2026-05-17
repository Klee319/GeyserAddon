package com.geyserextra.paper.listener;

import com.geyserextra.paper.util.BedrockPlayerUtil;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Intercepts right-click interactions from Bedrock players to simulate
 * offhand item usage using a LOWEST+MONITOR 2-pass detection pattern.
 *
 * Why 2-pass: A single priority cannot determine whether another plugin
 * will handle the event. By recording the initial state at LOWEST and
 * comparing at MONITOR, we detect if any plugin (or vanilla) consumed
 * the interaction, and only then fire the offhand event on the next tick.
 *
 * Why next-tick scheduling: MONITOR handlers must not modify game state
 * directly. Scheduling ensures the offhand event fires outside the
 * original event's dispatch cycle.
 */
public final class OffhandInteractionListener implements Listener {

    private final org.bukkit.plugin.Plugin plugin;

    /**
     * Why HashMap (not WeakHashMap): All handlers run on the main thread
     * and the map is populated/consumed within the same event dispatch.
     * Entries are always removed in the MONITOR pass, so no leak occurs.
     * WeakHashMap would risk premature GC of keys during dispatch.
     */
    private final Map<PlayerInteractEvent, org.bukkit.event.Event.Result> initialStates =
        new HashMap<>();

    /**
     * Why EnumSet: O(1) lookup for known vanilla right-click materials.
     * These items have inherent right-click behavior that should not be
     * overridden by offhand simulation.
     */
    private static final Set<Material> VANILLA_RIGHT_CLICK_ITEMS = buildVanillaRightClickSet();

    /**
     * @param plugin the owning plugin instance
     * @throws NullPointerException if plugin is null
     */
    public OffhandInteractionListener(org.bukkit.plugin.Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    // ── Pass 1: LOWEST ──────────────────────────────────────────────

    /**
     * Records the initial useItemInHand() state before any other plugin
     * has a chance to modify it.
     *
     * Why LOWEST: This is the earliest priority, guaranteeing we capture
     * the unmodified event state for comparison in the MONITOR pass.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractLowest(PlayerInteractEvent event) {
        if (!isMainHandRightClick(event)) {
            return;
        }

        Player player = event.getPlayer();
        if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
            return;
        }

        if (isEmptyOrAir(player.getInventory().getItemInOffHand())) {
            return;
        }

        // Why: store the initial result so MONITOR can detect plugin changes
        initialStates.put(event, event.useItemInHand());
    }

    // ── Pass 2: MONITOR ─────────────────────────────────────────────

    /**
     * Compares the current useItemInHand() against the recorded initial
     * state. If no plugin changed it and no vanilla action applies,
     * schedules an offhand interaction on the next tick.
     *
     * Why MONITOR: This priority runs after all other handlers, giving
     * us the final state of the event without modifying it ourselves.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractMonitor(PlayerInteractEvent event) {
        org.bukkit.event.Event.Result initialResult = initialStates.remove(event);
        if (initialResult == null) {
            return;
        }

        // Why: if another plugin changed useItemInHand, it handled the event
        if (event.useItemInHand() != initialResult) {
            return;
        }

        // Why: if the result is not DEFAULT, vanilla or a plugin already decided
        if (initialResult != org.bukkit.event.Event.Result.DEFAULT) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();

        // Why: main hand item with vanilla action takes priority over offhand
        if (!isEmptyOrAir(mainHand)) {
            Material mainHandType = mainHand.getType();
            // Blocks only have right-click action when clicking another block (placement)
            boolean isBlockPlacement = mainHandType.isBlock()
                && event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK;
            if (isBlockPlacement || hasVanillaRightClickAction(mainHandType)) {
                return;
            }
        }

        // Why: interactable blocks (chests, furnaces) consume the click
        if (isInteractableBlockClick(event, player)) {
            return;
        }

        scheduleOffhandInteraction(player, event);
    }

    // ── Entity interaction ──────────────────────────────────────────

    /**
     * Handles right-click-on-entity for Bedrock players whose main hand
     * has no vanilla action but offhand holds an item.
     *
     * Why LOW priority: entity events lack the same 2-pass need because
     * we only check whether main hand has a vanilla action. If it does
     * not and offhand has an item, we fire the offhand entity event.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
            return;
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (!isEmptyOrAir(mainHand) && hasVanillaRightClickAction(mainHand.getType())) {
            return;
        }

        ItemStack offhandItem = player.getInventory().getItemInOffHand();
        if (isEmptyOrAir(offhandItem)) {
            return;
        }

        // Why: schedule on next tick to avoid modifying state during dispatch
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }

            PlayerInteractAtEntityEvent offhandEvent = new PlayerInteractAtEntityEvent(
                player,
                event.getRightClicked(),
                event.getClickedPosition(),
                EquipmentSlot.OFF_HAND
            );
            Bukkit.getPluginManager().callEvent(offhandEvent);
        });
    }

    // ── Offhand event scheduling ────────────────────────────────────

    /**
     * Schedules a PlayerInteractEvent with OFF_HAND on the next server tick.
     *
     * Why next tick: MONITOR must not alter game state. Deferring ensures
     * the synthetic event fires in a clean dispatch context.
     */
    private void scheduleOffhandInteraction(Player player, PlayerInteractEvent original) {
        Action action = original.getAction();
        Block clickedBlock = original.getClickedBlock();
        BlockFace blockFace = original.getBlockFace();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }

            ItemStack offhand = player.getInventory().getItemInOffHand();
            if (offhand.getType() == Material.AIR) {
                return;
            }

            PlayerInteractEvent offhandEvent = new PlayerInteractEvent(
                player, action, offhand, clickedBlock, blockFace, EquipmentSlot.OFF_HAND
            );
            Bukkit.getPluginManager().callEvent(offhandEvent);
        });
    }

    // ── Vanilla right-click detection ───────────────────────────────

    /**
     * Determines whether a material has an inherent vanilla right-click action.
     *
     * Why this check: if the main hand item would trigger a vanilla action
     * (eating, placing, shooting), the offhand should not override it.
     */
    static boolean hasVanillaRightClickAction(Material material) {
        if (material.isEdible()) {
            return true;
        }
        // Note: isBlock() check removed — block placement is handled separately
        // in the MONITOR handler based on whether the player clicked a block
        if (VANILLA_RIGHT_CLICK_ITEMS.contains(material)) {
            return true;
        }

        String name = material.name();
        return name.endsWith("_SPAWN_EGG")
            || name.contains("BOAT")
            || name.contains("MINECART")
            || name.contains("HELMET")
            || name.contains("CHESTPLATE")
            || name.contains("LEGGINGS")
            || name.contains("BOOTS");
    }

    // ── Utility methods ─────────────────────────────────────────────

    /**
     * Checks whether the event is a right-click action on the main hand slot.
     *
     * Why check HAND: Bukkit fires two events per click (one per hand).
     * We only intercept the main-hand event to avoid double-firing.
     */
    private boolean isMainHandRightClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        return (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)
            && event.getHand() == EquipmentSlot.HAND;
    }

    /**
     * Checks whether a clicked block is interactable and the player is not
     * sneaking (which bypasses block interaction in vanilla).
     *
     * Why: clicking a chest/furnace/etc. should open its UI, not trigger
     * offhand item usage.
     *
     * <p>{@code @SuppressWarnings("deprecation")} on Material#isInteractable():
     * Paper deprecated the {@code Material} accessor in favour of a
     * BlockType-based API that is still being stabilised. Reproducing the
     * vanilla "interactable" set by hand (every chest variant, every door,
     * every furnace, beacons, repeaters, ...) would be a much larger
     * undertaking with the same observable behaviour, so we intentionally
     * keep the deprecated call until Paper publishes a stable replacement.
     * The method is not marked {@code [removal]} as of Paper 1.21.x.</p>
     */
    @SuppressWarnings("deprecation")
    private boolean isInteractableBlockClick(PlayerInteractEvent event, Player player) {
        Block clickedBlock = event.getClickedBlock();
        return clickedBlock != null
            && clickedBlock.getType().isInteractable()
            && !player.isSneaking();
    }

    /**
     * Checks whether an ItemStack is null, air, or has zero amount.
     */
    private boolean isEmptyOrAir(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    /**
     * Builds the set of known vanilla materials with right-click actions.
     *
     * Why EnumSet: memory-efficient bitfield for enum constants with O(1)
     * contains(). Built once at class load to avoid repeated allocation.
     */
    private static Set<Material> buildVanillaRightClickSet() {
        Set<Material> set = EnumSet.noneOf(Material.class);

        // Why: these items all have inherent right-click behavior in vanilla
        String[] names = {
            "BOW", "CROSSBOW", "TRIDENT", "SHIELD", "FISHING_ROD",
            "SPYGLASS", "BRUSH", "FLINT_AND_STEEL", "GOAT_HORN",
            "ENDER_PEARL", "ENDER_EYE", "SNOWBALL", "EGG",
            "EXPERIENCE_BOTTLE", "FIREWORK_ROCKET",
            "BUCKET", "WATER_BUCKET", "LAVA_BUCKET", "POWDER_SNOW_BUCKET",
            "MILK_BUCKET", "PUFFERFISH_BUCKET", "SALMON_BUCKET",
            "COD_BUCKET", "TROPICAL_FISH_BUCKET", "AXOLOTL_BUCKET",
            "TADPOLE_BUCKET",
            "WRITABLE_BOOK", "WRITTEN_BOOK", "LEAD", "NAME_TAG",
            "BONE_MEAL", "ARMOR_STAND",
            "POTION", "SPLASH_POTION", "LINGERING_POTION",
            "HONEY_BOTTLE"
        };

        for (String name : names) {
            try {
                set.add(Material.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // Why: some materials may not exist in older server versions;
                // gracefully skip to maintain cross-version compatibility
            }
        }

        return set;
    }
}
