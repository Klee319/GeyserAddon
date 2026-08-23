package com.geyserextra.core.util;

import com.geyserextra.core.util.JavaBlockModel.Direction;
import com.geyserextra.core.util.JavaBlockModel.Element;
import com.geyserextra.core.util.JavaBlockModel.Face;
import com.geyserextra.core.util.JavaBlockModel.Rotation;
import com.geyserextra.core.util.JavaBlockModel.Transform;

import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * Draws a Java block model the way the Java client draws it in an inventory slot.
 *
 * <p>Bedrock cannot render a model for a custom item — its icon is a flat PNG — so the icon has to
 * be baked. {@link IsometricBlockRenderer} did that by drawing a cube, which is right for the ~90%
 * of bases that <em>are</em> cubes and wrong for every lectern, anvil and beacon. This renderer
 * reads Minecraft's own model geometry instead, so the shape is whatever Java says it is.</p>
 *
 * <h2>The transform, derived rather than guessed</h2>
 * <p>Java renders the item at {@code translate(x+8, y+8)}, {@code scale(16, -16, 16)}, then the
 * model's {@code display.gui} transform, against a model whose coordinates have been divided by 16
 * and shifted by -0.5. Composed, a vertex {@code m} in model space lands at</p>
 * <pre>
 *   v = R · (S · (m/16 - 0.5)) + T/16
 *   px = SIZE/2 + v.x · SIZE
 *   py = SIZE/2 - v.y · SIZE
 * </pre>
 * <p>where {@code R = Rx · Ry · Rz}. The order matters and is easy to get backwards: JOML's
 * {@code rotationXYZ}, which Minecraft uses, <b>post</b>-multiplies, so Z is applied to the vertex
 * first and X last. Composing them the other way round shears the top face into a parallelogram —
 * a cube's top must come out as a symmetric 2:1 rhombus, which is the quickest check that the
 * order is right.</p>
 *
 * <p>Because the scale is fixed rather than fitted, a model smaller than a full block comes out
 * smaller in the icon, exactly as it does in Java. Fitting each model to the canvas would blow a
 * lectern up to the size of a stone block.</p>
 *
 * <p>Sampling is nearest-neighbour with no anti-aliasing, which is also what Java does; the output
 * is pixel art, and smoothing it would only make it look unlike the game.</p>
 */
public final class JavaModelRenderer {

    /** Edge of the rendered icon. Shared with {@link IsometricBlockRenderer} so the two agree. */
    public static final int SIZE = IsometricBlockRenderer.SIZE;

    /**
     * Colour multiplied into faces carrying a {@code tintindex}.
     *
     * <p>In game that is a biome lookup; in an inventory slot Java uses a fixed default, which is
     * why a grass block's top is green on the item and grey in the texture file. Leaving it out
     * makes tinted blocks come out washed grey.</p>
     */
    private static final int DEFAULT_TINT = 0x7CBD6B;

    private JavaModelRenderer() {
    }

