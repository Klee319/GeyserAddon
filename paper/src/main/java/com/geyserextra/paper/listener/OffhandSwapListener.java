package com.geyserextra.paper.listener;

import com.geyserextra.paper.util.BedrockPlayerUtil;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
        // Remove the dropped entity to avoid item duplication regardless of
        // how the server handles the cancellation below.
        event.getItemDrop().remove();
        event.setCancelled(true);

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
            merged.setAmount(currentMain.getAmount() + droppedItem.getAmount());
            return merged;
        }
        return droppedItem.clone();
    }
}
