package com.geyserextra.paper.display;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/**
 * Displays chunk boundaries as colored dust particles visible only to the requesting player.
 *
 * Why: Bedrock Edition lacks the F3+G chunk boundary overlay available in Java Edition.
 * Chunk boundaries are essential for technical players designing farms, redstone circuits,
 * and mob spawning systems where chunk-aligned placement matters. This class provides
 * an equivalent visual using DUST particles spawned per-player.
 *
 * Why DUST particles: DUST particles support custom RGB colors and sizes, persist
 * long enough to be visible at the 10-tick (0.5s) update interval, and can be sent
 * to individual players without broadcasting to the entire server.
 */
public final class ChunkBoundaryDisplay {

    /** Why: Chunks in Minecraft are 16x16 blocks. Used to calculate boundary positions. */
    private static final int CHUNK_SIZE = 16;

    /**
     * Why: Cyan color provides high visibility against most Minecraft terrain without
     * being confused with common particle effects (fire, enchantment, redstone).
     */
    private static final Color BOUNDARY_COLOR = Color.fromRGB(0, 255, 255);

    /**
     * Why: Yellow highlights sub-chunk Y boundaries (Y=0, Y=64, etc.) to help players
     * identify important vertical thresholds (sea level, spawn height, build limit zones).
     */
    private static final Color SUB_CHUNK_COLOR = Color.fromRGB(255, 255, 0);

    /**
     * Why: 0.7f provides visible particles without being overly large and obstructive.
     * Larger particles would obscure block details; smaller ones would be hard to see
     * at the edges of the player's view.
     */
    private static final float PARTICLE_SIZE = 0.7f;

    /**
     * Why: Particles are shown 3 blocks below the player to reveal boundaries at
     * floor level, which is where placement decisions are most critical.
     */
    private static final int Y_RANGE_BELOW = 3;

    /**
     * Why: Particles extend 6 blocks above the player to show boundaries above eye
     * level, helping gauge vertical clearance for builds and entity spawning.
     */
    private static final int Y_RANGE_ABOVE = 6;

    /**
     * Why: 0.5-block vertical spacing balances visual density with particle count.
     * Finer spacing (0.25) would double particle count without meaningful visual
     * improvement; coarser spacing (1.0) would create visible gaps in the boundary lines.
     */
    private static final double Y_STEP = 0.5;

    /**
     * Why: Sub-chunk height in Minecraft is 16 blocks. Used to determine whether a
     * Y position falls on a sub-chunk boundary for color differentiation.
     */
    private static final int SUB_CHUNK_HEIGHT = 16;

    /**
     * Creates a new ChunkBoundaryDisplay instance.
     *
     * Why: Stateless design — no per-player tracking is needed because DUST particles
     * are fire-and-forget. They automatically despawn after their lifetime expires,
     * eliminating the need for explicit cleanup.
     */
    public ChunkBoundaryDisplay() {
        // Why: intentionally empty — particles are ephemeral and need no state tracking
    }

    /**
     * Spawns chunk boundary particles visible only to the specified player.
     *
     * Why player.spawnParticle: This method sends particles only to the target player's
     * client, not to all nearby players. This is critical because each Bedrock player
     * may have different display settings, and broadcasting would create visual noise
     * for players who have not enabled chunk boundary display.
     *
     * Performance: 4 boundary lines x 16 positions x ~18 Y levels (every 0.5 blocks)
     * = ~1,152 particles per update. At a 10-tick interval, this is well within
     * acceptable limits for a single player's client.
     *
     * @param player the Bedrock player to show chunk boundaries to
     */
    public void update(Player player) {
        Location playerLoc = player.getLocation();
        int chunkX = playerLoc.getBlockX() >> 4;
        int chunkZ = playerLoc.getBlockZ() >> 4;

        // Why: Chunk boundaries are at chunkCoord * 16 and (chunkCoord + 1) * 16.
        // The +16 boundary is the start of the next chunk, which is also the edge
        // of the current chunk.
        int minX = chunkX * CHUNK_SIZE;
        int maxX = minX + CHUNK_SIZE;
        int minZ = chunkZ * CHUNK_SIZE;
        int maxZ = minZ + CHUNK_SIZE;

        double minY = playerLoc.getY() - Y_RANGE_BELOW;
        double maxY = playerLoc.getY() + Y_RANGE_ABOVE;

        // Why: Draw each of the 4 boundary edges separately. Two edges run along X
        // (at minZ and maxZ), two run along Z (at minX and maxX). This covers the
        // full perimeter of the chunk.
        drawXBoundaryLine(player, minX, maxX, minZ, minY, maxY);
        drawXBoundaryLine(player, minX, maxX, maxZ, minY, maxY);
        drawZBoundaryLine(player, minZ, maxZ, minX, minY, maxY);
        drawZBoundaryLine(player, minZ, maxZ, maxX, minY, maxY);
    }

