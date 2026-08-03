package com.geyserextra.paper.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IconSpriteNormalizer trim-to-content (no stretch)")
class IconSpriteNormalizerTest {

    private static final int OPAQUE_RED = 0xFFFF0000;

    @Test
    @DisplayName("padded content is cropped to opaque bbox without upscale")
    void trimCropsWithoutUpscale() throws IOException {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(1, 1, OPAQUE_RED);
        img.setRGB(2, 1, OPAQUE_RED);
        img.setRGB(1, 2, OPAQUE_RED);
        byte[] png = toPng(img);
        byte[] outBytes = IconSpriteNormalizer.trimAndFit(png, null);
        assertThat(outBytes).isNotSameAs(png);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(outBytes));
        assertThat(out.getWidth()).isEqualTo(2);
        assertThat(out.getHeight()).isEqualTo(2);
        assertThat(countOpaque(out)).isEqualTo(3);
    }

    @Test
    @DisplayName("32x32 full-bleed is a no-op")
    void fullBleedHiResIsNoop() throws IOException {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                img.setRGB(x, y, OPAQUE_RED);
            }
        }
        byte[] png = toPng(img);
        assertThat(IconSpriteNormalizer.forInventoryIcon(png, null)).isSameAs(png);
    }

    @Test
    @DisplayName("32x32 single-pixel pad crops to 1x1 (not stretched to 32)")
    void paddedHiResCropsToOnePixel() throws IOException {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(8, 8, OPAQUE_RED);
        byte[] outBytes = IconSpriteNormalizer.forInventoryIcon(toPng(img), null);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(outBytes));
        assertThat(out.getWidth()).isEqualTo(1);
        assertThat(out.getHeight()).isEqualTo(1);
        assertThat(countOpaque(out)).isEqualTo(1);
    }

    private static int countOpaque(BufferedImage out) {
        int opaque = 0;
        for (int y = 0; y < out.getHeight(); y++) {
            for (int x = 0; x < out.getWidth(); x++) {
                if (((out.getRGB(x, y) >>> 24) & 0xFF) > 8) {
                    opaque++;
                }
            }
        }
        return opaque;
    }

    private static byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
