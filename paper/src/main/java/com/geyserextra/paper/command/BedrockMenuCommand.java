package com.geyserextra.paper.command;

import com.geyserextra.paper.settings.PlayerSettings;
import com.geyserextra.paper.settings.PlayerSettings.EnvironmentDisplayMode;
import com.geyserextra.paper.settings.PlayerSettingsManager;
import com.geyserextra.paper.util.BedrockFormSender;
import com.geyserextra.paper.util.BedrockPlayerUtil;
import com.geyserextra.paper.util.TranslationUtil;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.SimpleForm;

/**
 * Opens a Floodgate SimpleForm menu with buttons for all Bedrock commands.
 *
 * Why: Bedrock players are more accustomed to touch/controller-friendly menus
 * than typing chat commands. This central menu provides quick access to all
 * Bedrock-specific features without memorizing individual commands.
 *
 * When biome or light level display is set to MENU mode, the menu content
 * area shows the current values as a convenient one-shot information display.
 */
public final class BedrockMenuCommand implements CommandExecutor {

    /** Menu entries: label displayed on button + command to execute. */
    private record MenuEntry(String label, String command) {}

    private static final MenuEntry[] MENU_ENTRIES = {
        new MenuEntry("オフハンド入替", "offhand"),
        new MenuEntry("ツールチップ表示", "tooltip"),
        new MenuEntry("進捗一覧", "advancements"),
        new MenuEntry("統計情報", "stats"),
        new MenuEntry("表示設定", "settings")
    };

    private final Plugin plugin;
    private final PlayerSettingsManager settingsManager;

    /**
     * @param plugin          the owning plugin instance for scheduling Bukkit tasks
     * @param settingsManager manages per-player display settings for menu info display
     */
    public BedrockMenuCommand(Plugin plugin, PlayerSettingsManager settingsManager) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
    }

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

        openMenu(player);
        return true;
    }

    /**
     * Builds and sends the main menu form to the Bedrock player.
     *
     * Why dynamic content: When biome or light level display is set to MENU mode,
     * the player expects to see that information when opening the menu. The content
     * area of the SimpleForm is used for this one-shot display, keeping the buttons
     * clean and focused on actions.
     */
    private void openMenu(Player player) {
        String content = buildMenuContent(player);

        SimpleForm.Builder builder = SimpleForm.builder()
            .title("GeyserExtra メニュー")
            .content(content);

        for (MenuEntry entry : MENU_ENTRIES) {
            builder.button(entry.label());
        }

        SimpleForm form = builder
            .validResultHandler(response -> {
                int buttonId = response.clickedButtonId();
                if (buttonId >= 0 && buttonId < MENU_ENTRIES.length) {
                    String cmd = MENU_ENTRIES[buttonId].command();
                    // Why runTask: Form response handlers execute on Netty thread,
                    // but performCommand requires the Bukkit main thread
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.performCommand(cmd);
                        }
                    });
                }
            })
            .build();

        BedrockFormSender.send(plugin, player, form, "main menu");
    }

    /**
     * Builds the menu content string, including biome and/or light level info
     * when the player has those displays set to MENU mode.
     *
     * Why in content area: SimpleForm buttons are for actions; the content area
     * is the natural place for informational text. This avoids cluttering the
     * button list with non-actionable items.
     *
     * @param player the player opening the menu
     * @return the content string for the SimpleForm
     */
    private String buildMenuContent(Player player) {
        PlayerSettings settings = settingsManager.getSettings(player.getUniqueId());
        StringBuilder content = new StringBuilder("使用したい機能を選択してください");

        boolean showBiome = settings.getBiomeDisplay() == EnvironmentDisplayMode.MENU;
        boolean showLight = settings.getLightLevelDisplay() == EnvironmentDisplayMode.MENU;

        if (showBiome || showLight) {
            content.append("\n\n");
            Block block = player.getLocation().getBlock();

            if (showBiome) {
                String biomeKeyValue = block.getBiome().getKey().value();
                String translationKey = "biome.minecraft." + biomeKeyValue;
                String biomeName = TranslationUtil.renderJapanese(
                    Component.translatable(translationKey));
                content.append("バイオーム: ").append(biomeName);

                if (showLight) {
                    content.append("\n");
                }
            }

            if (showLight) {
                content.append("明るさ: ").append(block.getLightLevel());
            }
        }

        return content.toString();
    }
}
