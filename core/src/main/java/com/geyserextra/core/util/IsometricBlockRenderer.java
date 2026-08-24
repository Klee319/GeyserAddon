package com.geyserextra.core.util;

import java.awt.image.BufferedImage;

/**
 * Renders a cube block into the same isometric icon Java draws in an inventory slot.
 *
 * <p>Java gets this for free: an item whose model inherits {@code block/cube_all} is handed to the
 * client, which renders the model live under the {@code gui} display transform
 * ({@code rotation [30, 225, 0]}, {@code scale 0.625}). Bedrock has no equivalent — a custom item's
 * icon is a flat PNG and nothing else — so the projection has to be baked here instead.</p>
 *
 * <p>The geometry is derived from that transform rather than guessed. Rotating a unit cube 225°
 * about Y and 30° about X, then projecting orthographically, gives:</p>
 * <ul>
 *   <li>silhouette width {@code 2·sin45° = 1.414} edges,</li>
 *   <li>up-face edges at {@code (±0.707, ∓0.354)} — a 2:1 rhombus, and</li>
 *   <li>the vertical edge at {@code cos30° = 0.866}, i.e. {@link #SIDE_HEIGHT_RATIO} of the
 *       width.</li>
 * </ul>
 * <p>The first cut used {@code 0.5} there — the plain pixel-art cube — and the result was reported
 * from the server as "Java と違う": too short in the body. The same derivation fixes which side is
 * which: at 225° the faces turned toward the camera are <b>east on the left</b> and <b>north on the
 * right</b>, and Minecraft shades east/west 0.6 against north/south 0.8, so Java's left face is the
 * <em>darker</em> one. That was backwards too.</p>
 *
 * <p>Pure function of its inputs and free of any Bukkit / Geyser type, so the geometry can be
 * asserted in a unit test without a server or a pack.</p>
 */
public final class IsometricBlockRenderer {

    /**
     * Edge length of the rendered icon.
     *
     * <p>128, not the texture's own 16: the icon is a projection, so a texel's edges land on the
     * diagonal of the output. At 32 that diagonal has one output pixel per two texels and the cube
     * came out visibly ragged — the first deployment was reported as "低画質で歪". 64 fixed the
     * raggedness but was still reported as softer than vanilla: Bedrock renders vanilla blocks as
     * real 3D models at the display's native resolution, so a pre-baked PNG loses whenever the
     * slot draws larger than the PNG (GUI scale ×2-3 on a 1080p+ screen draws the slot at
     * 96-160 px). 128 gives eight output pixels per texel and covers those slot sizes without
     * upscaling; beyond this the PNGs' cost in the pack outweighs a difference nobody can see.</p>
     */
    public static final int SIZE = 128;

    /**
     * Height of the cube's vertical edge as a fraction of the silhouette width: {@code cos30° /
     * (2·sin45°)}. Not 0.5 — that is the pixel-art cube, and it is visibly squatter than Java's.
     */
    private static final double SIDE_HEIGHT_RATIO = 0.6123724356957945;

    /** Silhouette height over width: the two half-rhombi plus the vertical edge. */
    private static final double ASPECT = 0.5 + SIDE_HEIGHT_RATIO;

    /**
     * Minecraft's face shading. The client multiplies each face's colour by these before drawing,
     * which is the entire reason a flat-coloured block still reads as a cube.
     *
     * <p>Left is east (0.6) and right is north (0.8), which is the way round the 225° Y rotation
     * turns them toward the camera — see the class javadoc.</p>
     */
    private static final double SHADE_UP = 1.0;
    private static final double SHADE_LEFT = 0.6;
    private static final double SHADE_RIGHT = 0.8;

    private IsometricBlockRenderer() {
    }

    /**
     * Renders one cube.
     *
     * @param up   the up-face texture; the left/right faces fall back to it when {@code side} is null
     * @param side the side-face texture, or {@code null} for a block that uses one texture all over
     * @return a {@link #SIZE}×{@link #SIZE} ARGB image with a fully transparent background
     */
    public static BufferedImage render(BufferedImage up, BufferedImage side) {
        if (up == null) {
            throw new IllegalArgumentException("up texture must not be null");
        }
        BufferedImage sides = side != null ? side : up;
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);

