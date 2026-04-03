package com.geyserextra.paper.command;

import com.geyserextra.paper.util.BedrockPlayerUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Swaps items between the offhand and hotbar for Bedrock players.
 *
 * Why: Bedrock Edition lacks a native key binding for offhand swap (F key on Java).
 * This command provides an equivalent mechanism so Bedrock players can use
 * shields, totems, and other offhand items without a UI workaround.
 *
 * Logic:
 * - If hotbar slot 8 (rightmost) has an item, swap it with the offhand slot.
 * - If hotbar slot 8 is empty, swap the player's current main hand item instead.
 */
public final class OffhandCommand implements CommandExecutor {

    /** Hotbar slot index for the rightmost slot (0-based). */
    private static final int HOTBAR_SLOT_RIGHTMOST = 8;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return true;
        }

        if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
            player.sendMessage(Component.text("This command is only available for Bedrock players.", NamedTextColor.RED));
            return true;
        }

        performOffhandSwap(player);
        return true;
    }

    /**
     * Performs the offhand swap for the given player.
     *
     * Why slot 8 priority: Bedrock players commonly place items in the rightmost
     * hotbar slot for quick offhand transfer, mimicking the Bedrock UI pattern.
     */
    private void performOffhandSwap(Player player) {
        PlayerInventory inventory = player.getInventory();

        ItemStack slot8Item = inventory.getItem(HOTBAR_SLOT_RIGHTMOST);
        ItemStack offhandItem = inventory.getItemInOffHand();

        if (slot8Item != null && !slot8Item.getType().isAir()) {
            // Swap slot 8 with offhand
            inventory.setItem(HOTBAR_SLOT_RIGHTMOST, offhandItem);
            inventory.setItemInOffHand(slot8Item);
        } else {
            // Swap main hand with offhand
            ItemStack mainHandItem = inventory.getItemInMainHand();
            inventory.setItemInMainHand(offhandItem);
            inventory.setItemInOffHand(mainHandItem);
        }

        // Why: Bedrock clients may not reflect server-side inventory changes without an explicit sync
        player.updateInventory();
    }
}
