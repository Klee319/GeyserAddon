package com.geyserextra.paper.command;

import com.geyserextra.paper.util.BedrockFormSender;
import com.geyserextra.paper.util.BedrockPlayerUtil;
import com.geyserextra.paper.util.TranslationUtil;

import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.SimpleForm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows Minecraft advancements in a Floodgate SimpleForm organized by category.
 *
 * Why: Bedrock Edition does not have an advancement screen. This command
 * provides Bedrock players with a touch-friendly way to view their progress
 * through the vanilla advancement tree.
 *
 * Uses Paper API's AdvancementDisplay to retrieve localization-ready titles
 * and descriptions, so advancement names render correctly for all client
 * languages (including Japanese).
 */
public final class AdvancementCommand implements CommandExecutor {

    /** Advancement category prefixes mapped to Japanese display names. */
    private static final Map<String, String> CATEGORIES = new LinkedHashMap<>();

    /** Filled segment character for progress bar display (Bedrock-safe ASCII). */
    private static final char PROGRESS_FILLED = '|';

    /** Empty segment character for progress bar display (Bedrock-safe ASCII). */
    private static final char PROGRESS_EMPTY = '.';

    /** Maximum number of segments in the progress bar to keep it compact. */
    private static final int MAX_PROGRESS_BAR_LENGTH = 10;

    static {
        CATEGORIES.put("minecraft:story/", "物語");
        CATEGORIES.put("minecraft:nether/", "ネザー");
        CATEGORIES.put("minecraft:end/", "エンド");
        CATEGORIES.put("minecraft:adventure/", "冒険");
        CATEGORIES.put("minecraft:husbandry/", "農業");
    }

    private final Plugin plugin;

