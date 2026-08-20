package com.geyserextra.paper.smithing;

import com.geyserextra.paper.bedrock.SmithingBaseExemptions;
import com.geyserextra.paper.inventory.BedrockContainerCmdStripper;
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
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Lets a Bedrock player netherite-upgrade a custom-model-data item at a smithing
 * table, by presenting every item as its vanilla base material for as long as the
 * table is open.
 *
 * <p><b>Why it is broken.</b> An item carrying {@code minecraft:custom_model_data}
 * is registered by Geyser as a Bedrock-side custom item — a distinct identifier in
 * the {@code geyser_custom:} namespace. Bedrock's smithing table is driven entirely
 * by the recipe list the client holds: the slots only accept an item some recipe
 * names as an input, and the result can only be taken when a recipe matches all
 * three inputs. Every netherite-upgrade recipe the client knows names vanilla
 * identifiers, so a {@code geyser_custom:*} sword matches nothing and the upgrade
 * is simply unavailable. Geyser 2.11 does mint a smithing recipe on the fly when
 * the Java server reports an output the client cannot explain
 * ({@code JavaContainerSetSlotTranslator.updateSmithingTableOutput}), but that
 * fires only <em>after</em> the server has computed a result — which it never does,
 * because the client would not let the item into the input slot in the first
 * place. On Java the same upgrade works, which is exactly the asymmetry reported.</p>
 *
 * <p><b>The cure.</b> The same one the enchantment table already uses: remove the
 * CMD component from the outbound packet so Geyser forwards the item as its
 * vanilla base material. The client then sees an ordinary diamond sword, accepts
 * it, previews the upgrade and completes the transaction; the server still holds
 * the real item and performs the real vanilla upgrade, whose result keeps the CMD
 * because that is how component transfer works on Java.</p>
 *
 * <p><b>Why the whole window and not just the four slots.</b> The client's refusal
 * happens at <em>placement</em> time, and at that moment the item is still in the
 * player-inventory half of the smithing window. Stripping only slots 0-3 would
 * make the item acceptable exactly once it was already somewhere it could not get
 * to. The cost is that custom artwork renders as its vanilla base while the
 * smithing GUI is open, and returns the moment it closes.</p>
 *
 * <p><b>Items the proxy ships a smithing recipe for are exempt</b> from all of this
 * ({@link com.geyserextra.paper.bedrock.SmithingBaseExemptions}). Stripping and recipe injection
 * are two cures for the same disease and <b>cancel each other out on any single item</b>: the
 * injected recipe names the item by its {@code geyser_custom:*} identifier, which stops existing
 * the moment the CMD is stripped. Stripping only helps where a <em>vanilla</em> netherite recipe
 * covers the base material — the diamond tools — so it stays in charge of exactly those items the
 * injected table does not name, and hands over the rest.</p>
 *
 * <p><b>Creative is excluded</b> for the same reason as
 * {@code BedrockDurabilityBarScaler}: Bedrock's creative inventory is
 * client-authoritative and can echo a stripped stack back through
 * {@code SET_CREATIVE_SLOT}, which would erase the CMD from the real item rather
 * than only from the packet.</p>
 */
public final class BedrockSmithingTableCmdStripper {

    private final Plugin plugin;
    private final BedrockContainerCmdStripper stripper;
    private final SmithingBaseExemptions exemptions;
    private final List<PacketListener> registeredListeners = new ArrayList<>();

    public BedrockSmithingTableCmdStripper(Plugin plugin, BedrockContainerCmdStripper stripper,
                                           SmithingBaseExemptions exemptions) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.stripper = Objects.requireNonNull(stripper, "stripper must not be null");
        this.exemptions = Objects.requireNonNull(exemptions, "exemptions must not be null");
    }

    /**
     * Strips this stack unless the proxy is shipping a smithing recipe that names it.
     *
     * <p>Returns {@code null} when nothing changed, matching
     * {@link BedrockContainerCmdStripper#stripCustomModelData} so callers stay lazy-copy.
     */
    private ItemStack stripUnlessExempt(ItemStack item) {
        if (exemptions.isExempt(item)) {
            return null;
        }
        return stripper.stripCustomModelData(item);
    }

    /** Registers the outbound listener. Call once, from onEnable. */
    public void register() {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

        // HIGHEST for the same reason the durability scaler uses it: read
        // whatever earlier listeners wrote, so a stack they replaced outright
        // still gets stripped.
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
                    // A missing upgrade is an annoyance; a swallowed inventory
                    // packet is an empty smithing table. Never propagate.
                    BedrockSmithingTableCmdStripper.this.plugin.getLogger().log(
                        Level.WARNING,
                        "BedrockSmithingTableCmdStripper: error processing "
                            + event.getPacketType() + " packet",
                        e
                    );
                }
            }
        };
        protocolManager.addPacketListener(outbound);
        registeredListeners.add(outbound);
    }

    /** Removes the listener. Call from onDisable so a reload cannot stack them. */
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
        if (!shouldStripFor(player)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        // Named here rather than in a static constant on purpose: touching
        // InventoryType initialises Bukkit's MenuType registry, which needs a
        // running server, and a static field would drag that into class init
        // and put every method on this class — including the pure ones — out of
        // reach of a unit test. Bukkit still declares SMITHING_NEW, but it has
        // been deprecated for removal since 1.20.1, the release that made
        // SMITHING mean the modern table, so matching it adds a build warning
        // and no coverage.
        if (!stripper.isAtInventory(playerId, InventoryType.SMITHING)) {
            return;
        }

        PacketContainer packet = event.getPacket();
        if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
            ItemStack stripped = stripUnlessExempt(packet.getItemModifier().read(0));
            if (stripped != null) {
                packet.getItemModifier().write(0, stripped);
            }
            return;
        }

        List<ItemStack> items = packet.getItemListModifier().read(0);
        if (items != null && !items.isEmpty()) {
            // Lazy copy: a window with no custom items must leave the packet
            // byte-identical rather than churn a list on every inventory open.
            List<ItemStack> modified = null;
            for (int i = 0; i < items.size(); i++) {
                ItemStack stripped = stripUnlessExempt(items.get(i));
                if (stripped == null) {
                    continue;
                }
                if (modified == null) {
                    modified = new ArrayList<>(items);
                }
                modified.set(i, stripped);
            }
            if (modified != null) {
                packet.getItemListModifier().write(0, modified);
            }
        }

        // WINDOW_ITEMS carries the cursor in a separate trailing field. Not
        // every ProtocolLib/protocol pairing exposes it the same way, so a read
        // failure here is tolerated rather than fatal — the same treatment the
        // enchantment-table path gives it.
        try {
            ItemStack carried = stripUnlessExempt(packet.getItemModifier().read(0));
            if (carried != null) {
                packet.getItemModifier().write(0, carried);
            }
        } catch (Exception ex) {
            plugin.getLogger().fine(
                "Smithing CMD-strip: WINDOW_ITEMS carried-item access unavailable ("
                    + ex.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Whether this player's smithing-table packets should be rewritten.
     *
     * <p>Package-private and taking the two properties separately so the policy
     * can be pinned by a test without a live {@link Player}.</p>
     */
    static boolean shouldStripFor(boolean isBedrock, GameMode gameMode) {
        return isBedrock && gameMode != GameMode.CREATIVE;
    }

    private boolean shouldStripFor(Player player) {
        if (player == null) {
            return false;
        }
        return shouldStripFor(BedrockPlayerUtil.isBedrockPlayer(player), player.getGameMode());
    }
}
