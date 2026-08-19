package com.geyserextra.paper.durability;

import com.geyserextra.paper.util.BedrockPlayerUtil;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Makes the durability bar of a custom-durability item read correctly on
 * Bedrock, by rescaling the damage value in the packets sent to Bedrock players.
 *
 * <p><b>Why the bar is wrong.</b> Bedrock has no per-stack maximum durability.
 * The client draws the bar from the maximum baked into its own item type and the
 * {@code damage} value the server sent. Java, since data components, lets a
 * plugin override {@code minecraft:max_damage} per item stack — and TrinityForge
 * does, deriving it from the item's rolled stats and its enchantments
 * ({@code ItemAssembler.resolveEffectiveDurability}). Geyser forwards
 * {@code damage} unchanged and has nowhere to put the custom maximum, so a
 * diamond sword given 3000 durability drains its bar against Bedrock's 1561: the
 * bar hits red while more than half the real durability is left.</p>
 *
 * <p><b>Why rescaling and not a component.</b> Geyser's v2 custom item API does
 * expose {@code JavaItemDataComponents.MAX_DAMAGE}, but a custom item definition
 * is static — one value for every stack that matches the definition. Here the
 * maximum differs between two otherwise identical swords because their stat roll
 * or their enchantments differ, so no single value is correct. Rescaling the
 * damage instead keeps the <em>ratio</em> right, which is the only thing the bar
 * actually draws:</p>
 *
 * <pre>{@code
 *   1 - sentDamage / vanillaMax  ==  1 - realDamage / realMax
 * }</pre>
 *
 * <p><b>What this does not touch.</b> Only the outbound packet is rewritten. The
 * server-side {@link ItemStack} keeps its real damage and its real maximum, so
 * repair, breaking, anvils and every plugin that inspects the item see exactly
 * what they saw before. Java players are never affected — their client already
 * understands the component.</p>
 */
public final class BedrockDurabilityBarScaler {

    private final Plugin plugin;
    private final List<PacketListener> registeredListeners = new ArrayList<>();

