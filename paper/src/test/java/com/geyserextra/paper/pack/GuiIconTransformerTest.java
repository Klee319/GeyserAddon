package com.geyserextra.paper.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GuiIconTransformer display.gui bake")
class GuiIconTransformerTest {

    private static final int OPAQUE_RED = 0xFFFF0000;

    @Test
    @DisplayName("null gui transform returns the original byte array reference")
    void nullGuiIsNoop() throws IOException {
        byte[] png = solidPng(4, 4, OPAQUE_RED);
        assertThat(GuiIconTransformer.bake(png, null, null)).isSameAs(png);
    }

    @Test
    @DisplayName("visual no-op transform (scale 1, rotZ 0, gui rotX 90) returns the original reference")
    void identityLikeGuiIsNoop() throws IOException {
        byte[] png = solidPng(4, 4, OPAQUE_RED);
        JavaModelDisplay.Transform gui = new JavaModelDisplay.Transform(
            new float[]{90f, 0f, 0f},   // face-the-camera X rotation only
            new float[]{0f, 0f, 5f},    // Z translation is depth — invisible in 2D
            new float[]{1f, 1f, 3f});   // Z scale is depth — invisible in 2D
        assertThat(GuiIconTransformer.bake(png, gui, null)).isSameAs(png);
    }

    @Test
    @DisplayName("2x scale enlarges content about the canvas centre at unchanged canvas size")
    void scaleEnlargesAboutCentre() throws IOException {
        // 8x8 canvas, single red pixel just off-centre at (5, 4). Centre is
        // (4, 4); scaling 2x about the centre maps x=5..6 → x=6..8.
        BufferedImage img = transparentImage(8, 8);
        img.setRGB(5, 4, OPAQUE_RED);
        byte[] baked = GuiIconTransformer.bake(toPng(img), guiScale(2f), null);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(baked));
        assertThat(out.getWidth()).isEqualTo(8);
        assertThat(out.getHeight()).isEqualTo(8);
        assertThat(out.getRGB(6, 4)).isEqualTo(OPAQUE_RED);
        // The original pixel position is left of the enlarged copy and must
        // now be transparent.
        assertThat(out.getRGB(3, 4) >>> 24).isZero();
    }

    @Test
    @DisplayName("translation moves content right (+x) and up (-y in image space)")
    void translationMovesContent() throws IOException {
        // 16x16 canvas: translation is in Java GUI units where 16 units span
        // the canvas, so [4, 2, 0] moves 4px right and 2px up.
        BufferedImage img = transparentImage(16, 16);
        img.setRGB(8, 8, OPAQUE_RED);
        JavaModelDisplay.Transform gui = new JavaModelDisplay.Transform(
            new float[]{0f, 0f, 0f},
            new float[]{4f, 2f, 0f},
            new float[]{1f, 1f, 1f});
        byte[] baked = GuiIconTransformer.bake(toPng(img), gui, null);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(baked));
        assertThat(out.getRGB(12, 6)).isEqualTo(OPAQUE_RED);
        assertThat(out.getRGB(8, 8) >>> 24).isZero();
    }

    @Test
    @DisplayName("content scaled past the canvas edge is clipped, matching Java's GUI slot crop")
    void oversizedContentIsClipped() throws IOException {
        byte[] baked = GuiIconTransformer.bake(solidPng(8, 8, OPAQUE_RED), guiScale(2f), null);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(baked));
        // Canvas stays 8x8 even though the content doubled.
        assertThat(out.getWidth()).isEqualTo(8);
        assertThat(out.getHeight()).isEqualTo(8);
        // Still fully covered in red (the enlarged fill overflows and clips).
        assertThat(out.getRGB(0, 0)).isEqualTo(OPAQUE_RED);
        assertThat(out.getRGB(7, 7)).isEqualTo(OPAQUE_RED);
    }

    @Test
    @DisplayName("corrupt PNG bytes fall back to the original bytes without throwing")
    void corruptPngFallsBack() {
        byte[] garbage = {1, 2, 3, 4, 5};
        assertThat(GuiIconTransformer.bake(garbage, guiScale(2f), null)).isSameAs(garbage);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static JavaModelDisplay.Transform guiScale(float s) {
        return new JavaModelDisplay.Transform(
            new float[]{0f, 0f, 0f},
            new float[]{0f, 0f, 0f},
            new float[]{s, s, s});
    }

    private static BufferedImage transparentImage(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    private static byte[] solidPng(int w, int h, int argb) throws IOException {
        BufferedImage img = transparentImage(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb);
            }
        }
        return toPng(img);
    }

    private static byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
