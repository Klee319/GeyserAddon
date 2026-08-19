package com.geyserextra.paper.inventory;

import com.comphenix.protocol.events.PacketContainer;
import com.geyserextra.paper.GeyserExtraPaper;

import io.papermc.paper.datacomponent.DataComponentTypes;

import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks each player's currently-open top inventory type and exposes thread-safe
 * packet-level helpers that strip the {@code CUSTOM_MODEL_DATA} component from
 * outbound items destined for a vanilla utility container.
 *
 * <p>Two containers need this, for the same underlying reason and with the same
 * cure. Geyser registers CMD-bearing items as Bedrock-side custom items
 * ({@code geyser_custom:*}) via the auto-generated pack, and Bedrock's
 * client-side logic for these containers only understands vanilla identifiers:
 * the enchantment table crashes computing a preview, and the smithing table
 * refuses the item outright. Removing the CMD component from the outbound packet
 * makes Geyser forward the item as its vanilla base material, which the client
 * handles normally. Server-side state is never touched.</p>
 *
 * <p>Why a tracker instead of {@code player.getOpenInventory()} from the packet
 * thread: ProtocolLib packet listeners run on netty I/O threads, where the Bukkit
 * inventory API is not documented to be safe. This class records the open
 * inventory type from main-thread events and serves it through a
 * {@link ConcurrentHashMap}, so packet-thread callers never touch Bukkit state
 * directly.</p>
 *
 * <p>Why bundled with the strip helpers: the CMD strip is the only consumer of
 * the per-player state, and keeping them in one file avoids a tracker without a
 * client and a stripper without state.</p>
 */
public final class BedrockContainerCmdStripper implements Listener {

    /** Wire value of {@code containerId} for the cursor / off-window SET_SLOT updates. */
    public static final int CURSOR_CONTAINER_ID = -1;
    /** Wire value of {@code slot} for cursor SET_SLOT updates. */
    public static final int CURSOR_SLOT = -1;

    private final GeyserExtraPaper plugin;
    private final Map<UUID, InventoryType> openTopInventoryType = new ConcurrentHashMap<>();
    // Surface field-read failures exactly once at WARN level. Without this,
    // a ProtocolLib accessor change in a future Paper update would silently
    // disable the CMD-strip workaround (every field-read would throw and be
    // demoted to fine-level), and the Bedrock client crash this workaround
    // exists to prevent would silently come back. The flag also guards
    // against log flooding on the happy path where one bad packet would
    // otherwise produce thousands of identical warnings per minute.
    private final AtomicBoolean fieldReadWarningEmitted = new AtomicBoolean(false);

