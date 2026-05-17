package com.geyserextra.paper.command;

import com.geyserextra.paper.util.BedrockPlayerUtil;
import com.geyserextra.paper.util.TranslationUtil;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Map;

/**
 * Shows held item details in a Floodgate SimpleForm panel for Bedrock players.
 *
 * Why SimpleForm instead of BossBar: BossBar display had timing and visibility
 * issues on Bedrock clients. A one-shot SimpleForm provides a cleaner,
 * touch-friendly way to inspect item details without persistent UI clutter.
 */
public final class TooltipCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(
                "This command can only be used by players.", NamedTextColor.RED));
            return true;
        }

        if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
            player.sendMessage(Component.text(
                "This command is only available for Bedrock players.", NamedTextColor.RED));
            return true;
        }

        showItemTooltip(player);
        return true;
    }

    /**
     * Builds and sends a SimpleForm displaying the held item's details.
     *
     * Why we read the item synchronously: this method is called from the
     * main thread (command execution), so accessing player inventory is safe.
     */
    private void showItemTooltip(Player player) {
        ItemStack heldItem = player.getInventory().getItemInMainHand();

        if (heldItem.getType() == Material.AIR) {
            player.sendMessage(Component.text(
                "手に何も持っていません。", NamedTextColor.YELLOW));
            return;
        }

        String content = buildTooltipContent(heldItem);

        SimpleForm form = SimpleForm.builder()
            .title("アイテム詳細")
            .content(content)
            .button("閉じる")
            .build();

        try {
            FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
        } catch (Exception e) {
            player.sendMessage(Component.text("フォームの表示に失敗しました。", NamedTextColor.RED));
        }
    }

    /**
     * Builds the tooltip content string from an ItemStack's properties.
     *
     * Includes: item ID, durability (if applicable), stack size,
     * enchantments, and custom display name.
     */
    private String buildTooltipContent(ItemStack item) {
        StringBuilder sb = new StringBuilder();

        try {
            // Item name rendered in Japanese via translation key
            String itemName = TranslationUtil.renderJapanese(
                    Component.translatable(item.getType().translationKey()));
            sb.append("アイテム名: ").append(itemName).append("\n");
        } catch (Exception e) {
            sb.append("アイテム名: ").append(item.getType().name()).append("\n");
        }

        // Item ID (e.g., minecraft:diamond_pickaxe)
        sb.append("アイテムID: ")
            .append(item.getType().getKey().toString())
            .append("\n");

        // Stack size
        sb.append("個数: ")
            .append(item.getAmount())
            .append("\n");

        // Durability (only for Damageable items)
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable damageable) {
            int maxDurability = damageable.hasMaxDamage()
                ? damageable.getMaxDamage()
                : item.getType().getMaxDurability();
            if (maxDurability > 0) {
                int remaining = maxDurability - damageable.getDamage();
                sb.append("耐久値: ")
                    .append(remaining)
                    .append("/")
                    .append(maxDurability)
                    .append("\n");
            }
        }

        // Custom name (if any)
        if (meta != null && meta.hasDisplayName()) {
            try {
                Component displayName = meta.displayName();
                if (displayName != null) {
                    String nameText = TranslationUtil.renderJapanese(displayName);
                    sb.append("カスタム名: ").append(nameText).append("\n");
                }
            } catch (Exception e) {
                sb.append("カスタム名: (表示不可)\n");
            }
        }

        // Lore (if any) — important for custom items
        if (meta != null && meta.hasLore()) {
            java.util.List<Component> lore = meta.lore();
            if (lore != null && !lore.isEmpty()) {
                sb.append("\n説明:\n");
                for (Component line : lore) {
                    try {
                        sb.append("  ").append(TranslationUtil.renderJapanese(line)).append("\n");
                    } catch (Exception e) {
                        sb.append("  (表示不可)\n");
                    }
                }
            }
        }

        // Custom Model Data (if any) — useful for identifying custom items
        if (meta != null && meta.hasCustomModelData()) {
            sb.append("カスタムモデル: ").append(meta.getCustomModelData()).append("\n");
        }

        // Enchantments (if any) — with safe rendering
        Map<Enchantment, Integer> enchantments = item.getEnchantments();
        if (!enchantments.isEmpty()) {
            sb.append("\nエンチャント:\n");
            for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                try {
                    String enchantName = TranslationUtil.renderJapanese(
                            Component.translatable(entry.getKey()));
                    sb.append("  ").append(enchantName)
                        .append(" Lv.").append(entry.getValue()).append("\n");
                } catch (Exception e) {
                    // Fallback for custom enchantments or deprecated API
                    sb.append("  ").append(entry.getKey().getKey().toString())
                        .append(" Lv.").append(entry.getValue()).append("\n");
                }
            }
        }

        // Stored enchantments (for enchanted books)
        if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta storageMeta) {
            Map<Enchantment, Integer> storedEnchants = storageMeta.getStoredEnchants();
            if (!storedEnchants.isEmpty()) {
                sb.append("\n保存エンチャント:\n");
                for (Map.Entry<Enchantment, Integer> entry : storedEnchants.entrySet()) {
                    try {
                        String enchantName = TranslationUtil.renderJapanese(
                                Component.translatable(entry.getKey()));
                        sb.append("  ").append(enchantName)
                            .append(" Lv.").append(entry.getValue()).append("\n");
                    } catch (Exception e) {
                        sb.append("  ").append(entry.getKey().getKey().toString())
                            .append(" Lv.").append(entry.getValue()).append("\n");
                    }
                }
            }
        }

        return sb.toString();
    }
}
