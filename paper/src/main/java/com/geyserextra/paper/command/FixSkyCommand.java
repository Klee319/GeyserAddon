package com.geyserextra.paper.command;

import com.geyserextra.paper.GeyserExtraPaper;
import com.geyserextra.paper.util.BedrockPlayerUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Manual escape hatch for a Bedrock sky stuck in the wrong dimension.
 *
 * <p>The automatic repair in {@code DimensionHandoffListener} only sees joins;
 * a sky that gets stuck mid-session — a badly timed portal transit inside one
 * backend — has no event to hook. This command lets the player run the same
 * dimension round-trip themselves instead of having to relog.</p>
 */
public final class FixSkyCommand implements CommandExecutor {

    private final GeyserExtraPaper plugin;

    public FixSkyCommand(GeyserExtraPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("このコマンドはプレイヤー専用です。", NamedTextColor.RED));
            return true;
        }
        if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
            player.sendMessage(Component.text("このコマンドは統合版プレイヤー専用です。", NamedTextColor.RED));
            return true;
        }
        var juggleService = plugin.getDimensionJuggleService();
        if (juggleService == null) {
            player.sendMessage(Component.text(
                "この機能はサーバー設定で無効化されています。", NamedTextColor.RED));
            return true;
        }
        juggleService.juggle(player);
        return true;
    }
}
