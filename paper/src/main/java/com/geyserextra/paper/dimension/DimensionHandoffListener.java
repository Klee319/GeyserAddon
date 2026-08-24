package com.geyserextra.paper.dimension;

import com.geyserextra.paper.util.BedrockPlayerUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Repairs the Bedrock "stuck nether sky" automatically on the joins where it
 * can happen.
 *
 * <p>The bug (GeyserMC #3005 family, unfixed upstream): the Bedrock client
 * starts the nether-portal fog transition, and before it resolves the player
 * is moved to another backend by Velocity's builtin {@code /server} — a
 * transfer this backend cannot intercept — or relogs. The new session reloads
 * chunks without a dimension change, so the client keeps rendering the nether
 * sky in the overworld indefinitely. Live repro: stand inside the resource
 * server's nether portal and {@code /server} to main.</p>
 *
 * <p>Prevention being impossible, the destination repairs instead: on quit
 * every backend records where a Bedrock player was (world environment, inside
 * a portal or not) in the shared extension folder; on join, if that record —
 * or the player's current position — says the client's sky is at risk, the
 * {@link DimensionJuggleService} bounces them through a world of a different
 * environment, which forces the client to rebuild the dimension. The manual
 * escape hatch for a sky that got stuck some other way is {@code /fixsky}.</p>
 */
public final class DimensionHandoffListener implements Listener {

    /**
     * Ticks between the join and the repair check. Two seconds lets the join's
     * own dimension initialization and any login-plugin teleports (spawn
     * plugins, HuskSync position sync) finish first, so the origin we save is
     * the player's real position.
     */
    static final long JOIN_CHECK_DELAY_TICKS = 40L;

    private final JavaPlugin plugin;
    private final DimensionHandoffStore store;
    private final DimensionJuggleService juggleService;

    public DimensionHandoffListener(JavaPlugin plugin, DimensionHandoffStore store,
                                    DimensionJuggleService juggleService) {
        this.plugin = plugin;
        this.store = store;
        this.juggleService = juggleService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
            return;
        }
        store.writeFlag(player.getUniqueId(), new DimensionHandoffFlag(
            System.currentTimeMillis(),
            player.getWorld().getEnvironment().name(),
            isInNetherPortal(player)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!BedrockPlayerUtil.isBedrockPlayer(player)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (juggleService.tryRestorePendingReturn(player)) {
                // An interrupted repair means the quit flag describes the
                // repair's own foreign world, not real player movement — and
                // the fresh login already rebuilt the client's dimension.
                store.consumeFlag(player.getUniqueId());
                return;
            }
            DimensionHandoffFlag flag = store.consumeFlag(player.getUniqueId());
            boolean standingInPortal = isInNetherPortal(player);
            if (DimensionHandoffDecision.shouldJuggle(System.currentTimeMillis(), flag,
                standingInPortal, player.getWorld().getEnvironment().name())) {
                juggleService.juggle(player);
            }
        }, JOIN_CHECK_DELAY_TICKS);
    }

    /** Feet or head inside a portal block — either is enough to start the client's fog transition. */
    private static boolean isInNetherPortal(Player player) {
        return player.getLocation().getBlock().getType() == Material.NETHER_PORTAL
            || player.getEyeLocation().getBlock().getType() == Material.NETHER_PORTAL;
    }
}
