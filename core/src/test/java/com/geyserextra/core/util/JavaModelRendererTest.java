package com.geyserextra.core.util;

import com.geyserextra.core.util.JavaBlockModel.Direction;
import com.geyserextra.core.util.JavaBlockModel.Element;
import com.geyserextra.core.util.JavaBlockModel.Face;
import com.geyserextra.core.util.JavaBlockModel.Rotation;
import com.geyserextra.core.util.JavaBlockModel.Transform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Java-model bake.
 *
 * <p>The properties asserted here are the ones that failed silently while the icons were being
 * drawn as a hand-made cube: the projection's proportion, which face lands on which side, and — the
 * reason this renderer exists at all — that a model smaller than a full block stays smaller instead
 * of being blown up to fill the icon.
 */
@DisplayName("JavaModelRenderer")
class JavaModelRendererTest {

    private static final int S = JavaModelRenderer.SIZE;

    private static BufferedImage solid(int rgb) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        return image;
    }

    /** A cuboid with the same white texture all over, in Minecraft's 0..16 model space. */
    private static Element box(double x1, double y1, double z1, double x2, double y2, double z2,
                               Rotation rotation) {
        Map<Direction, Face> faces = new EnumMap<>(Direction.class);
        for (Direction direction : Direction.values()) {
            faces.put(direction,
                new Face(solid(0xFFFFFF), new double[] {0, 0, 16, 16}, 0, false));
        }
        return new Element(new double[] {x1, y1, z1}, new double[] {x2, y2, z2},
            rotation, true, faces);
    }

    private static JavaBlockModel model(Element... elements) {
        return new JavaBlockModel(List.of(elements), Transform.blockGui(), true);
    }

    private static int[] boundingBox(BufferedImage icon) {
        int minX = S;
        int maxX = -1;
        int minY = S;
        int maxY = -1;
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                if (((icon.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return new int[] {minX, minY, maxX, maxY};
    }

    @Test
    @DisplayName("a full cube keeps Java's proportion: 1.112 times taller than wide")
    void fullCubeMatchesJavasProportion() {
        // From rotation [30, 225, 0]: width 2*sin45 = 1.414 edges, height 2*0.354 + cos30 = 1.573.
        // The plain pixel-art cube is square instead, which is what the first bake shipped.
        int[] bounds = boundingBox(JavaModelRenderer.render(model(box(0, 0, 0, 16, 16, 16, null))));
        double width = bounds[2] - bounds[0] + 1;
        double height = bounds[3] - bounds[1] + 1;
        assertThat(height / width).isBetween(1.08, 1.15);
    }

    @Test
    @DisplayName("shows north on the right and east on the left, as Java's 225° yaw does")
    void northIsOnTheRight() {
        // Minecraft shades north/south 0.8 and east/west 0.6, so the side that comes out brighter
        // is the north one. Getting this backwards mirrors every asymmetric block — a furnace would
        // show its front on the wrong side — and nothing else in the pipeline would notice.
        BufferedImage icon = JavaModelRenderer.render(model(box(0, 0, 0, 16, 16, 16, null)));
        int left = icon.getRGB(S * 3 / 16, S * 11 / 16) & 0xFF;
        int right = icon.getRGB(S * 13 / 16, S * 11 / 16) & 0xFF;
        int up = icon.getRGB(S / 2, S * 3 / 16) & 0xFF;
        assertThat(up).as("up face is brightest").isGreaterThan(right);
        assertThat(right).as("right is north (0.8), left is east (0.6)").isGreaterThan(left);
        assertThat((double) left / right).as("the ratio is exactly 0.6/0.8")
            .isBetween(0.73, 0.77);
    }

    @Test
    @DisplayName("a model smaller than a block renders smaller, instead of filling the icon")
    void smallModelsStaySmall() {
        // This is the whole reason for reading Java's models. Fitting each model to the canvas
        // would draw a lectern at the size of a stone block and lose the only cue that they differ.
        int[] full = boundingBox(JavaModelRenderer.render(model(box(0, 0, 0, 16, 16, 16, null))));
        int[] slab = boundingBox(JavaModelRenderer.render(model(box(0, 0, 0, 16, 8, 16, null))));
        assertThat(slab[3] - slab[1]).as("a half-height slab is shorter")
            .isLessThan(full[3] - full[1]);
        assertThat(slab[2] - slab[0]).as("but just as wide").isEqualTo(full[2] - full[0]);
    }

    @Test
    @DisplayName("draws nothing for a model Minecraft renders from code")
    void returnsNullWithoutGeometry() {
        // Decorated pots, conduits, chests, beds and heads have no elements — only a particle
        // texture. Returning null lets the caller fall back rather than ship an empty icon.
        assertThat(JavaModelRenderer.render(new JavaBlockModel(List.of(), Transform.blockGui(),
            true))).isNull();
        assertThat(JavaModelRenderer.render(null)).isNull();
    }

    @Test
    @DisplayName("applies an element's own rotation")
    void elementRotationChangesTheShape() {
        BufferedImage upright = JavaModelRenderer.render(model(box(0, 6, 0, 16, 10, 16, null)));
        BufferedImage tilted = JavaModelRenderer.render(model(box(0, 6, 0, 16, 10, 16,
            new Rotation(45, 'x', new double[] {8, 8, 8}, false))));
        int[] a = boundingBox(upright);
        int[] b = boundingBox(tilted);
        // Not "taller": for this slab the 45° tilt grows the y-extent but shrinks the
        // z-extent by almost exactly as much in projection, so the height comparison came
        // down to sub-pixel rounding (it held at SIZE=64 by luck and tied 73=73 at 128).
        // What rotation must guarantee is a different silhouette, so pin exactly that.
        assertThat(java.util.Arrays.equals(a, b))
            .as("tilting the slab changes its silhouette bounding box %s vs %s",
                java.util.Arrays.toString(a), java.util.Arrays.toString(b))
            .isFalse();
    }

    @Test
    @DisplayName("the nearer face wins the pixel")
    void depthTestKeepsTheNearFace() {
        // Without a depth test the last element drawn wins, so a lectern's base would paint over
        // its desk depending only on declaration order.
        Map<Direction, Face> red = new EnumMap<>(Direction.class);
        red.put(Direction.UP, new Face(solid(0xFF0000), new double[] {0, 0, 16, 16}, 0, false));
        Map<Direction, Face> blue = new EnumMap<>(Direction.class);
        blue.put(Direction.UP, new Face(solid(0x0000FF), new double[] {0, 0, 16, 16}, 0, false));

        // The blue lid sits above the red one and is declared first; it must still win.
        JavaBlockModel stacked = new JavaBlockModel(List.of(
            new Element(new double[] {0, 12, 0}, new double[] {16, 12, 16}, null, true, blue),
            new Element(new double[] {0, 4, 0}, new double[] {16, 4, 16}, null, true, red)),
            Transform.blockGui(), true);

        BufferedImage icon = JavaModelRenderer.render(stacked);
        int centre = icon.getRGB(S / 2, S / 2) & 0xFFFFFF;
        assertThat(centre & 0xFF).as("blue channel").isGreaterThan(centre >> 16 & 0xFF);
    }
}
