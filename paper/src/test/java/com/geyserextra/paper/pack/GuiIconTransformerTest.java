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
    @DisplayName("2x scale magnifies about the centre, pushing an off-centre pixel outward")
    void scaleEnlargesAboutCentre() throws IOException {
        // 8x8 canvas, single red pixel just off-centre at (5, 4). Scale 2 about
        // the centre (4, 4) turns it into a 2x2 block starting at (6, 4) and
        // vacates its original position. See scaleAboveOneClipsAtTheCanvasEdge
        // for why the canvas is not grown or the scale clamped to fit.
        BufferedImage img = transparentImage(8, 8);
        img.setRGB(5, 4, OPAQUE_RED);
        byte[] baked = GuiIconTransformer.bake(toPng(img), guiScale(2f), null);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(baked));
        assertThat(out.getWidth()).isEqualTo(8);
        assertThat(out.getHeight()).isEqualTo(8);
        assertThat(out.getRGB(6, 4)).isEqualTo(OPAQUE_RED);
        assertThat(out.getRGB(5, 4) >>> 24).as("original texel is vacated").isZero();
        assertThat(countOpaquePixels(out)).as("one texel becomes a 2x2 block").isEqualTo(4);
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
    @DisplayName("oversized full-bleed scale is fit to canvas so edges stay opaque")
    void oversizedContentFitsToCanvas() throws IOException {
        byte[] baked = GuiIconTransformer.bake(solidPng(8, 8, OPAQUE_RED), guiScale(2f), null);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(baked));
        // Canvas stays 8x8; fit clamps scale 2 to effective 1:1 so nothing clips.
        assertThat(out.getWidth()).isEqualTo(8);
        assertThat(out.getHeight()).isEqualTo(8);
        assertThat(out.getRGB(0, 0)).isEqualTo(OPAQUE_RED);
        assertThat(out.getRGB(7, 7)).isEqualTo(OPAQUE_RED);
    }

    @Test
    @DisplayName("full-bleed scale 3 bake matches scale 1 (fit fills canvas without clipping)")
    void fullBleedScale3MatchesScale1() throws IOException {
        byte[] source = solidPng(8, 8, OPAQUE_RED);
        BufferedImage scale1 = ImageIO.read(new ByteArrayInputStream(
            GuiIconTransformer.bake(source, guiScale(1f), null)));
        BufferedImage scale3 = ImageIO.read(new ByteArrayInputStream(
            GuiIconTransformer.bake(source, guiScale(3f), null)));

        assertThat(scale3.getWidth()).isEqualTo(scale1.getWidth());
        assertThat(scale3.getHeight()).isEqualTo(scale1.getHeight());
        for (int y = 0; y < scale1.getHeight(); y++) {
            for (int x = 0; x < scale1.getWidth(); x++) {
                assertThat(scale3.getRGB(x, y)).isEqualTo(scale1.getRGB(x, y));
            }
        }
    }

    @Test
    @DisplayName("scale above 1 clips at the canvas edge instead of being fitted to it")
    void scaleAboveOneClipsAtTheCanvasEdge() throws IOException {
        // 16x16 with opaque fill in [2..13]×[2..13]. Scale 2 about the centre
        // magnifies that block past every border, so the canvas ends up fully
        // opaque and the outer bands are lost.
        //
        // Why clip and not fit: Java renders the model at the declared gui.scale
        // and the 16x16 inventory slot crops whatever overflows. Clamping the
        // scale so the content fits would make the Bedrock icon disagree with
        // the Java one for exactly the packs that set scale > 1 on purpose.
        BufferedImage img = transparentImage(16, 16);
        for (int y = 2; y <= 13; y++) {
            for (int x = 2; x <= 13; x++) {
                img.setRGB(x, y, OPAQUE_RED);
            }
        }
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(
            GuiIconTransformer.bake(toPng(img), guiScale(2f), null)));

        assertThat(out.getRGB(0, 0)).as("magnified past the top-left border")
            .isEqualTo(OPAQUE_RED);
        assertThat(out.getRGB(15, 15)).as("magnified past the bottom-right border")
            .isEqualTo(OPAQUE_RED);
        assertThat(countOpaquePixels(out)).isEqualTo(16 * 16);
    }

    @Test
    @DisplayName("scale above 1 paints more than scale below 1")
    void scaleAboveOnePaintsMoreThanScaleBelowOne() throws IOException {
        // A single texel: scale 2 turns it into a 2x2 block, scale 0.5 drops it
        // off the nearest-neighbour grid entirely. Both are magnification and
        // minification about the canvas centre, with no fit step in between.
        BufferedImage img = transparentImage(8, 8);
        img.setRGB(5, 4, OPAQUE_RED);
        BufferedImage enlarged = ImageIO.read(new ByteArrayInputStream(
            GuiIconTransformer.bake(toPng(img), guiScale(2f), null)));
        BufferedImage shrunk = ImageIO.read(new ByteArrayInputStream(
            GuiIconTransformer.bake(toPng(img), guiScale(0.5f), null)));

        assertThat(countOpaquePixels(enlarged)).isEqualTo(4);
        assertThat(countOpaquePixels(shrunk)).isLessThan(countOpaquePixels(enlarged));
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

    private static int countOpaquePixels(BufferedImage img) {
        int count = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }
}