        // The cube is taller than it is wide, so height is what fills the icon and the width is
        // derived from it. Centred horizontally; the top and bottom vertices touch the edge.
        double width = SIZE / ASPECT;
        double half = width / 2;            // half the width
        double quarter = width / 4;         // the rhombus' left/right corners sit this far down
        double sideHeight = width * SIDE_HEIGHT_RATIO;
        double left = (SIZE - width) / 2;   // x of the cube's left corner

        // Cube corners, in the order the faces below refer to them:
        //   T (top)    = (left + half, 0)
        //   L (left)   = (left,        quarter)
        //   R (right)  = (left + width, quarter)
        //   M (middle) = (left + half, 2*quarter)   <- where all three faces meet
        // The side faces hang from L-M and M-R down by sideHeight.
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                // Sample the pixel centre, otherwise the shared edges land exactly on a face
                // boundary and round inconsistently, leaving a seam of holes down the middle.
                double px = x + 0.5 - left;
                double py = y + 0.5;

                int argb = 0;
                double[] uv = solve(px, py - quarter, half, -quarter, half, quarter);
                if (inUnitSquare(uv)) {
                    argb = shade(sample(up, uv[0], uv[1]), SHADE_UP);
                } else {
                    uv = solve(px, py - quarter, half, quarter, 0, sideHeight);
                    if (inUnitSquare(uv)) {
                        argb = shade(sample(sides, uv[0], uv[1]), SHADE_LEFT);
                    } else {
                        uv = solve(px - half, py - 2 * quarter, half, -quarter, 0, sideHeight);
                        if (inUnitSquare(uv)) {
                            argb = shade(sample(sides, uv[0], uv[1]), SHADE_RIGHT);
                        }
                    }
                }
                out.setRGB(x, y, argb);
            }
        }
        return out;
    }

    /**
     * Scales one texture to fill the icon, with no projection and no shading.
     *
     * <p>For blocks that are not full cubes — a decorated pot, a lectern, a beacon — the cube
     * projection would draw something the block is not. Their own texture, flat, is honest and
     * still recognisable. The alternative is no icon, which does not merely look worse: an item
     * with no icon is left unregistered, and an unregistered item cannot be named by an injected
     * Bedrock recipe, so its crafts become impossible rather than ugly.</p>
     */
    public static BufferedImage flat(BufferedImage texture) {
        if (texture == null) {
            throw new IllegalArgumentException("texture must not be null");
        }
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                out.setRGB(x, y, sample(texture, (x + 0.5) / SIZE, (y + 0.5) / SIZE));
            }
        }
        return out;
    }

    /**
     * Solves {@code (px, py) = u * (ax, ay) + v * (bx, by)} for {@code (u, v)}.
     *
     * <p>Each face is a parallelogram spanned by two edge vectors, so its inverse mapping is this
     * one 2×2 solve. Returns {@code null} for a degenerate face rather than dividing by zero.</p>
     */
    private static double[] solve(double px, double py, double ax, double ay, double bx, double by) {
        double det = ax * by - ay * bx;
        if (det == 0.0) {
            return null;
        }
        double u = (px * by - py * bx) / det;
        double v = (ax * py - ay * px) / det;
        return new double[] {u, v};
    }

    private static boolean inUnitSquare(double[] uv) {
        return uv != null && uv[0] >= 0.0 && uv[0] < 1.0 && uv[1] >= 0.0 && uv[1] < 1.0;
    }

    /**
     * Nearest-neighbour sample at normalised {@code (u, v)}.
     *
     * <p>Only the first frame of an animated texture is read: Bedrock icons are static, and the
     * strip's later frames live further down the same PNG, so sampling the full height would
     * squash an eight-frame animation into one icon.</p>
     */
    private static int sample(BufferedImage texture, double u, double v) {
        int w = texture.getWidth();
        int frame = Math.min(w, texture.getHeight());
        int sx = clamp((int) (u * w), 0, w - 1);
        int sy = clamp((int) (v * frame), 0, frame - 1);
        return texture.getRGB(sx, sy);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Multiplies RGB by {@code factor}, leaving alpha alone so cut-outs stay cut out. */
    private static int shade(int argb, double factor) {
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) {
            return 0;
        }
        int r = (int) Math.round(((argb >> 16) & 0xFF) * factor);
        int g = (int) Math.round(((argb >> 8) & 0xFF) * factor);
        int b = (int) Math.round((argb & 0xFF) * factor);
        return (a << 24) | (clamp(r, 0, 255) << 16) | (clamp(g, 0, 255) << 8) | clamp(b, 0, 255);
    }
}
