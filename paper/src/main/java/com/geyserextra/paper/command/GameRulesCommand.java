package com.geyserextra.paper.command;

import com.geyserextra.paper.util.BedrockPlayerUtil;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays all game rules for the player's current world in a Floodgate SimpleForm.
 *
 * Why: Bedrock Edition does not provide a convenient way to view all game rules
 * at once. This command lets Bedrock players see the current world's game rule
 * configuration without needing operator permissions or typing individual commands.
 */
public final class GameRulesCommand implements CommandExecutor {

    private final Plugin plugin;

    /**
     * @param plugin the owning plugin instance for scheduling Bukkit tasks
     */
    public GameRulesCommand(Plugin plugin) {
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

        showGameRules(player);
        return true;
    }

    /**
     * Builds and sends a form displaying all game rules grouped by type.
     *
     * Why group by type: boolean and integer rules serve different purposes.
     * Grouping makes it easier to scan the list and find relevant settings.
     */
    private void showGameRules(Player player) {
        World world = player.getWorld();
        String worldName = world.getName();

        List<String> booleanRules = new ArrayList<>();
        List<String> integerRules = new ArrayList<>();

        // Why suppress: GameRule.values() and getName() are marked for removal
        // in Paper 1.21+ but no stable replacement exists yet in Bukkit API.
        // Using the typed GameRule API ensures correct value retrieval.
        @SuppressWarnings("removal")
        GameRule<?>[] allRules = GameRule.values();
        for (GameRule<?> gameRule : allRules) {
            // Why try-catch: GameRule.values() exposes every rule Bukkit knows about,
            // but the underlying NMS GameRules instance for a given world may not
            // contain entries for rules added in newer versions or removed/relocated
            // in custom worlds. world.getGameRuleValue() throws IllegalArgumentException
            // for such rules. Skip them so a single missing rule does not break the form.
            Object value;
            try {
                value = world.getGameRuleValue(gameRule);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (value == null) {
                continue;
            }

            @SuppressWarnings("removal")
            String ruleName = gameRule.getName();
            String line = ruleName + ": " + value;

            if (value instanceof Boolean) {
                booleanRules.add(line);
            } else {
                integerRules.add(line);
            }
        }

        StringBuilder content = new StringBuilder();
        content.append("World: ").append(worldName).append("\n\n");

        content.append("=== Boolean Rules ===\n");
        for (String rule : booleanRules) {
            content.append(rule).append("\n");
        }

        content.append("\n=== Integer Rules ===\n");
        for (String rule : integerRules) {
            content.append(rule).append("\n");
        }

        SimpleForm form = SimpleForm.builder()
            .title("ワールド設定 - " + worldName)
            .content(content.toString())
            .button("閉じる")
            .build();

        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
    }
}