    public BedrockContainerCmdStripper(GeyserExtraPaper plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    // ========================================================================
    // Inventory state tracking — main thread
    // ========================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        HumanEntity human = event.getPlayer();
        if (human instanceof Player player) {
            openTopInventoryType.put(player.getUniqueId(), event.getInventory().getType());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        HumanEntity human = event.getPlayer();
        if (human instanceof Player player) {
            openTopInventoryType.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        openTopInventoryType.remove(event.getPlayer().getUniqueId());
    }

    // ========================================================================
    // Packet-thread queries — thread-safe (ConcurrentHashMap reads only)
    // ========================================================================

    /**
     * Returns whether the player's open top inventory is one of {@code types}.
     *
     * <p>Takes several types because Bukkit keeps more than one constant for a
     * single vanilla menu — {@link InventoryType#SMITHING} and
     * {@link InventoryType#SMITHING_NEW} both exist on 1.21 — and a caller that
     * guessed the wrong one would silently do nothing.</p>
     *
     * <p>Safe to call from any thread.</p>
     */
    public boolean isAtInventory(UUID playerId, InventoryType... types) {
        if (playerId == null) {
            return false;
        }
        InventoryType open = openTopInventoryType.get(playerId);
        if (open == null) {
            return false;
        }
        for (InventoryType type : types) {
            if (open == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the player is known to currently have an enchantment table
     * (or any other vanilla {@link InventoryType#ENCHANTING}) open.
     *
     * <p>Safe to call from any thread.</p>
     */
    public boolean isAtEnchantmentTable(UUID playerId) {
        return isAtInventory(playerId, InventoryType.ENCHANTING);
    }

    /**
     * Reads the {@code containerId} field of a SET_SLOT or WINDOW_ITEMS packet.
     *
     * <p>ProtocolLib widens the wire byte to int on Paper 1.21.x, so the
     * integer accessor is the canonical path. Throws on protocol mismatch so
     * callers can decide whether to retry or skip.</p>
     */
    public int readContainerId(PacketContainer packet) {
        return packet.getIntegers().read(0);
    }

    /**
     * Reads the {@code slot} field of a SET_SLOT packet.
     *
     * <p>The 1.21.x wire type for slot is {@code Short}; ProtocolLib exposes it
     * via the short modifier. The previous fallback to
     * {@code getIntegers().read(2)} never matched on this version and has been
     * removed.</p>
     */
    public int readSetSlotIndex(PacketContainer packet) {
        return packet.getShorts().read(0);
    }

    /**
     * Returns whether the packet addresses the open top window of a player who
     * currently has one of {@code types} open.
     *
     * <p>Works for both SET_SLOT and WINDOW_ITEMS, because both carry the same
     * {@code containerId} field and neither {@code 0} (the player inventory) nor
     * {@code -1} (the cursor) is a real window.</p>
     */
    public boolean isTopWindow(UUID playerId, PacketContainer packet, InventoryType... types) {
        if (!isAtInventory(playerId, types)) {
            return false;
        }
        try {
            int containerId = readContainerId(packet);
            return containerId != 0 && containerId != CURSOR_CONTAINER_ID;
        } catch (Exception ex) {
            logFieldReadFailure("SET_SLOT/WINDOW_ITEMS", ex);
            return false;
        }
    }

    /**
     * Returns whether the SET_SLOT packet targets the input slot of an
     * enchantment table that the given player has open.
     */
    public boolean isEnchantmentTableInputSlot(UUID playerId, PacketContainer packet) {
        if (!isTopWindow(playerId, packet, InventoryType.ENCHANTING)) {
            return false;
        }
        try {
            return readSetSlotIndex(packet) == 0;
        } catch (Exception ex) {
            logFieldReadFailure("SET_SLOT", ex);
            return false;
        }
    }

    /**
     * Returns whether the SET_SLOT packet targets the cursor (windowId == -1
     * and slot == -1). Combined with {@link #isAtInventory(UUID, InventoryType...)}
     * the caller can decide whether the cursor item should also be stripped.
     */
    public boolean isCursorSetSlot(PacketContainer packet) {
        try {
            return readContainerId(packet) == CURSOR_CONTAINER_ID
                && readSetSlotIndex(packet) == CURSOR_SLOT;
        } catch (Exception ex) {
            logFieldReadFailure("SET_SLOT", ex);
            return false;
        }
    }

    /**
     * Returns whether the WINDOW_ITEMS packet describes the enchantment table
     * window currently open for the given player.
     */
    public boolean isEnchantmentTableWindow(UUID playerId, PacketContainer packet) {
        return isTopWindow(playerId, packet, InventoryType.ENCHANTING);
    }

    // ========================================================================
    // CMD strip helper
    // ========================================================================

    /**
     * Returns a clone of {@code item} with the {@code CUSTOM_MODEL_DATA}
     * component removed, or {@code null} when the item lacks the component
     * (the normal short-circuit path) or the strip cannot be performed.
     *
     * <p>The two {@code null} cases are deliberately distinguished by log
     * output: absent component is silent, while a real strip failure
     * (component present but {@code clone()}/{@code unsetData()} threw) is
     * always logged at WARN level so an operator can correlate with player
     * crash reports. Server-side state is never mutated.</p>
     */
    public ItemStack stripCustomModelData(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        boolean hasComponent;
        try {
            hasComponent = item.hasData(DataComponentTypes.CUSTOM_MODEL_DATA);
        } catch (Exception ex) {
            plugin.getLogger().warning(
                "CMD-strip: hasData(CUSTOM_MODEL_DATA) failed on " + item.getType()
                    + " (" + ex.getClass().getSimpleName() + "): " + ex.getMessage());
            return null;
        }
        if (!hasComponent) {
            return null;
        }
        try {
            ItemStack clone = item.clone();
            clone.unsetData(DataComponentTypes.CUSTOM_MODEL_DATA);
            return clone;
        } catch (Exception ex) {
            plugin.getLogger().warning(
                "CMD-strip: unsetData(CUSTOM_MODEL_DATA) failed on " + item.getType()
                    + " (" + ex.getClass().getSimpleName() + "): " + ex.getMessage()
                    + " — Bedrock crash workaround inactive for this item.");
            return null;
        }
    }

    private void logFieldReadFailure(String packetName, Exception ex) {
        // First occurrence: WARN so an operator notices a ProtocolLib API
        // mismatch immediately. Subsequent occurrences: fine-level only,
        // so a persistent mismatch does not flood the log.
        if (fieldReadWarningEmitted.compareAndSet(false, true)) {
            plugin.getLogger().warning(
                "CMD-strip: " + packetName + " field read failed ("
                    + ex.getClass().getSimpleName() + "): " + ex.getMessage()
                    + " — Bedrock container CMD-strip workaround inactive for"
                    + " subsequent packets of this type. Update ProtocolLib or"
                    + " report a Paper/ProtocolLib mismatch.");
            return;
        }
        if (plugin.getGeyserExtraConfig().general().debugMode()) {
            plugin.getLogger().fine(
                "CMD-strip: " + packetName + " field read failed ("
                    + ex.getClass().getSimpleName() + "): " + ex.getMessage());
        }
    }
}
