package com.geyserextra.paper.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the filmstrip crop that keeps animated Java textures from reaching
 * Bedrock's item atlas as a squashed strip.
 *
 * <p>Two properties matter more than the arithmetic. First, a texture with no
 * animation metadata must come back <em>byte-identical</em> — the pack's
 * manifest version is a hash of its contents, so a gratuitous re-encode would
 * invalidate every Bedrock client's cached pack for no visual change. Second,
 * anything malformed must degrade to "ship it uncropped" rather than throw,
 * because one bad PNG must never fail a pack build.</p>
 */
@DisplayName("AnimatedTextureStrip filmstrip cropping")
class AnimatedTextureStripTest {

    @TempDir
    Path packDir;

    // Distinct per-frame colours so a crop can be proven to have taken the
    // frame we asked for rather than merely something the right size.
    private static final Color[] FRAME_COLOURS = {
        new Color(0xFF0000), new Color(0x00FF00), new Color(0x0000FF),
        new Color(0xFFFF00), new Color(0xFF00FF), new Color(0x00FFFF),
    };

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /** Writes a {@code frames}-cell grid, each cell filled with its own colour. */
    private Path writeStrip(String name, int frameSize, int columns, int rows)
        throws IOException {
        BufferedImage image = new BufferedImage(
            frameSize * columns, frameSize * rows, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int index = row * columns + col;
                g.setColor(FRAME_COLOURS[index % FRAME_COLOURS.length]);
                g.fillRect(col * frameSize, row * frameSize, frameSize, frameSize);
            }
        }
        g.dispose();
        Path png = packDir.resolve(name);
        ImageIO.write(image, "PNG", png.toFile());
        return png;
    }

    private void writeMcmeta(Path png, String json) throws IOException {
        Files.writeString(
            png.resolveSibling(png.getFileName() + ".mcmeta"), json, StandardCharsets.UTF_8);
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private byte[] cropped(Path png) throws IOException {
        return AnimatedTextureStrip.firstFrame(png, Files.readAllBytes(png), null);
    }

    // -----------------------------------------------------------------
    // the real-world case
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a 12-frame vertical strip is cropped to its first frame")
    void verticalStripIsCroppedToFirstFrame() throws IOException {
        // tf_crystal_apple.png as it actually ships: 16x192, frametime and
        // interpolate only, no explicit frame size and no frames list.
        Path png = writeStrip("apple.png", 16, 1, 12);
        writeMcmeta(png, "{\"animation\":{\"frametime\":4,\"interpolate\":true}}");

        BufferedImage frame = decode(cropped(png));

        assertThat(frame.getWidth()).isEqualTo(16);
        assertThat(frame.getHeight()).isEqualTo(16);
        assertThat(frame.getRGB(8, 8)).isEqualTo(FRAME_COLOURS[0].getRGB());
    }

    @Test
    @DisplayName("the geometry is told the frame size, not the strip size")
    void frameDimensionsReportTheFrameNotTheStrip() throws IOException {
        Path png = writeStrip("apple.png", 16, 1, 12);
        writeMcmeta(png, "{\"animation\":{\"frametime\":4}}");

        // Regression guard for the UV bug: a geometry declaring
        // texture_height=192 against a 16px texture scales every V by 1/12.
        assertThat(AnimatedTextureStrip.frameDimensions(png, 16, 192, null))
            .containsExactly(16, 16);
    }

    @Test
    @DisplayName("a horizontal strip is cropped the same way")
    void horizontalStripIsCropped() throws IOException {
        // Java sizes an unspecified frame as min(width, height) square, so a
        // 64x16 sheet is four frames across rather than one squat frame.
        Path png = writeStrip("row.png", 16, 4, 1);
        writeMcmeta(png, "{\"animation\":{}}");

        BufferedImage frame = decode(cropped(png));

        assertThat(frame.getWidth()).isEqualTo(16);
        assertThat(frame.getHeight()).isEqualTo(16);
        assertThat(frame.getRGB(8, 8)).isEqualTo(FRAME_COLOURS[0].getRGB());
    }

    // -----------------------------------------------------------------
    // frame selection
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("first-frame selection")
    class FirstFrameSelection {

        @Test
        @DisplayName("a frames list starts at its own first entry, not at index 0")
        void framesListPicksItsOwnFirstEntry() throws IOException {
            Path png = writeStrip("strip.png", 16, 1, 4);
            writeMcmeta(png, "{\"animation\":{\"frametime\":3,\"frames\":[3,1,0]}}");

            assertThat(decode(cropped(png)).getRGB(8, 8))
                .isEqualTo(FRAME_COLOURS[3].getRGB());
        }

        @Test
        @DisplayName("the ping-pong list the GUI pieces use still starts at frame 0")
        void pingPongListStartsAtZero() throws IOException {
            // connection_pieces/*.png ship exactly this: 16x64, [0,1,2,3,2,1].
            Path png = writeStrip("piece.png", 16, 1, 4);
            writeMcmeta(png, "{\"animation\":{\"frametime\":3,\"frames\":[0,1,2,3,2,1]}}");

            assertThat(decode(cropped(png)).getRGB(8, 8))
                .isEqualTo(FRAME_COLOURS[0].getRGB());
        }

        @Test
        @DisplayName("the long {index,time} frame form is understood")
        void longFrameFormIsUnderstood() throws IOException {
            Path png = writeStrip("strip.png", 16, 1, 4);
            writeMcmeta(png,
                "{\"animation\":{\"frames\":[{\"index\":2,\"time\":5},{\"index\":0}]}}");

            assertThat(decode(cropped(png)).getRGB(8, 8))
                .isEqualTo(FRAME_COLOURS[2].getRGB());
        }

        @Test
        @DisplayName("a frame index outside the grid falls back to frame 0")
        void outOfRangeFrameFallsBackToZero() throws IOException {
            // Must not throw and must not read outside the raster: a pack
            // author's typo would otherwise take down the whole build.
            Path png = writeStrip("strip.png", 16, 1, 4);
            writeMcmeta(png, "{\"animation\":{\"frames\":[99]}}");

            BufferedImage frame = decode(cropped(png));

            assertThat(frame.getHeight()).isEqualTo(16);
            assertThat(frame.getRGB(8, 8)).isEqualTo(FRAME_COLOURS[0].getRGB());
        }
    }

    // -----------------------------------------------------------------
    // explicit frame sizes
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("explicit animation width/height")
    class ExplicitFrameSize {

        @Test
        @DisplayName("both dimensions given are used verbatim")
        void bothDimensionsAreUsed() throws IOException {
            Path png = writeStrip("grid.png", 16, 2, 2);
            writeMcmeta(png, "{\"animation\":{\"width\":16,\"height\":16}}");

            BufferedImage frame = decode(cropped(png));

            assertThat(frame.getWidth()).isEqualTo(16);
            assertThat(frame.getHeight()).isEqualTo(16);
        }

        @Test
        @DisplayName("width alone keeps the full image height, matching Java")
        void widthAloneKeepsFullHeight() throws IOException {
            // AnimationMetadataSection#calculateFrameSize is asymmetric here.
            // Getting it wrong would crop a 2x1 sheet into quarters.
            Path png = writeStrip("pair.png", 16, 2, 1);
            writeMcmeta(png, "{\"animation\":{\"width\":16}}");

            assertThat(AnimatedTextureStrip.frameDimensions(png, 32, 16, null))
                .containsExactly(16, 16);
        }

        @Test
        @DisplayName("height alone keeps the full image width, matching Java")
        void heightAloneKeepsFullWidth() throws IOException {
            Path png = writeStrip("pair.png", 16, 1, 2);
            writeMcmeta(png, "{\"animation\":{\"height\":16}}");

            assertThat(AnimatedTextureStrip.frameDimensions(png, 16, 32, null))
                .containsExactly(16, 16);
        }
    }

    // -----------------------------------------------------------------
    // everything that must be left alone
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("textures that must pass through untouched")
    class Untouched {

        @Test
        @DisplayName("a PNG with no .mcmeta is returned byte-identical")
        void noMcmetaIsByteIdentical() throws IOException {
            Path png = writeStrip("plain.png", 16, 1, 1);
            byte[] original = Files.readAllBytes(png);

            // Same array instance, not merely equal contents: proves no
            // decode/re-encode round trip touched the pack hash.
            assertThat(AnimatedTextureStrip.firstFrame(png, original, null))
                .isSameAs(original);
        }

        @Test
        @DisplayName("a tall PNG with no .mcmeta is not guessed to be a filmstrip")
        void tallPngWithoutMcmetaIsNotCropped() throws IOException {
            // The exact case the old dimension-based guess broke: a legitimate
            // non-square atlas whose height happens to be a multiple of its
            // width.
            Path png = writeStrip("atlas.png", 16, 1, 4);
            byte[] original = Files.readAllBytes(png);

            assertThat(AnimatedTextureStrip.firstFrame(png, original, null))
                .isSameAs(original);
            assertThat(AnimatedTextureStrip.frameDimensions(png, 16, 64, null))
                .containsExactly(16, 64);
        }

        @Test
        @DisplayName("a .mcmeta without an animation block leaves the texture alone")
        void nonAnimationMcmetaIsIgnored() throws IOException {
            Path png = writeStrip("atlas.png", 16, 1, 4);
            writeMcmeta(png, "{\"texture\":{\"blur\":false,\"clamp\":true}}");
            byte[] original = Files.readAllBytes(png);

            assertThat(AnimatedTextureStrip.firstFrame(png, original, null))
                .isSameAs(original);
        }

        @Test
        @DisplayName("an animated single-frame texture is not re-encoded")
        void singleFrameAnimationIsNotReEncoded() throws IOException {
            // Cropping a 1x1 grid is a no-op picture-wise but would still
            // change the bytes, and with them the manifest version.
            Path png = writeStrip("square.png", 16, 1, 1);
            writeMcmeta(png, "{\"animation\":{\"frametime\":2}}");
            byte[] original = Files.readAllBytes(png);

            assertThat(AnimatedTextureStrip.firstFrame(png, original, null))
                .isSameAs(original);
        }

        @Test
        @DisplayName("a frame size that does not tile the image is refused")
        void nonTilingFrameSizeIsRefused() throws IOException {
            Path png = writeStrip("strip.png", 16, 1, 3);
            writeMcmeta(png, "{\"animation\":{\"height\":7}}");
            byte[] original = Files.readAllBytes(png);

            assertThat(AnimatedTextureStrip.firstFrame(png, original, null))
                .isSameAs(original);
            assertThat(AnimatedTextureStrip.frameDimensions(png, 16, 48, null))
                .containsExactly(16, 48);
        }

        @Test
        @DisplayName("a malformed .mcmeta degrades to shipping the texture as-is")
        void malformedMcmetaDoesNotThrow() throws IOException {
            Path png = writeStrip("strip.png", 16, 1, 4);
            writeMcmeta(png, "{\"animation\": {");
            byte[] original = Files.readAllBytes(png);

            assertThat(AnimatedTextureStrip.firstFrame(png, original, null))
                .isSameAs(original);
        }

        @Test
        @DisplayName("bytes that are not a decodable PNG are handed back unchanged")
        void undecodableBytesAreHandedBack() throws IOException {
            Path png = writeStrip("strip.png", 16, 1, 4);
            writeMcmeta(png, "{\"animation\":{}}");
            byte[] garbage = "not a png".getBytes(StandardCharsets.UTF_8);

            assertThat(AnimatedTextureStrip.firstFrame(png, garbage, null))
                .isSameAs(garbage);
        }
    }
}