    /**
     * Performs cleanup when the display is disabled or plugin is shutting down.
     *
     * Why: DUST particles are ephemeral (auto-despawn after ~1 second), so no explicit
     * cleanup is required. This method exists to satisfy the consistent interface
     * contract shared with other display classes that do require cleanup.
     */
    public void cleanup() {
        // Why: intentionally empty — particles auto-despawn and require no tracking
    }

    /**
     * Draws a boundary line along the X axis at a fixed Z coordinate.
     *
     * @param player the target player
     * @param startX the starting X position (inclusive)
     * @param endX   the ending X position (exclusive)
     * @param z      the fixed Z position for this boundary line
     * @param minY   the minimum Y position for particle spawning
     * @param maxY   the maximum Y position for particle spawning
     */
    private void drawXBoundaryLine(Player player, int startX, int endX, int z, double minY, double maxY) {
        for (int x = startX; x < endX; x++) {
            spawnVerticalParticleColumn(player, x + 0.5, z, minY, maxY);
        }
    }

    /**
     * Draws a boundary line along the Z axis at a fixed X coordinate.
     *
     * @param player the target player
     * @param startZ the starting Z position (inclusive)
     * @param endZ   the ending Z position (exclusive)
     * @param x      the fixed X position for this boundary line
     * @param minY   the minimum Y position for particle spawning
     * @param maxY   the maximum Y position for particle spawning
     */
    private void drawZBoundaryLine(Player player, int startZ, int endZ, int x, double minY, double maxY) {
        for (int z = startZ; z < endZ; z++) {
            spawnVerticalParticleColumn(player, x, z + 0.5, minY, maxY);
        }
    }

    /**
     * Spawns a vertical column of particles at the given XZ position.
     *
     * Why vertical column: Drawing particles at every Y step along the boundary
     * creates a visible "wall" effect that clearly delineates chunk edges, similar
     * to Java Edition's F3+G overlay but rendered in 3D space.
     *
     * @param player the target player
     * @param x      the X coordinate for the particle column
     * @param z      the Z coordinate for the particle column
     * @param minY   the bottom Y of the column
     * @param maxY   the top Y of the column
     */
    private void spawnVerticalParticleColumn(Player player, double x, double z, double minY, double maxY) {
        for (double y = minY; y <= maxY; y += Y_STEP) {
            Color color = selectParticleColor(y);
            Particle.DustOptions dustOptions = new Particle.DustOptions(color, PARTICLE_SIZE);

            // Why: count=1, offset=0 ensures a single particle at the exact position
            // with no random scatter. This creates clean, precise boundary lines
            // instead of fuzzy clouds.
            player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dustOptions);
        }
    }

    /**
     * Selects the particle color based on the Y coordinate.
     *
     * Why color differentiation: Sub-chunk boundaries (multiples of 16) are highlighted
     * in yellow to help players identify important vertical thresholds such as Y=0
     * (world bottom reference), Y=64 (old sea level), and Y=128 (nether ceiling).
     *
     * @param y the Y coordinate to evaluate
     * @return the color to use for particles at this Y level
     */
    private Color selectParticleColor(double y) {
        // Why: Round to nearest integer before modulo check to avoid floating-point
        // comparison issues with the 0.5-step iteration
        int roundedY = (int) Math.round(y);
        if (roundedY % SUB_CHUNK_HEIGHT == 0) {
            return SUB_CHUNK_COLOR;
        }
        return BOUNDARY_COLOR;
    }
}
