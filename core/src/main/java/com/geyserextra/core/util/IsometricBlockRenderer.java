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
 * <p>The output is the standard 2:1 pixel-art cube, which is what that transform produces at icon
 * size: a rhombus for the up face and two parallelograms for the visible sides. Faces are shaded
 * with Minecraft's own per-direction multipliers, so the result matches what the Java client draws
 * rather than merely resembling it.</p>
 *
 * <p>Pure function of its inputs and free of any Bukkit / Geyser type, so the geometry can be
 * asserted in a unit test without a server or a pack.</p>
 */
public final class IsometricBlockRenderer {

    /**
     * Edge length of the rendered icon.
     *
     * <p>64, not the texture's own 16: the icon is a projection, so a texel's edges land on the
     * diagonal of the output. At 32 that diagonal has one output pixel per two texels and the cube
     * came out visibly ragged — the first deployment was reported as "低画質で歪". 64 gives four
     * output pixels per 16-texture texel, so every face edge lands on a whole pixel and the client
     * still has resolution left when it scales the slot up.</p>
     */
    public static final int SIZE = 64;

    /**
     * Minecraft's face shading. The client multiplies each face's colour by these before drawing,
     * which is the entire reason a flat-coloured block still reads as a cube.
     */
    private static final double SHADE_UP = 1.0;
    private static final double SHADE_LEFT = 0.8;
    private static final double SHADE_RIGHT = 0.6;

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

        int half = SIZE / 2;      // 16 — half the width, and the height of one rhombus
        int quarter = SIZE / 4;   // 8  — the vertical offset of the rhombus' left/right corners

        // Cube corners, in the order the faces below refer to them:
        //   T (top)      = (half, 0)
        //   L (left)     = (0, quarter)
        //   R (right)    = (SIZE, quarter)
        //   M (middle)   = (half, half)          <- where all three faces meet
        // The side faces hang from L-M and M-R down by half the height.
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                // Sample the pixel centre, otherwise the shared edges land exactly on a face
                // boundary and round inconsistently, leaving a seam of holes down the middle.
                double px = x + 0.5;
                double py = y + 0.5;

                int argb = 0;
                double[] uv = solve(px - 0, py - quarter, half, -quarter, half, quarter);
                if (inUnitSquare(uv)) {
                    argb = shade(sample(up, uv[0], uv[1]), SHADE_UP);
                } else {
                    uv = solve(px - 0, py - quarter, half, quarter, 0, half);
                    if (inUnitSquare(uv)) {
                        argb = shade(sample(sides, uv[0], uv[1]), SHADE_LEFT);
                    } else {
                        uv = solve(px - half, py - half, half, -quarter, 0, half);
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
