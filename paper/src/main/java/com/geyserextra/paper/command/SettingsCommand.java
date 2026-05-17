package com.geyserextra.paper.command;

import com.geyserextra.paper.settings.PlayerSettings;
import com.geyserextra.paper.settings.PlayerSettings.EnvironmentDisplayMode;
import com.geyserextra.paper.settings.PlayerSettings.EntityDisplayMode;
import com.geyserextra.paper.settings.PlayerSettingsManager;
import com.geyserextra.paper.util.BedrockPlayerUtil;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Opens a Floodgate CustomForm for toggling per-player display settings.
 *
 * Why: Bedrock players cannot use Java Edition's F3 debug screen or chat-based
 * configuration workflows. A native Floodgate form provides an intuitive,
 * touch/controller-friendly interface for managing biome, light level,
 * chunk boundary, and entity information displays.
 */
public final class SettingsCommand implements CommandExecutor {

    /** Dropdown options for environment-related displays (biome, light level). */
    private static final List<String> ENVIRONMENT_OPTIONS = List.of("オフ", "ボスバー", "メニュー表示");

    /** Dropdown options for entity information display. */
    private static final List<String> ENTITY_OPTIONS = List.of("オフ", "ボスバー", "テキストディスプレイ");

    private final Plugin plugin;
    private final PlayerSettingsManager settingsManager;
    private final Consumer<Player> onSettingsChanged;

    /**
     * @param plugin             the owning plugin instance for scheduling Bukkit tasks
     * @param settingsManager    manages per-player display settings persistence
     * @param onSettingsChanged  callback invoked after settings are saved, for immediate
     *                           display update (e.g., removing disabled BossBars instantly)
     */
    public SettingsCommand(Plugin plugin, PlayerSettingsManager settingsManager,
                           Consumer<Player> onSettingsChanged) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
        this.onSettingsChanged = onSettingsChanged;
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

        openSettingsForm(player);
        return true;
    }

    /**
     * Builds and sends a CustomForm populated with the player's current settings.
     *
     * Why CustomForm: Unlike SimpleForm (buttons only), CustomForm supports
     * dropdowns and toggles, which map naturally to multi-option enum settings
     * and boolean flags.
     */
    private void openSettingsForm(Player player) {
        PlayerSettings current = settingsManager.getSettings(player.getUniqueId());

        CustomForm form = CustomForm.builder()
            .title("表示設定")
            .dropdown("バイオーム表示", ENVIRONMENT_OPTIONS,
                toEnvironmentIndex(current.getBiomeDisplay()))
            .dropdown("明るさレベル表示", ENVIRONMENT_OPTIONS,
                toEnvironmentIndex(current.getLightLevelDisplay()))
            .toggle("チャンク境界表示", current.isChunkBoundaryDisplay())
            .dropdown("エンティティ情報表示", ENTITY_OPTIONS,
                toEntityIndex(current.getEntityDisplay()))
            .toggle("アイテムLore注入(耐久値・オーバーエンチャント)",
                current.isLoreTooltipEnabled())
            .validResultHandler(response -> {
                EnvironmentDisplayMode biomeMode = fromEnvironmentIndex(response.asDropdown(0));
                EnvironmentDisplayMode lightMode = fromEnvironmentIndex(response.asDropdown(1));
                boolean chunkBoundary = response.asToggle(2);
                EntityDisplayMode entityMode = fromEntityIndex(response.asDropdown(3));
                boolean loreTooltip = response.asToggle(4);

                PlayerSettings newSettings = current
                    .withBiomeDisplay(biomeMode)
                    .withLightLevelDisplay(lightMode)
                    .withChunkBoundaryDisplay(chunkBoundary)
                    .withEntityDisplay(entityMode)
                    .withLoreTooltipEnabled(loreTooltip);

                // Why runTask: Form response handlers execute on the Netty I/O thread,
                // but settings persistence and player messaging must happen on the
                // Bukkit main thread to avoid concurrent modification issues.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    settingsManager.updateSettings(player.getUniqueId(), newSettings);
                    onSettingsChanged.accept(player);
                    player.sendMessage(Component.text(
                        "設定を保存しました", NamedTextColor.GREEN));
                });
            })
            .build();

        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
    }

    // ── Index conversion helpers ────────────────────────────────────────

    /**
     * Converts an EnvironmentDisplayMode enum to its corresponding dropdown index.
     *
     * Why explicit mapping: The dropdown index order (OFF=0, BOSSBAR=1, MENU=2)
     * must match the Japanese label array. Using ordinal() would be fragile if
     * the enum declaration order changes.
     */
    private static int toEnvironmentIndex(EnvironmentDisplayMode mode) {
        return switch (mode) {
            case OFF -> 0;
            case BOSSBAR -> 1;
            case MENU -> 2;
        };
    }

    /**
     * Converts a dropdown index back to an EnvironmentDisplayMode enum.
     */
    private static EnvironmentDisplayMode fromEnvironmentIndex(int index) {
        return switch (index) {
            case 1 -> EnvironmentDisplayMode.BOSSBAR;
            case 2 -> EnvironmentDisplayMode.MENU;
            default -> EnvironmentDisplayMode.OFF;
        };
    }

    /**
     * Converts an EntityDisplayMode enum to its corresponding dropdown index.
     *
     * Why explicit mapping: Same rationale as toEnvironmentIndex — decouples
     * enum declaration order from UI presentation order.
     */
    private static int toEntityIndex(EntityDisplayMode mode) {
        return switch (mode) {
            case OFF -> 0;
            case BOSSBAR -> 1;
            case TEXT_DISPLAY -> 2;
        };
    }

    /**
     * Converts a dropdown index back to an EntityDisplayMode enum.
     */
    private static EntityDisplayMode fromEntityIndex(int index) {
        return switch (index) {
            case 1 -> EntityDisplayMode.BOSSBAR;
            case 2 -> EntityDisplayMode.TEXT_DISPLAY;
            default -> EntityDisplayMode.OFF;
        };
    }
}