    public BedrockDurabilityBarScaler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    /** Registers the outbound listeners. Safe to call once, from onEnable. */
    public void register() {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

        // HIGHEST so the rescale reads whatever the enchantment-lore listener
        // wrote rather than being overwritten by it. The two are independent —
        // one edits lore, the other edits damage — but reading the final
        // payload means a future listener that replaces the stack outright
        // cannot silently drop the rescale.
        PacketListener outbound = new PacketAdapter(
            plugin,
            ListenerPriority.HIGHEST,
            PacketType.Play.Server.SET_SLOT,
            PacketType.Play.Server.WINDOW_ITEMS
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                try {
                    handleOutbound(event);
                } catch (Exception e) {
                    // Never let a bad stack take down item delivery: the bar
                    // being wrong is cosmetic, an empty inventory is not.
                    BedrockDurabilityBarScaler.this.plugin.getLogger().log(
                        Level.WARNING,
                        "BedrockDurabilityBarScaler: error processing "
                            + event.getPacketType() + " packet",
                        e
                    );
                }
            }
        };
        protocolManager.addPacketListener(outbound);
        registeredListeners.add(outbound);
    }

    /** Removes the listeners. Call from onDisable so a reload cannot stack them. */
    public void cleanup() {
        if (registeredListeners.isEmpty()) {
            return;
        }
        ProtocolManager pm = ProtocolLibrary.getProtocolManager();
        for (PacketListener listener : registeredListeners) {
            pm.removePacketListener(listener);
        }
        registeredListeners.clear();
    }

    private void handleOutbound(PacketEvent event) {
        Player player = event.getPlayer();
        if (!shouldRescaleFor(player)) {
            return;
        }

        PacketContainer packet = event.getPacket();
        if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
            ItemStack rescaled = rescaled(packet.getItemModifier().read(0));
            if (rescaled != null) {
                packet.getItemModifier().write(0, rescaled);
            }
            return;
        }

        List<ItemStack> items = packet.getItemListModifier().read(0);
        if (items == null || items.isEmpty()) {
            return;
        }
        // Lazy copy: most windows contain nothing with a custom maximum, and
        // rewriting the list unconditionally would churn every inventory open.
        List<ItemStack> modified = null;
        for (int i = 0; i < items.size(); i++) {
            ItemStack rescaled = rescaled(items.get(i));
            if (rescaled != null && modified == null) {
                modified = new ArrayList<>(items);
            }
            if (rescaled != null) {
                modified.set(i, rescaled);
            }
        }
        if (modified != null) {
            packet.getItemListModifier().write(0, modified);
        }
    }

    /**
     * Whether this player's packets should be rewritten.
     *
     * <p>Creative is excluded deliberately. Bedrock's creative inventory is
     * client-authoritative: the client can send the stack it was given straight
     * back to the server via {@code SET_CREATIVE_SLOT}, which would write the
     * rescaled damage onto the real item. Skipping creative removes that hazard
     * entirely, and a creative player has no use for a durability bar anyway.</p>
     */
    private boolean shouldRescaleFor(Player player) {
        if (player == null || player.getGameMode() == GameMode.CREATIVE) {
            return false;
        }
        return BedrockPlayerUtil.isBedrockPlayer(player);
    }

    // ================== pure helpers (unit-tested) ==================

    /**
     * A copy of {@code item} whose damage draws the correct bar on Bedrock, or
     * {@code null} when the item already draws correctly and must be left alone.
     *
     * <p>Returning null rather than an equal copy is what keeps the packet
     * untouched in the common case.</p>
     */
    static ItemStack rescaled(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        int vanillaMax = item.getType().getMaxDurability();
        if (vanillaMax <= 0) {
            return null;
        }
        if (!(item.getItemMeta() instanceof Damageable meta)) {
            return null;
        }
        // No override means Java and Bedrock already agree on the maximum.
        if (!meta.hasMaxDamage()) {
            return null;
        }
        int realMax = meta.getMaxDamage();
        int damage = meta.hasDamage() ? meta.getDamage() : 0;

        int scaled = scaledDamage(damage, realMax, vanillaMax);
        if (scaled == damage) {
            return null;
        }

        ItemStack copy = item.clone();
        copy.editMeta(Damageable.class, m -> {
            // The override has to go before the damage does. The scaled value is
            // expressed against the *vanilla* maximum, so on an item whose
            // override is SMALLER than vanilla — a custom sword capped at 50
            // where the material allows 1561 — writing it against the surviving
            // override throws "Damage cannot exceed max damage" and the whole
            // packet listener aborts. Clearing it first also keeps the packet
            // self-consistent: what leaves here is a plain vanilla-durability
            // item carrying a proportionally scaled damage, which is exactly
            // what Bedrock draws its bar from.
            m.setMaxDamage(null);
            m.setDamage(scaled);
        });
        return copy;
    }

    /**
     * The damage to report so that {@code sent / vanillaMax} matches the real
     * {@code damage / realMax}.
     *
     * @param damage     the item's real damage
     * @param realMax    the item's real maximum durability (the Java override)
     * @param vanillaMax the maximum Bedrock's client will divide by
     * @return the damage to put in the packet; {@code damage} itself when no
     *     rescale is warranted
     */
    static int scaledDamage(int damage, int realMax, int vanillaMax) {
        if (realMax <= 0 || vanillaMax <= 0 || realMax == vanillaMax) {
            return damage;
        }
        if (damage <= 0) {
            return 0;
        }
        if (damage >= realMax) {
            // Already spent. Report "one hit from breaking" rather than a
            // number past the end, which Bedrock would render as an empty or
            // wrapped bar. The item breaks server-side regardless.
            return vanillaMax - 1;
        }

        long scaled = Math.round((double) damage * vanillaMax / realMax);
        // A used item must never look pristine: rounding a small amount of wear
        // on a high-durability item down to zero would hide the bar entirely,
        // which reads as "undamaged" rather than "barely damaged".
        if (scaled < 1) {
            return 1;
        }
        // Nor may it look destroyed while durability remains.
        return (int) Math.min(scaled, vanillaMax - 1L);
    }
}
