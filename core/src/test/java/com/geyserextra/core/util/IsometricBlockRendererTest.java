package com.geyserextra.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the cube projection. Bedrock cannot render a block model for a custom item, so this bake is
 * the only thing standing between a block-based item and no icon at all — and an item with no icon
 * is left unregistered, which is what silently made its recipes undeliverable.
 */
@DisplayName("IsometricBlockRenderer")
class IsometricBlockRendererTest {

    /**
     * The icon edge. Every coordinate below is expressed as a fraction of it, so raising the bake
     * resolution stays a one-line change instead of a test rewrite.
     */
    private static final int S = IsometricBlockRenderer.SIZE;

    /** Solid 16x16 of one colour, so a face can be identified purely by its shade. */
    private static BufferedImage solid(int rgb) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        return image;
    }

    private static int alphaAt(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >>> 24) & 0xFF;
    }

    @Test
    @DisplayName("renders a square icon of the declared size")
    void rendersFixedSize() {
        BufferedImage icon = IsometricBlockRenderer.render(solid(0xFFFFFF), null);
        assertThat(icon.getWidth()).isEqualTo(IsometricBlockRenderer.SIZE);
        assertThat(icon.getHeight()).isEqualTo(IsometricBlockRenderer.SIZE);
    }

    @Test
    @DisplayName("leaves the four corners transparent so the cube reads as a cube")
    void cornersAreTransparent() {
        // A filled square would be indistinguishable from a flat texture, which is precisely the
        // look this bake exists to avoid.
        BufferedImage icon = IsometricBlockRenderer.render(solid(0xFFFFFF), null);
        assertThat(alphaAt(icon, 0, 0)).as("top-left").isZero();
        assertThat(alphaAt(icon, S - 1, 0)).as("top-right").isZero();
        assertThat(alphaAt(icon, 0, S - 1)).as("bottom-left").isZero();
        assertThat(alphaAt(icon, S - 1, S - 1)).as("bottom-right").isZero();
    }

    @Test
    @DisplayName("covers the cube silhouette without seams between the faces")
    void silhouetteIsFilled() {
        BufferedImage icon = IsometricBlockRenderer.render(solid(0xFFFFFF), null);
        // The vertical centre line crosses all three faces top to bottom. A gap here is the classic
        // symptom of the shared edges rounding into neither face.
        for (int y = 0; y < S; y++) {
            assertThat(alphaAt(icon, S / 2, y)).as("centre column at y=%d", y).isEqualTo(255);
        }
    }

    @Test
    @DisplayName("shades the three faces differently, which is what makes it look three-dimensional")
    void facesUseDistinctShading() {
        BufferedImage icon = IsometricBlockRenderer.render(solid(0xFFFFFF), null);
        // Sample well inside each face rather than near an edge.
        int up = icon.getRGB(S / 2, S * 3 / 16) & 0xFFFFFF;
        int left = icon.getRGB(S * 3 / 16, S * 11 / 16) & 0xFFFFFF;
        int right = icon.getRGB(S * 13 / 16, S * 11 / 16) & 0xFFFFFF;
        assertThat(up).as("up face is brightest").isGreaterThan(right);
        // Java's 225° yaw turns EAST toward the camera on the left and NORTH on the right, and
        // Minecraft shades east/west 0.6 against north/south 0.8 — so the LEFT face is the dark
        // one. Having these the wrong way round is what made the bake read as "not Java".
        assertThat(right).as("right face (north, 0.8) is brighter than left (east, 0.6)")
            .isGreaterThan(left);
    }

    @Test
    @DisplayName("is taller than it is wide, in Java's proportion rather than the pixel-art cube's")
    void silhouetteMatchesJavasProportion() {
        // Java's gui transform is rotation [30, 225, 0]: the silhouette is 2·sin45° = 1.414 edges
        // wide and 2·(0.354) + cos30° = 1.573 edges tall, i.e. 1.112 times taller than wide.
        // The plain pixel-art cube uses a half-width body instead and comes out square — which is
        // what the first deployment shipped, and what was reported back as "Java と違う".
        BufferedImage icon = IsometricBlockRenderer.render(solid(0xFFFFFF), null);
        int minX = S;
        int maxX = -1;
        int minY = S;
        int maxY = -1;
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                if (alphaAt(icon, x, y) != 0) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        double width = maxX - minX + 1;
        double height = maxY - minY + 1;
        assertThat(height / width).as("silhouette height over width")
            .isBetween(1.08, 1.15);
    }

    @Test
    @DisplayName("uses the side texture on the sides and the up texture on top")
    void facesSampleTheirOwnTexture() {
        // Logs are the reason this matters: their top is the ring texture and their sides are bark.
        BufferedImage icon = IsometricBlockRenderer.render(solid(0xFF0000), solid(0x00FF00));
        assertThat(icon.getRGB(S / 2, S * 3 / 16) & 0x00FF00)
            .as("top must not carry the side texture").isZero();
        assertThat(icon.getRGB(S * 3 / 16, S * 11 / 16) & 0xFF0000)
            .as("side must not carry the up texture").isZero();
    }

    @Test
    @DisplayName("keeps transparent source pixels transparent")
    void transparencySurvives() {
        BufferedImage transparent = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        BufferedImage icon = IsometricBlockRenderer.render(transparent, null);
        assertThat(alphaAt(icon, S / 2, S * 3 / 16)).isZero();
        assertThat(alphaAt(icon, S / 2, S * 5 / 8)).isZero();
    }
}