    /**
     * @return a {@link #SIZE}×{@link #SIZE} ARGB icon, or {@code null} when the model has no
     *     geometry to draw — Minecraft renders some blocks (decorated pot, conduit, chest, heads)
     *     from code rather than from JSON, and those models carry only a particle texture. The
     *     caller falls back rather than shipping an empty icon.
     */
    public static BufferedImage render(JavaBlockModel model) {
        if (model == null || model.elements().isEmpty()) {
            return null;
        }
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        double[] depth = new double[SIZE * SIZE];
        java.util.Arrays.fill(depth, Double.NEGATIVE_INFINITY);

        for (Element element : model.elements()) {
            for (Map.Entry<Direction, Face> entry : element.faces().entrySet()) {
                drawFace(out, depth, model, element, entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static void drawFace(BufferedImage out, double[] depth, JavaBlockModel model,
                                 Element element, Direction direction, Face face) {
        if (face.texture() == null) {
            return;
        }
        double[][] corners = cornersOf(direction, element.from(), element.to());
        double[][] screen = new double[4][];
        for (int i = 0; i < 4; i++) {
            double[] p = corners[i];
            if (element.rotation() != null) {
                p = rotateElement(p, element.rotation());
            }
            screen[i] = project(p, model.gui());
        }
        double[][] uv = uvCorners(face);
        double shade = shadeFor(model, element, direction);

        // Two triangles. A quad from a rotated element is not necessarily planar in screen space,
        // and splitting is what keeps the seam between the halves invisible.
        rasterize(out, depth, screen, uv, face, shade, 0, 1, 2);
        rasterize(out, depth, screen, uv, face, shade, 0, 2, 3);
    }

    /**
     * Brightness for a face. Uses the rotated normal rather than the declared direction: an element
     * turned 22.5° (a lectern's desk) faces somewhere else once rotated, and Java shades what it
     * actually faces.
     */
    private static double shadeFor(JavaBlockModel model, Element element, Direction direction) {
        if (!model.shaded() || !element.shade()) {
            return 1.0;
        }
        double[] normal = {direction.nx, direction.ny, direction.nz};
        if (element.rotation() != null) {
            Rotation rotation = element.rotation();
            double[] origin = {0, 0, 0};
            normal = rotateAbout(normal, rotation.angle(), rotation.axis(), origin, false);
        }
        Direction nearest = direction;
        double best = Double.NEGATIVE_INFINITY;
        for (Direction candidate : Direction.values()) {
            double dot = normal[0] * candidate.nx + normal[1] * candidate.ny
                + normal[2] * candidate.nz;
            if (dot > best) {
                best = dot;
                nearest = candidate;
            }
        }
        return nearest.shade;
    }

    /** Model space to pixels. See the class javadoc for the derivation. */
    private static double[] project(double[] modelSpace, Transform gui) {
        double x = (modelSpace[0] / 16.0 - 0.5) * gui.scale()[0];
        double y = (modelSpace[1] / 16.0 - 0.5) * gui.scale()[1];
        double z = (modelSpace[2] / 16.0 - 0.5) * gui.scale()[2];

        double[] r = gui.rotation();
        double[] rotated = rotateZ(new double[] {x, y, z}, r[2]);
        rotated = rotateY(rotated, r[1]);
        rotated = rotateX(rotated, r[0]);

        double vx = rotated[0] + gui.translation()[0] / 16.0;
        double vy = rotated[1] + gui.translation()[1] / 16.0;
        double vz = rotated[2] + gui.translation()[2] / 16.0;

        return new double[] {SIZE / 2.0 + vx * SIZE, SIZE / 2.0 - vy * SIZE, vz};
    }

    private static double[] rotateX(double[] p, double degrees) {
        double a = Math.toRadians(degrees);
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new double[] {p[0], p[1] * c - p[2] * s, p[1] * s + p[2] * c};
    }

    private static double[] rotateY(double[] p, double degrees) {
        double a = Math.toRadians(degrees);
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new double[] {p[0] * c + p[2] * s, p[1], -p[0] * s + p[2] * c};
    }

    private static double[] rotateZ(double[] p, double degrees) {
        double a = Math.toRadians(degrees);
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new double[] {p[0] * c - p[1] * s, p[0] * s + p[1] * c, p[2]};
    }

    private static double[] rotateElement(double[] p, Rotation rotation) {
        return rotateAbout(p, rotation.angle(), rotation.axis(), rotation.origin(),
            rotation.rescale());
    }

    /**
     * Rotates a point about one axis through {@code origin}.
     *
     * <p>{@code rescale} stretches the two perpendicular axes by {@code 1/cos(angle)} so the
     * rotated element still meets its neighbours — Minecraft uses it for the diagonal parts of
     * rails and hoppers, and without it those come out visibly too thin.</p>
     */
    private static double[] rotateAbout(double[] p, double angle, char axis, double[] origin,
                                        boolean rescale) {
        double x = p[0] - origin[0];
        double y = p[1] - origin[1];
        double z = p[2] - origin[2];
        double a = Math.toRadians(angle);
        double c = Math.cos(a);
        double s = Math.sin(a);
        double scale = rescale ? 1.0 / Math.cos(Math.toRadians(Math.abs(angle))) : 1.0;

        double rx;
        double ry;
        double rz;
        switch (axis) {
            case 'x' -> {
                rx = x;
                ry = (y * c - z * s) * scale;
                rz = (y * s + z * c) * scale;
            }
            case 'y' -> {
                rx = (x * c + z * s) * scale;
                ry = y;
                rz = (-x * s + z * c) * scale;
            }
            default -> {
                rx = (x * c - y * s) * scale;
                ry = (x * s + y * c) * scale;
                rz = z;
            }
        }
        return new double[] {rx + origin[0], ry + origin[1], rz + origin[2]};
    }

    /**
     * The four corners of a face, in the order Minecraft pairs with the UV corners
     * {@code (u1,v1) (u1,v2) (u2,v2) (u2,v1)}.
     */
    private static double[][] cornersOf(Direction direction, double[] from, double[] to) {
        double x1 = from[0];
        double y1 = from[1];
        double z1 = from[2];
        double x2 = to[0];
        double y2 = to[1];
        double z2 = to[2];
        return switch (direction) {
            case DOWN -> new double[][] {
                {x1, y1, z2}, {x1, y1, z1}, {x2, y1, z1}, {x2, y1, z2}};
            case UP -> new double[][] {
                {x1, y2, z1}, {x1, y2, z2}, {x2, y2, z2}, {x2, y2, z1}};
            case NORTH -> new double[][] {
                {x2, y2, z1}, {x2, y1, z1}, {x1, y1, z1}, {x1, y2, z1}};
            case SOUTH -> new double[][] {
                {x1, y2, z2}, {x1, y1, z2}, {x2, y1, z2}, {x2, y2, z2}};
            case WEST -> new double[][] {
                {x1, y2, z1}, {x1, y1, z1}, {x1, y1, z2}, {x1, y2, z2}};
            case EAST -> new double[][] {
                {x2, y2, z2}, {x2, y1, z2}, {x2, y1, z1}, {x2, y2, z1}};
        };
    }

    /** UV corners in vertex order, rotated by the face's {@code rotation}. */
    private static double[][] uvCorners(Face face) {
        double u1 = face.uv()[0];
        double v1 = face.uv()[1];
        double u2 = face.uv()[2];
        double v2 = face.uv()[3];
        double[][] corners = {{u1, v1}, {u1, v2}, {u2, v2}, {u2, v1}};
        int steps = ((face.rotation() % 360) + 360) % 360 / 90;
        double[][] rotated = new double[4][];
        for (int i = 0; i < 4; i++) {
            rotated[i] = corners[(i + steps) % 4];
        }
        return rotated;
    }

    /** Barycentric triangle fill with a depth test, interpolating the texture coordinates. */
    private static void rasterize(BufferedImage out, double[] depth, double[][] screen,
                                  double[][] uv, Face face, double shade, int i0, int i1, int i2) {
        double[] a = screen[i0];
        double[] b = screen[i1];
        double[] c = screen[i2];
        double area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        if (area == 0.0) {
            return;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int maxX = Math.min(SIZE - 1, (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int maxY = Math.min(SIZE - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5;
                double py = y + 0.5;
                double w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area;
                double w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area;
                double w2 = 1.0 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) {
                    continue;
                }
                double z = w0 * a[2] + w1 * b[2] + w2 * c[2];
                int index = y * SIZE + x;
                if (z < depth[index]) {
                    continue;
                }
                double u = w0 * uv[i0][0] + w1 * uv[i1][0] + w2 * uv[i2][0];
                double v = w0 * uv[i0][1] + w1 * uv[i1][1] + w2 * uv[i2][1];
                int argb = sample(face.texture(), u / 16.0, v / 16.0);
                if ((argb >>> 24) == 0) {
                    continue; // a cut-out must not claim the pixel, or it punches a hole
                }
                depth[index] = z;
                out.setRGB(x, y, shade(argb, shade, face.tinted()));
            }
        }
    }

    /**
     * Nearest-neighbour sample at normalised coordinates.
     *
     * <p>Only the first frame of an animated texture is read: the strip's later frames sit further
     * down the same PNG, so using the full height would squash the whole animation into the icon.
     */
    private static int sample(BufferedImage texture, double u, double v) {
        int w = texture.getWidth();
        int frame = Math.min(w, texture.getHeight());
        int sx = clamp((int) Math.floor(u * w), 0, w - 1);
        int sy = clamp((int) Math.floor(v * frame), 0, frame - 1);
        return texture.getRGB(sx, sy);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int shade(int argb, double factor, boolean tinted) {
        int a = (argb >>> 24) & 0xFF;
        double r = ((argb >> 16) & 0xFF) * factor;
        double g = ((argb >> 8) & 0xFF) * factor;
        double b = (argb & 0xFF) * factor;
        if (tinted) {
            r = r * ((DEFAULT_TINT >> 16) & 0xFF) / 255.0;
            g = g * ((DEFAULT_TINT >> 8) & 0xFF) / 255.0;
            b = b * (DEFAULT_TINT & 0xFF) / 255.0;
        }
        return (a << 24)
            | (clamp((int) Math.round(r), 0, 255) << 16)
            | (clamp((int) Math.round(g), 0, 255) << 8)
            | clamp((int) Math.round(b), 0, 255);
    }
}
