package com.geyserextra.paper.command;

import com.geyserextra.paper.util.BedrockPlayerUtil;
import com.geyserextra.paper.util.TranslationUtil;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays player statistics in a Floodgate SimpleForm organized by category.
 *
 * Why: Bedrock Edition has a different statistics screen that lacks the detail
 * of Java Edition. This command provides Bedrock players with a categorized
 * view of their gameplay statistics with human-friendly formatting.
 */
public final class StatisticsCommand implements CommandExecutor {

    /** Category display names for the top-level menu (Japanese). */
    private static final String[] CATEGORY_NAMES = {
        "一般",
        "モブ",
        "採掘",
        "クラフト"
    };

    /** Ticks per second for time conversion. */
    private static final int TICKS_PER_SECOND = 20;

    /** Seconds per minute. */
    private static final int SECONDS_PER_MINUTE = 60;

    /** Minutes per hour. */
    private static final int MINUTES_PER_HOUR = 60;

    /** Centimeters per meter for distance conversion. */
    private static final double CM_PER_METER = 100.0;

    /** Meters per kilometer for distance conversion. */
    private static final double METERS_PER_KM = 1000.0;

    private final Plugin plugin;

    /**
     * @param plugin the owning plugin instance for scheduling Bukkit tasks
     */
    public StatisticsCommand(Plugin plugin) {
        this.plugin = plugin;
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

        openCategoryMenu(player);
        return true;
    }

