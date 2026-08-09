package com.geyserextra.paper.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.Form;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Sends a Cumulus form to a Bedrock player and reports delivery failures.
 *
 * Why this exists: every command that opens a form used to call
 * {@code FloodgateApi.getInstance().sendForm(uuid, form)} and discard the
 * boolean it returns. Floodgate returns {@code false} when it has no Bedrock
 * session for that UUID — which is precisely what happens behind a Velocity
 * proxy if the backend's Floodgate never saw the handshake. The command then
 * looks entirely healthy from the server's side: it is logged as issued, no
 * exception is thrown, and the player is told nothing. That is the exact
 * shape of "the Bedrock commands don't work" reports, and it was invisible
 * because the one signal Floodgate gives was thrown away.
 *
 * Every send now goes through here so the failure is loud in the log and
 * visible to the player instead of silent.
 */
public final class BedrockFormSender {

    private BedrockFormSender() {
    }

    /**
     * Sends {@code form} to {@code player}, logging and notifying on failure.
     *
     * @param plugin   owning plugin, used for its logger
     * @param player   the Bedrock player to deliver to
     * @param form     the form to send
     * @param formName short human name of the form, used in the warning
     * @return true when Floodgate accepted the form for delivery
     */
    public static boolean send(Plugin plugin, Player player, Form form, String formName) {
        boolean accepted;
        try {
            accepted = FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
        } catch (RuntimeException ex) {
            // A throwing sendForm was previously swallowed by the caller's
            // absence of any handling at all, taking the whole command with it.
            plugin.getLogger().warning("[Form] Sending the " + formName + " form to "
                + player.getName() + " threw " + ex.getClass().getSimpleName()
                + ": " + ex.getMessage());
            player.sendMessage(Component.text(
                "画面を開けませんでした。管理者に連絡してください。", NamedTextColor.RED));
            return false;
        }

        if (!accepted) {
            plugin.getLogger().warning("[Form] Floodgate refused the " + formName
                + " form for " + player.getName() + " (uuid=" + player.getUniqueId()
                + "); it has no Bedrock session bound to that UUID on this server.");
            player.sendMessage(Component.text(
                "画面を開けませんでした。管理者に連絡してください。", NamedTextColor.RED));
            return false;
        }

        DebugLog.log(plugin.getLogger(),
            () -> "Sent the " + formName + " form to " + player.getName()
                + " (uuid=" + player.getUniqueId() + ")");
        return true;
    }
}
