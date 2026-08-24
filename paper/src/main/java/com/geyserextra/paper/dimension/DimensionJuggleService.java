package com.geyserextra.paper.dimension;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forces a Bedrock client to rebuild its dimension by teleporting the player
 * to a world of a different environment and straight back.
 *
 * <p>This is the only remedy a backend has for the stuck nether sky: the
 * Bukkit API cannot resend a dimension-change packet, and the Velocity
 * {@code /server} transfer that causes the bug is proxy-builtin and cannot be
 * intercepted here. Two loading screens (~1–2&nbsp;s) on an affected join is
 * the price of not playing the rest of the session under a red sky.</p>
 *
 * <p>Failure safety: the player's origin is persisted as a
 * {@link DimensionHandoffStore.PendingReturn} <em>before</em> the outbound
 * teleport. If they disconnect in the window between the two teleports, the
 * next join on this backend reads it and puts them back — without it they
 * would log in on the end platform with their invulnerability flag wrong.</p>
 */
public final class DimensionJuggleService {

    /**
     * Ticks to stay in the foreign world before returning. One second: Geyser
     * queues dimension switches, and bouncing back before the client has
     * acknowledged the first switch is exactly the kind of fast double-switch
     * that produces the stuck state this exists to repair.
     */
    static final long RETURN_DELAY_TICKS = 20L;

    private final JavaPlugin plugin;
    private final DimensionHandoffStore store;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public DimensionJuggleService(JavaPlugin plugin, DimensionHandoffStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /**
     * Runs the round-trip for an online player. Main thread only. A player
     * already mid-repair is left alone.
     */
    public void juggle(Player player) {
        UUID uuid = player.getUniqueId();
        if (!inFlight.add(uuid)) {
            return;
        }
        World foreign = pickForeignWorld(player.getWorld(), Bukkit.getWorlds());
        if (foreign == null) {
            // Single-environment server: nothing to bounce through. The sky
            // cannot be repaired from here; say so instead of failing silently.
            inFlight.remove(uuid);
            plugin.getLogger().fine(() -> "Nether-sky repair skipped for " + player.getName()
                + ": no world with a different environment is loaded.");
            return;
        }

        Location origin = player.getLocation().clone();
        boolean wasInvulnerable = player.isInvulnerable();
        store.writePendingReturn(uuid, new DimensionHandoffStore.PendingReturn(
            origin.getWorld().getUID().toString(),
            origin.getX(), origin.getY(), origin.getZ(),
            origin.getYaw(), origin.getPitch(),
            wasInvulnerable, System.currentTimeMillis()));

        player.sendMessage(Component.text(
            "空の表示を修復しています。少しの間ロード画面が出ます…", NamedTextColor.GRAY));
        // Invulnerable for the whole trip: the foreign spawn may be mid-air,
        // over the void, or occupied by something hostile.
        player.setInvulnerable(true);
        player.teleport(foreign.getSpawnLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                if (!player.isOnline()) {
                    // The pending-return file stays: the join listener will
                    // finish the trip when they come back.
                    plugin.getLogger().info("Nether-sky repair interrupted by disconnect for "
                        + player.getName() + "; return is persisted for their next join.");
                    return;
                }
                player.teleport(origin, PlayerTeleportEvent.TeleportCause.PLUGIN);
                player.setFallDistance(0f);
                player.setInvulnerable(wasInvulnerable);
                store.deletePendingReturn(uuid);
            } finally {
                inFlight.remove(uuid);
            }
        }, RETURN_DELAY_TICKS);
    }

    /**
     * Finishes a repair whose return teleport was cut off by a disconnect.
     *
     * @return true if a pending return existed for a world of this backend and
     *     the player was put back (the caller must then skip the normal join
     *     decision — the login itself already rebuilt the client's dimension)
     */
    public boolean tryRestorePendingReturn(Player player) {
        UUID uuid = player.getUniqueId();
        DimensionHandoffStore.PendingReturn pendingReturn =
            store.readPendingReturn(uuid, System.currentTimeMillis());
        if (pendingReturn == null) {
            return false;
        }
        World world;
        try {
            world = Bukkit.getWorld(UUID.fromString(pendingReturn.worldUid()));
        } catch (IllegalArgumentException e) {
            store.deletePendingReturn(uuid);
            return false;
        }
        if (world == null) {
            // Not this backend's world — the shared folder serves every
            // backend, so leave the file for the one that owns the world.
            return false;
        }
        Location origin = new Location(world,
            pendingReturn.x(), pendingReturn.y(), pendingReturn.z(),
            pendingReturn.yaw(), pendingReturn.pitch());
        player.teleport(origin, PlayerTeleportEvent.TeleportCause.PLUGIN);
        player.setFallDistance(0f);
        player.setInvulnerable(pendingReturn.invulnerable());
        store.deletePendingReturn(uuid);
        plugin.getLogger().info("Returned " + player.getName()
            + " from an interrupted nether-sky repair.");
        return true;
    }

    /**
     * Picks the world to bounce through: the end when available (its spawn is
     * the safe obsidian platform and its environment differs from both the
     * overworld and the nether), otherwise any world whose environment differs
     * from the player's current one.
     */
    static World pickForeignWorld(World current, List<World> worlds) {
        World fallback = null;
        for (World world : worlds) {
            if (world.getEnvironment() == current.getEnvironment()) {
                continue;
            }
            if (world.getEnvironment() == World.Environment.THE_END) {
                return world;
            }
            if (fallback == null) {
                fallback = world;
            }
        }
        return fallback;
    }
}