    /**
     * Opens the top-level category selection form.
     */
    private void openCategoryMenu(Player player) {
        SimpleForm.Builder builder = SimpleForm.builder()
            .title("統計情報")
            .content("カテゴリを選択してください");

        for (String name : CATEGORY_NAMES) {
            builder.button(name);
        }

        SimpleForm form = builder
            .validResultHandler(response -> {
                int buttonId = response.clickedButtonId();
                // Why runTask: statistics retrieval uses Bukkit API
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    try {
                        switch (buttonId) {
                            case 0 -> showGeneralStats(player);
                            case 1 -> showMobStats(player);
                            case 2 -> showMiningStats(player);
                            case 3 -> showCraftingStats(player);
                            default -> { /* no-op for invalid button */ }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "[Stats] Error showing category " + buttonId, e);
                    }
                });
            })
            .build();

        try {
            FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
        } catch (Exception e) {
            player.sendMessage(Component.text("フォームの表示に失敗しました。", NamedTextColor.RED));
        }
    }

    /**
     * Shows general gameplay statistics (deaths, kills, playtime, etc.).
     * Why grouped with section headers: Bedrock SimpleForm renders plain text,
     * so visual hierarchy via section color codes improves readability.
     */
    private void showGeneralStats(Player player) {
        List<String> lines = new ArrayList<>();

        lines.add(sectionHeader("戦闘"));
        lines.add(statLine("死亡数", formatNumber(
            player.getStatistic(Statistic.DEATHS))));
        lines.add(statLine("プレイヤーキル数", formatNumber(
            player.getStatistic(Statistic.PLAYER_KILLS))));
        lines.add(statLine("モブキル数", formatNumber(
            player.getStatistic(Statistic.MOB_KILLS))));

        lines.add("");
        lines.add(sectionHeader("移動"));
        lines.add(statLine("プレイ時間", formatTime(
            player.getStatistic(Statistic.PLAY_ONE_MINUTE))));
        lines.add(statLine("歩行距離", formatDistance(
            player.getStatistic(Statistic.WALK_ONE_CM))));
        lines.add(statLine("ダッシュ距離", formatDistance(
            player.getStatistic(Statistic.SPRINT_ONE_CM))));
        lines.add(statLine("水泳距離", formatDistance(
            player.getStatistic(Statistic.SWIM_ONE_CM))));
        lines.add(statLine("ジャンプ回数", formatNumber(
            player.getStatistic(Statistic.JUMP))));

        lines.add("");
        lines.add(sectionHeader("その他"));
        lines.add(statLine("与えたダメージ", formatHalfHearts(
            safeGetStatistic(player, Statistic.DAMAGE_DEALT))));
        lines.add(statLine("受けたダメージ", formatHalfHearts(
            safeGetStatistic(player, Statistic.DAMAGE_TAKEN))));
        lines.add(statLine("繁殖させた動物", formatNumber(
            safeGetStatistic(player, Statistic.ANIMALS_BRED))));
        lines.add(statLine("捨てたアイテム", formatNumber(
            safeGetStatistic(player, Statistic.DROP_COUNT))));

        showStatForm(player, "一般", lines);
    }

    /**
     * Shows mob kill statistics for all tracked mobs.
     * Why show zero-value entries: gives players a sense of progress
     * and shows which mobs are tracked by the system.
     */
    private void showMobStats(Player player) {
        EntityType[] mobs = {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER,
            EntityType.SPIDER, EntityType.ENDERMAN, EntityType.WITCH,
            EntityType.BLAZE, EntityType.GHAST, EntityType.SLIME,
            EntityType.PHANTOM, EntityType.DROWNED, EntityType.PILLAGER,
            EntityType.WARDEN, EntityType.WITHER, EntityType.ENDER_DRAGON
        };

        int totalKills = 0;
        List<String> lines = new ArrayList<>();

        for (EntityType mob : mobs) {
            int kills = getEntityStatistic(player, Statistic.KILL_ENTITY, mob);
            totalKills += kills;
            String mobName = TranslationUtil.renderJapanese(
                    Component.translatable(mob.translationKey()));
            lines.add(entityStatLine(mobName, kills));
        }

        List<String> result = new ArrayList<>();
        result.add(totalHeader("合計キル数", totalKills));
        result.add("");
        result.addAll(lines);

        showStatForm(player, "モブ", result);
    }

    /**
     * Shows mining statistics for common ores and blocks.
     * Why show all items: gives players a sense of progress for unvisited ores.
     */
    private void showMiningStats(Player player) {
        Material[] blocks = {
            Material.STONE, Material.DIRT, Material.SAND,
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.ANCIENT_DEBRIS, Material.OBSIDIAN,
            Material.NETHERRACK
        };

        int totalMined = 0;
        List<String> lines = new ArrayList<>();

        for (Material block : blocks) {
            int mined = getMaterialStatistic(player, Statistic.MINE_BLOCK, block);
            totalMined += mined;
            String blockName = TranslationUtil.renderJapanese(
                    Component.translatable(block.translationKey()));
            lines.add(materialStatLine(blockName, mined));
        }

        List<String> result = new ArrayList<>();
        result.add(totalHeader("合計採掘数", totalMined));
        result.add("");
        result.addAll(lines);

        showStatForm(player, "採掘", result);
    }

    /**
     * Shows crafting statistics for common items.
     * Why show all items: gives players a sense of progress for uncrafted items.
     */
    private void showCraftingStats(Player player) {
        Material[] items = {
            Material.CRAFTING_TABLE, Material.FURNACE, Material.CHEST,
            Material.TORCH, Material.STICK, Material.WOODEN_PICKAXE,
            Material.STONE_PICKAXE, Material.IRON_PICKAXE,
            Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE,
            Material.IRON_SWORD, Material.DIAMOND_SWORD,
            Material.IRON_AXE, Material.DIAMOND_AXE,
            Material.IRON_SHOVEL, Material.DIAMOND_SHOVEL,
            Material.SHIELD, Material.BOW, Material.ARROW,
            Material.BREAD, Material.GOLDEN_APPLE,
            Material.ENCHANTING_TABLE, Material.ANVIL,
            Material.RAIL, Material.POWERED_RAIL,
            Material.BUCKET, Material.COMPASS, Material.CLOCK
        };

        int totalCrafted = 0;
        List<String> lines = new ArrayList<>();

        for (Material item : items) {
            int crafted = getMaterialStatistic(player, Statistic.CRAFT_ITEM, item);
            totalCrafted += crafted;
            String itemName = TranslationUtil.renderJapanese(
                    Component.translatable(item.translationKey()));
            lines.add(materialStatLine(itemName, crafted));
        }

        List<String> result = new ArrayList<>();
        result.add(totalHeader("合計クラフト数", totalCrafted));
        result.add("");
        result.addAll(lines);

        showStatForm(player, "クラフト", result);
    }

    /**
     * Shows a statistics form with a back button to return to categories.
     */
    private void showStatForm(Player player, String title, List<String> lines) {
        String content = String.join("\n", lines);

        SimpleForm form = SimpleForm.builder()
            .title(title + " - 統計情報")
            .content(content)
            .button("戻る")
            .validResultHandler(response -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        openCategoryMenu(player);
                    }
                });
            })
            .build();

        try {
            FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
        } catch (Exception e) {
            player.sendMessage(Component.text("フォームの表示に失敗しました。", NamedTextColor.RED));
        }
    }

    // ================== Visual Formatting Helpers ==================

    /** Minecraft section sign for color codes. */
    private static final char COLOR_CODE = '\u00A7';

    /**
     * Builds a section header line for general stats grouping.
     * Why bold white: provides visual hierarchy in Bedrock SimpleForm.
     */
    private static String sectionHeader(String title) {
        return COLOR_CODE + "f" + COLOR_CODE + "l--- " + title
            + " ---" + COLOR_CODE + "r";
    }

    /**
     * Builds a label-value line with gray label and yellow value.
     * Why color separation: labels and values blend together without color.
     */
    private static String statLine(String label, String value) {
        return COLOR_CODE + "7" + label + ": "
            + COLOR_CODE + "e" + value + COLOR_CODE + "r";
    }

    /**
     * Builds a total count header with bold yellow number.
     * Why bold for total: draws attention to the aggregate count.
     */
    private static String totalHeader(String label, int total) {
        return COLOR_CODE + "7" + label + ": "
            + COLOR_CODE + "e" + COLOR_CODE + "l" + formatNumber(total)
            + COLOR_CODE + "r";
    }

    /**
     * Builds an entity stat line: white name + yellow value for non-zero,
     * dark gray name + dark gray zero for zero values.
     * Why show zeros in dark gray: indicates tracked mobs and progress.
     */
    private static String entityStatLine(String name, int value) {
        if (value > 0) {
            return COLOR_CODE + "f" + name + ": "
                + COLOR_CODE + "e" + formatNumber(value) + COLOR_CODE + "r";
        }
        return COLOR_CODE + "8" + name + ": 0" + COLOR_CODE + "r";
    }

    /**
     * Builds a material stat line: white name + yellow value for non-zero,
     * dark gray name + dark gray zero for zero values.
     * Why same pattern as entityStatLine: consistent visual language.
     */
    private static String materialStatLine(String name, int value) {
        if (value > 0) {
            return COLOR_CODE + "f" + name + ": "
                + COLOR_CODE + "e" + formatNumber(value) + COLOR_CODE + "r";
        }
        return COLOR_CODE + "8" + name + ": 0" + COLOR_CODE + "r";
    }

    // ================== Formatting Utilities ==================

    /**
     * Formats tick-based time values to "Xh Ym" format.
     * Why ticks: Minecraft stores play time in ticks (20 per second).
     */
    private String formatTime(int ticks) {
        int totalSeconds = ticks / TICKS_PER_SECOND;
        int hours = totalSeconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR);
        int minutes = (totalSeconds % (SECONDS_PER_MINUTE * MINUTES_PER_HOUR))
            / SECONDS_PER_MINUTE;
        return hours + "h " + minutes + "m";
    }

    /**
     * Formats centimeter-based distance values to "X.X km" or "X m" format.
     * Why centimeters: Minecraft stores distance in centimeters internally.
     */
    private String formatDistance(int cm) {
        double meters = cm / CM_PER_METER;
        if (meters >= METERS_PER_KM) {
            return String.format("%.1f km", meters / METERS_PER_KM);
        }
        return String.format("%.0f m", meters);
    }

    /**
     * Formats large numbers with comma separators for readability.
     */
    private static String formatNumber(int value) {
        return String.format("%,d", value);
    }

    /**
     * Formats half-heart damage values to heart-based display.
     * Why divide by 2: Minecraft damage is stored in half-hearts.
     */
    private String formatHalfHearts(int halfHearts) {
        return String.format("%.1f ハート", halfHearts / 2.0);
    }

    /**
     * Safely retrieves an untyped statistic, returning 0 on failure.
     * Why try-catch: some statistics require additional parameters (entity/material)
     * and will throw IllegalArgumentException when called without them.
     */
    private int safeGetStatistic(Player player, Statistic stat) {
        try {
            return player.getStatistic(stat);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /**
     * Safely retrieves an entity-typed statistic, returning 0 on failure.
     * Why try-catch: some entity types may not be valid for certain statistics.
     */
    private int getEntityStatistic(Player player, Statistic stat, EntityType type) {
        try {
            return player.getStatistic(stat, type);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /**
     * Safely retrieves a material-typed statistic, returning 0 on failure.
     * Why try-catch: some materials may not be valid for certain statistics.
     */
    private int getMaterialStatistic(Player player, Statistic stat, Material material) {
        try {
            return player.getStatistic(stat, material);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }
}
