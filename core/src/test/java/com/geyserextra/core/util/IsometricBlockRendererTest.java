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
    @DisplayName("renders a 32x32 icon")
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
        assertThat(alphaAt(icon, 31, 0)).as("top-right").isZero();
        assertThat(alphaAt(icon, 0, 31)).as("bottom-left").isZero();
        assertThat(alphaAt(icon, 31, 31)).as("bottom-right").isZero();
    }

    @Test
    @DisplayName("covers the cube silhouette without seams between the faces")
    void silhouetteIsFilled() {
        BufferedImage icon = IsometricBlockRenderer.render(solid(0xFFFFFF), null);
        // The vertical centre line crosses all three faces top to bottom. A gap here is the classic
        // symptom of the shared edges rounding into neither face.
        for (int y = 0; y < IsometricBlockRenderer.SIZE; y++) {
            assertThat(alphaAt(icon, 16, y)).as("centre column at y=%d", y).isEqualTo(255);
        }
    }

    @Test
    @DisplayName("shades the three faces differently, which is what makes it look three-dimensional")
    void facesUseDistinctShading() {
        BufferedImage icon = IsometricBlockRenderer.render(solid(0xFFFFFF), null);
        // Sample well inside each face rather than near an edge.
        int up = icon.getRGB(16, 6) & 0xFFFFFF;
        int left = icon.getRGB(6, 22) & 0xFFFFFF;
        int right = icon.getRGB(26, 22) & 0xFFFFFF;
        assertThat(up).as("up face is brightest").isGreaterThan(left);
        assertThat(left).as("left face is brighter than right").isGreaterThan(right);
    }

    @Test
    @DisplayName("uses the side texture on the sides and the up texture on top")
    void facesSampleTheirOwnTexture() {
        // Logs are the reason this matters: their top is the ring texture and their sides are bark.
        BufferedImage icon = IsometricBlockRenderer.render(solid(0xFF0000), solid(0x00FF00));
        assertThat(icon.getRGB(16, 6) & 0x00FF00).as("top must not carry the side texture").isZero();
        assertThat(icon.getRGB(6, 22) & 0xFF0000).as("side must not carry the up texture").isZero();
    }

    @Test
    @DisplayName("keeps transparent source pixels transparent")
    void transparencySurvives() {
        BufferedImage transparent = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        BufferedImage icon = IsometricBlockRenderer.render(transparent, null);
        assertThat(alphaAt(icon, 16, 6)).isZero();
        assertThat(alphaAt(icon, 16, 20)).isZero();
    }
}
