package com.geyserextra.paper.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Understands Java edition's animated-texture filmstrips — a PNG holding N
 * frames stacked in a grid, described by a sibling {@code <name>.png.mcmeta}.
 *
 * <p><b>Why this exists:</b> Bedrock has no per-item equivalent. Its
 * {@code flipbook_textures.json} keys off {@code terrain_texture.json}
 * shortnames and is documented for blocks only, so a filmstrip handed to the
 * item atlas is drawn as-is: all frames squashed into one 16x16 slot. Until
 * that changes, the correct picture is a single frame, and everything
 * downstream — the item icon, the {@code display.gui} bake, and the
 * attachable's {@code texture_width}/{@code texture_height}/UVs — must agree
 * on which frame and how tall it is.</p>
 *
 * <p>That agreement is why the crop happens here rather than in each consumer.
 * An earlier revision of {@link ItemIconRenderer} guessed at filmstrips from
 * the image dimensions alone while {@link BedrockGeometryConverter} scaled UVs
 * by the full PNG height, so the icon and the 3D model sampled the same
 * texture differently. Reading the {@code .mcmeta} — the only authoritative
 * source — and cropping once at the point the bytes enter the pipeline means
 * no consumer has to know filmstrips exist.</p>
 *
 * <p>Frame geometry follows {@code AnimationMetadataSection#calculateFrameSize}
 * exactly, including its asymmetric handling of a lone {@code width} or
 * {@code height}, so a pack that renders correctly on Java crops to the frame
 * Java would have shown first.</p>
 */
public final class AnimatedTextureStrip {

    private AnimatedTextureStrip() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Where a filmstrip's frames sit inside the source image.
     *
     * @param frameWidth      width of one frame in pixels
     * @param frameHeight     height of one frame in pixels
     * @param columns         frames per row; 1 for the usual vertical strip
     * @param frameCount      total frames in the grid
     * @param firstFrameIndex grid index of the frame Java displays first,
     *                        taken from {@code animation.frames[0]} when that
     *                        list is present
     */
    public record Layout(
        int frameWidth,
        int frameHeight,
        int columns,
        int frameCount,
        int firstFrameIndex
    ) {}

    /**
     * Reads the sibling {@code .mcmeta} and works out the frame grid.
     *
     * @return the layout, or {@code null} when the texture is not animated —
     *         no {@code .mcmeta}, no {@code animation} block, an unreadable or
     *         malformed file, or a grid that resolves to a single frame. A
     *         null return means "leave this PNG completely alone".
     */
    public static Layout read(Path png, int imageWidth, int imageHeight, Logger logger) {
        if (png == null || imageWidth <= 0 || imageHeight <= 0) {
            return null;
        }
        JsonObject animation = readAnimationBlock(png, logger);
        if (animation == null) {
            return null;
        }

        // Ported from AnimationMetadataSection#calculateFrameSize. The lone-
        // width and lone-height cases are deliberately asymmetric there
        // (width alone keeps the full image height, height alone keeps the
        // full image width); reproducing that is the whole point.
        int frameWidth;
        int frameHeight;
        Integer declaredWidth = positiveInt(animation, "width");
        Integer declaredHeight = positiveInt(animation, "height");
        if (declaredWidth != null) {
            frameWidth = declaredWidth;
            frameHeight = declaredHeight != null ? declaredHeight : imageHeight;
        } else if (declaredHeight != null) {
            frameWidth = imageWidth;
            frameHeight = declaredHeight;
        } else {
            int square = Math.min(imageWidth, imageHeight);
            frameWidth = square;
            frameHeight = square;
        }

        // A declared frame larger than the image, or one that doesn't tile it,
        // is a broken pack. Cropping on a guess would silently mangle the
        // artwork, so decline and let the texture through untouched.
        if (frameWidth <= 0 || frameHeight <= 0
            || frameWidth > imageWidth || frameHeight > imageHeight
            || imageWidth % frameWidth != 0 || imageHeight % frameHeight != 0) {
            if (logger != null) {
                logger.warning("[AutoPack] " + png.getFileName()
                    + " declares an animation whose " + frameWidth + "x" + frameHeight
                    + " frame does not tile its " + imageWidth + "x" + imageHeight
                    + " image — leaving the texture uncropped.");
            }
            return null;
        }

        int columns = imageWidth / frameWidth;
        int rows = imageHeight / frameHeight;
        int frameCount = columns * rows;
        if (frameCount <= 1) {
            // Animated metadata on a single-frame image: nothing to crop, and
            // returning a layout would force a pointless PNG re-encode that
            // changes the pack hash for no visual difference.
            return null;
        }

        int firstFrameIndex = firstFrameIndex(animation, frameCount, png, logger);
        return new Layout(frameWidth, frameHeight, columns, frameCount, firstFrameIndex);
    }

    /**
     * Crops {@code pngBytes} down to the single frame Java shows first.
     *
     * <p>Returns the argument array unchanged whenever the texture is not an
     * animated filmstrip or anything at all goes wrong, so a pack that has no
     * animations builds byte-for-byte as it did before this class existed and
     * one bad PNG can never fail the build.</p>
     *
     * @param source    path the bytes came from, used to find the {@code .mcmeta}
     * @param pngBytes  the raw PNG
     * @return the cropped frame, or {@code pngBytes} itself
     */
    public static byte[] firstFrame(Path source, byte[] pngBytes, Logger logger) {
        if (pngBytes == null || pngBytes.length == 0) {
            return pngBytes;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (image == null) {
                return pngBytes;
            }
            Layout layout = read(source, image.getWidth(), image.getHeight(), logger);
            if (layout == null) {
                return pngBytes;
            }
            int index = layout.firstFrameIndex();
            int x = (index % layout.columns()) * layout.frameWidth();
            int y = (index / layout.columns()) * layout.frameHeight();
            BufferedImage frame = new BufferedImage(
                layout.frameWidth(), layout.frameHeight(), BufferedImage.TYPE_INT_ARGB);
            // getSubimage shares the parent raster, which ImageIO would then
            // write with the parent's dimensions. Copy into a standalone image
            // so the encoded PNG really is one frame tall.
            frame.createGraphics().drawImage(
                image.getSubimage(x, y, layout.frameWidth(), layout.frameHeight()),
                0, 0, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(frame, "PNG", out)) {
                return pngBytes;
            }
            if (logger != null) {
                logger.fine("[AutoPack] cropped animated texture " + source.getFileName()
                    + " to frame " + index + " of " + layout.frameCount()
                    + " (" + layout.frameWidth() + "x" + layout.frameHeight() + ")");
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException ex) {
            if (logger != null) {
                logger.warning("[AutoPack] failed to crop animated texture " + source
                    + " (" + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                    + ") — shipping the full filmstrip unchanged.");
            }
            return pngBytes;
        }
    }

    /**
     * Frame dimensions the rest of the pipeline should treat this PNG as
     * having, so a geometry descriptor's {@code texture_width} /
     * {@code texture_height} match the frame {@link #firstFrame} actually
     * writes rather than the whole strip.
     *
     * @return {@code [frameWidth, frameHeight]}, or the supplied dimensions
     *         unchanged when the texture is not animated
     */
    public static int[] frameDimensions(
        Path source, int imageWidth, int imageHeight, Logger logger
    ) {
        Layout layout = read(source, imageWidth, imageHeight, logger);
        if (layout == null) {
            return new int[]{imageWidth, imageHeight};
        }
        return new int[]{layout.frameWidth(), layout.frameHeight()};
    }

    // -----------------------------------------------------------------
    // .mcmeta parsing
    // -----------------------------------------------------------------

    /**
     * @return the {@code animation} object from {@code <png>.mcmeta}, or
     *         {@code null} when there is none. An empty {@code animation: {}}
     *         is a valid animation (all defaults) and returns an empty object,
     *         which is why the caller must null-check rather than isEmpty-check.
     */
    private static JsonObject readAnimationBlock(Path png, Logger logger) {
        Path meta = png.resolveSibling(png.getFileName().toString() + ".mcmeta");
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        try {
            String text = Files.readString(meta, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            if (root == null || !root.isJsonObject()) {
                return null;
            }
            JsonElement animation = root.getAsJsonObject().get("animation");
            return animation != null && animation.isJsonObject()
                ? animation.getAsJsonObject()
                : null;
        } catch (IOException | RuntimeException ex) {
            // A .mcmeta we cannot read is treated as absent: the texture ships
            // as-is, which is exactly the pre-existing behaviour.
            if (logger != null) {
                logger.warning("[AutoPack] could not read " + meta.getFileName()
                    + " (" + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                    + ") — treating " + png.getFileName() + " as a still texture.");
            }
            return null;
        }
    }

    /**
     * Index of the first frame in the display order. Java plays
     * {@code animation.frames} in the order given, so a ping-pong list like
     * {@code [0,1,2,3,2,1]} still starts at its first entry; without the list
     * it starts at grid index 0.
     */
    private static int firstFrameIndex(
        JsonObject animation, int frameCount, Path png, Logger logger
    ) {
        JsonElement framesElement = animation.get("frames");
        if (framesElement == null || !framesElement.isJsonArray()) {
            return 0;
        }
        JsonArray frames = framesElement.getAsJsonArray();
        if (frames.isEmpty()) {
            return 0;
        }
        JsonElement first = frames.get(0);
        Integer index = null;
        if (first.isJsonPrimitive() && first.getAsJsonPrimitive().isNumber()) {
            index = first.getAsInt();
        } else if (first.isJsonObject()) {
            // The long form is {"index": N, "time": T}; only the index matters
            // when a single frame is all we can show.
            index = nonNegativeInt(first.getAsJsonObject(), "index");
        }
        if (index == null || index < 0 || index >= frameCount) {
            if (logger != null) {
                logger.warning("[AutoPack] " + png.getFileName()
                    + " names a first animation frame outside its " + frameCount
                    + "-frame grid — using frame 0.");
            }
            return 0;
        }
        return index;
    }

    private static Integer positiveInt(JsonObject object, String member) {
        Integer value = readInt(object, member);
        return value != null && value > 0 ? value : null;
    }

    private static Integer nonNegativeInt(JsonObject object, String member) {
        Integer value = readInt(object, member);
        return value != null && value >= 0 ? value : null;
    }

    private static Integer readInt(JsonObject object, String member) {
        JsonElement element = object.get(member);
        if (element == null || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