    /**
     * @param plugin the owning plugin instance for scheduling Bukkit tasks
     */
    public AdvancementCommand(Plugin plugin) {
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
            .title("進捗一覧")
            .content("カテゴリを選択してください");

        List<String> categoryPrefixes = new ArrayList<>(CATEGORIES.keySet());
        for (String prefix : categoryPrefixes) {
            builder.button(CATEGORIES.get(prefix));
        }

        SimpleForm form = builder
            .validResultHandler(response -> {
                int buttonId = response.clickedButtonId();
                if (buttonId >= 0 && buttonId < categoryPrefixes.size()) {
                    String selectedPrefix = categoryPrefixes.get(buttonId);
                    String categoryName = CATEGORIES.get(selectedPrefix);
                    // Why runTask: collectAdvancements accesses Bukkit API
                    // which must run on the main thread
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            openAdvancementList(player, selectedPrefix, categoryName);
                        }
                    });
                }
            })
            .build();

        BedrockFormSender.send(plugin, player, form, "advancement categories");
    }

    /**
     * Opens a form listing all advancements in the specified category
     * with the player's completion status.
     *
     * Why: Bedrock SimpleForm supports Minecraft section-sign color codes,
     * so we use them to create visual hierarchy between completed/incomplete
     * items and to make progress information easy to scan at a glance.
     */
    private void openAdvancementList(Player player, String prefix, String categoryName) {
        List<AdvancementInfo> advancements = collectAdvancements(player, prefix);

        if (advancements.isEmpty()) {
            player.sendMessage(Component.text(
                "No advancements found in this category.", NamedTextColor.YELLOW));
            return;
        }

        // Why: sort incomplete first so players immediately see what to work on
        advancements.sort((a, b) -> Boolean.compare(a.done(), b.done()));

        long completedCount = advancements.stream().filter(AdvancementInfo::done).count();

        StringBuilder content = new StringBuilder();
        // Why: summary header gives an at-a-glance overview of category progress
        content.append("\u00A7e\u9054\u6210\u6E08\u307F: ")
            .append(completedCount)
            .append("/")
            .append(advancements.size())
            .append("\u00A7r\n\n");

        for (AdvancementInfo info : advancements) {
            content.append(info.formattedStatusIcon())
                .append(" \u00A7f")
                .append(info.displayName())
                .append("\u00A7r");

            if (info.totalCriteria() > 1) {
                content.append(" ")
                    .append(buildProgressBar(info.completedCriteria(), info.totalCriteria()))
                    .append(" \u00A77(")
                    .append(info.completedCriteria())
                    .append("/")
                    .append(info.totalCriteria())
                    .append(")\u00A7r");
            }

            // Why: show description on a separate indented line for readability
            if (info.description() != null && !info.description().isEmpty()) {
                content.append("\n   \u00A77")
                    .append(info.description())
                    .append("\u00A7r");
            }

            content.append("\n\n");
        }

        SimpleForm form = SimpleForm.builder()
            .title(categoryName + " - 進捗")
            .content(content.toString())
            .button("戻る")
            .validResultHandler(response -> {
                // Why runTask: openCategoryMenu calls Bukkit APIs internally
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        openCategoryMenu(player);
                    }
                });
            })
            .build();

        BedrockFormSender.send(plugin, player, form, "advancement list");
    }

    /**
     * Builds a visual progress bar using filled/empty square characters.
     *
     * Why: a graphical bar is far easier to parse than raw numbers,
     * especially on the small Bedrock touch-screen UI.
     *
     * @param completed number of criteria completed
     * @param total total number of criteria
     * @return a color-coded progress bar string, e.g. "§e[|||..]§r"
     */
    private static String buildProgressBar(int completed, int total) {
        // Why: cap bar length at 10 to avoid overly wide bars for
        // advancements with many criteria (e.g., "Two by Two" has 20+)
        int barLength = Math.min(total, MAX_PROGRESS_BAR_LENGTH);
        int filledLength = (total > 0)
            ? (int) Math.round((double) completed / total * barLength)
            : 0;

        StringBuilder bar = new StringBuilder("\u00A7e[");
        for (int i = 0; i < barLength; i++) {
            bar.append(i < filledLength ? PROGRESS_FILLED : PROGRESS_EMPTY);
        }
        bar.append("]\u00A7r");
        return bar.toString();
    }

    /**
     * Collects advancement information for a given category prefix.
     *
     * Uses Paper API's AdvancementDisplay to retrieve localized titles and
     * descriptions. Advancements without a display (e.g., recipes) are
     * skipped as they are not meaningful progress indicators.
     */
    private List<AdvancementInfo> collectAdvancements(Player player, String prefix) {
        List<AdvancementInfo> result = new ArrayList<>();
        Iterator<Advancement> iterator = Bukkit.advancementIterator();

        while (iterator.hasNext()) {
            Advancement advancement = iterator.next();
            String key = advancement.getKey().toString();

            if (!key.startsWith(prefix) || key.contains("recipes/")) {
                continue;
            }

            // Why: advancements without a display entry are internal or recipes
            // and should not appear in the player-facing list
            AdvancementDisplay display = advancement.getDisplay();
            if (display == null) {
                continue;
            }

            String title = TranslationUtil.renderJapanese(display.title());
            String description = TranslationUtil.renderJapanese(display.description());

            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            Collection<String> remaining = progress.getRemainingCriteria();
            Collection<String> awarded = progress.getAwardedCriteria();

            int total = remaining.size() + awarded.size();
            int completed = awarded.size();
            boolean done = progress.isDone();

            result.add(new AdvancementInfo(title, description, done, completed, total));
        }

        return result;
    }

    /**
     * Holds advancement display information for a single advancement.
     *
     * @param displayName the localized advancement title
     * @param description the localized advancement description (may be null)
     * @param done whether the advancement is fully completed
     * @param completedCriteria number of criteria completed
     * @param totalCriteria total number of criteria
     */
    private record AdvancementInfo(
        String displayName,
        String description,
        boolean done,
        int completedCriteria,
        int totalCriteria
    ) {
        /**
         * Returns a color-coded status icon for Bedrock SimpleForm display.
         *
         * Why: plain checkmark/cross symbols are hard to distinguish on
         * Bedrock's UI; Minecraft color codes add clear visual contrast.
         *
         * @return green [O] for completed, red [X] for incomplete
         */
        String formattedStatusIcon() {
            return done
                ? "\u00A7a[O]\u00A7r"
                : "\u00A7c[X]\u00A7r";
        }
    }
}
