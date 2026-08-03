package com.geyserextra.paper.listener;

import com.geyserextra.core.api.CustomItemMapping;
import com.geyserextra.core.util.CustomItemCooldownGroups;
import com.geyserextra.paper.GeyserExtraPaper;
import com.geyserextra.paper.scanner.CustomItemScanner;
import com.geyserextra.paper.util.BedrockPlayerUtil;
import io.papermc.paper.event.player.PlayerItemGroupCooldownEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mirrors Java cooldown groups into per-custom-item Bedrock categories.
 *
 * <p>Paper plugins may call {@code Player#setCooldown(Material, ticks)} or
 * {@code Player#setCooldown(NamespacedKey, ticks)}. Geyser receives categories
 * such as {@code minecraft:golden_sword} or plugin keys like
 * {@code valhallammo:dash}. When attributed to a held or recently used custom
 * item mapping, material groups and arbitrary plugin cooldown groups are
 * mirrored so Bedrock USE_COOLDOWN overlays stay item-specific.</p>
 */
public final class CooldownBridgeListener implements Listener {

    private static final long RECENT_ITEM_NANOS = 2_000_000_000L;

    private final GeyserExtraPaper plugin;
    private final CustomItemScanner scanner;
    private final Map<UUID, RecentMapping> recentMappings = new ConcurrentHashMap<>();

    /**
     * Emits a per-event cooldown boundary trace, but only when
     * {@code general.debugMode} is set.
     *
     * <p>Logged at INFO rather than FINE on purpose: the server runtime's
     * handler is pinned to INFO, so FINE records are dropped before they reach
     * the console and the trace would be invisible exactly when it is wanted.
     *
     * <p>Only cooldown-bearing events reach this. The per-input traces that
     * used to sit on interact, arm swing and melee damage are gone: they fired
     * on every click and swing, which flooded a console that had debugMode on
     * for unrelated reasons, and they told nothing that the cooldown_event
     * trace does not already carry at the point the mapping is chosen.</p>
     */
    private void diagnostic(String message) {
        if (plugin.getGeyserExtraConfig().general().debugMode()) {
            plugin.getLogger().info(message);
        }
    }

    public CooldownBridgeListener(GeyserExtraPaper plugin, CustomItemScanner scanner) {
        this.plugin = plugin;
        this.scanner = scanner;
    }

    /**
     * Remembers the source item before a use action can consume its final
     * stack entry. Cooldown events normally arrive immediately afterwards.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        // Capture before gameplay plugins (normally NORMAL/HIGH) synchronously
        // call setCooldown from the same interaction event. MONITOR is too
        // late: PlayerItemGroupCooldownEvent is nested inside setCooldown.
        Optional<CustomItemMapping> mapping = scanner.scanItem(event.getItem());
        mapping.ifPresent(value ->
            recentMappings.put(event.getPlayer().getUniqueId(),
                new RecentMapping(value, System.nanoTime())));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onArmSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        Optional<CustomItemMapping> mapping =
            scanner.scanItem(player.getInventory().getItemInMainHand());
        mapping.ifPresent(value ->
            recentMappings.put(player.getUniqueId(),
                new RecentMapping(value, System.nanoTime())));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMeleeDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        // Melee weapons often set cooldown from damage events without interact.
        Optional<CustomItemMapping> mapping =
            scanner.scanItem(player.getInventory().getItemInMainHand());
        mapping.ifPresent(value ->
            recentMappings.put(player.getUniqueId(),
                new RecentMapping(value, System.nanoTime())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCooldown(PlayerItemGroupCooldownEvent event) {
        Player player = event.getPlayer();
        String sourceGroup = event.getCooldownGroup().toString();
        if (CustomItemCooldownGroups.isSynthetic(sourceGroup)) {
            diagnostic("[CooldownBridge:diagnostic] synthetic event"
                + " player=" + player.getName()
                + " group=" + sourceGroup
                + " ticks=" + event.getCooldown());
            return; // Prevent recursion from the synthetic setCooldown call.
        }

        Optional<CustomItemMapping> main =
            scanner.scanItem(player.getInventory().getItemInMainHand());
        Optional<CustomItemMapping> off =
            scanner.scanItem(player.getInventory().getItemInOffHand());
        Optional<CustomItemMapping> recent = recentMapping(player.getUniqueId());
        boolean bedrock = BedrockPlayerUtil.isBedrockPlayer(player);
        Optional<CustomItemMapping> selected =
            CooldownMappingSelector.select(sourceGroup, main, off, recent);

        // Boundary trace: shows whether the loss occurs before selection,
        // during the synthetic Paper event, or later in Geyser/Bedrock
        // translation. Gated by debugMode -- see diagnostic().
        diagnostic("[CooldownBridge:diagnostic] cooldown_event"
            + " player=" + player.getName()
            + " bedrock=" + bedrock
            + " source=" + sourceGroup
            + " ticks=" + event.getCooldown()
            + " recent=" + mappingLabel(recent)
            + " main=" + mappingLabel(main)
            + " off=" + mappingLabel(off)
            + " selected=" + mappingLabel(selected));

        if (!bedrock) {
            return;
        }

        selected.ifPresent(mapping -> {
            // Consume only when the selected mapping is the remembered source.
            // A cooldown associated with another currently-held item must not
            // erase a delayed cooldown source that is still waiting.
            if (recent.filter(mapping::equals).isPresent()) {
                recentMappings.remove(player.getUniqueId());
            }
            String group = CustomItemCooldownGroups.forMapping(mapping.name());
            NamespacedKey key = group != null ? NamespacedKey.fromString(group) : null;
            if (key == null) {
                plugin.getLogger().fine("[CooldownBridge] Invalid synthetic group for "
                    + mapping.name());
                return;
            }
            // This fires one nested PlayerItemGroupCooldownEvent. The
            // synthetic-namespace guard above stops it immediately while the
            // resulting ClientboundCooldownPacket continues through Geyser to
            // PlayerStartItemCooldownPacket.
            diagnostic("[CooldownBridge:diagnostic] mirror"
                + " player=" + player.getName()
                + " source=" + sourceGroup
                + " target=" + key
                + " ticks=" + event.getCooldown());
            player.setCooldown(key, event.getCooldown());
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        recentMappings.remove(event.getPlayer().getUniqueId());
    }

    private Optional<CustomItemMapping> recentMapping(UUID playerId) {
        RecentMapping recent = recentMappings.get(playerId);
        if (recent == null) {
            return Optional.empty();
        }
        if (System.nanoTime() - recent.createdAtNanos() > RECENT_ITEM_NANOS) {
            recentMappings.remove(playerId, recent);
            return Optional.empty();
        }
        return Optional.of(recent.mapping());
    }

    private static String mappingLabel(Optional<CustomItemMapping> mapping) {
        return mapping
            .map(value -> value.name() + "@" + value.baseItem())
            .orElse("-");
    }

    private record RecentMapping(CustomItemMapping mapping, long createdAtNanos) {}
}
