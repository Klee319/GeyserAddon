package com.geyserextra.core.util;

import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * A Java block model, flattened to exactly what drawing it needs.
 *
 * <p>Deliberately free of any JSON type: the loader that reads Minecraft's assets lives in the
 * build's {@code generate} source set, while the rasteriser lives here and can be unit-tested with
 * hand-built geometry and no network. Everything is in Minecraft's own model space — coordinates in
 * 0..16 per axis, UVs in 0..16 of the texture.</p>
 *
 * @param elements the cuboids to draw, in declaration order
 * @param gui      the {@code display.gui} transform; blocks vary (a lectern uses scale 0.6, a plain
 *                 cube 0.625), so it is carried per model rather than assumed
 * @param shaded   {@code gui_light: "side"} — directional face shading. {@code "front"} models are
 *                 drawn flat-lit, which is what Java does for them
 */
public record JavaBlockModel(java.util.List<Element> elements, Transform gui, boolean shaded) {

    /** The six faces, with the normal and the brightness Minecraft multiplies that face by. */
    public enum Direction {
        DOWN(0, -1, 0, 0.5),
        UP(0, 1, 0, 1.0),
        NORTH(0, 0, -1, 0.8),
        SOUTH(0, 0, 1, 0.8),
        WEST(-1, 0, 0, 0.6),
        EAST(1, 0, 0, 0.6);

        public final double nx;
        public final double ny;
        public final double nz;
        public final double shade;

        Direction(double nx, double ny, double nz, double shade) {
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.shade = shade;
        }
    }

    /**
     * One cuboid.
     *
     * @param from     lower corner, model space
     * @param to       upper corner, model space
     * @param rotation optional rotation about one axis, or {@code null}
     * @param shade    the element's {@code shade} flag; {@code false} draws it flat-lit
     * @param faces    the faces that are actually drawn — an absent face is absent, not transparent
     */
    public record Element(double[] from, double[] to, Rotation rotation, boolean shade,
                          Map<Direction, Face> faces) {
    }

    /**
     * A face's texture and its mapping.
     *
     * @param texture  the resolved image; the first frame only, for animated textures
     * @param uv       {@code [u1, v1, u2, v2]} in 0..16
     * @param rotation 0/90/180/270, rotating the texture on the face
     * @param tinted   the face carries a {@code tintindex}; Java multiplies a biome colour in
     */
    public record Face(BufferedImage texture, double[] uv, int rotation, boolean tinted) {
    }

    /** {@code rotation} on an element: {@code angle} degrees about {@code axis} through {@code origin}. */
    public record Rotation(double angle, char axis, double[] origin, boolean rescale) {
    }

    /** A {@code display} entry. Translation is in model units (Minecraft scales it by 1/16). */
    public record Transform(double[] rotation, double[] translation, double[] scale) {

        /** Minecraft's default for a block item, from {@code block/block}. */
        public static Transform blockGui() {
            return new Transform(new double[] {30, 225, 0}, new double[] {0, 0, 0},
                new double[] {0.625, 0.625, 0.625});
        }
    }
}
