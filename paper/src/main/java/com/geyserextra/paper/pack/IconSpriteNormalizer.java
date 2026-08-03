package com.geyserextra.paper.pack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Normalizes Bedrock inventory/drop icon sprites by trimming transparent
 * margins only (crop to opaque bounding box).
 *
 * <p>Does <b>not</b> upscale into the original canvas — nearest-neighbour
 * stretch of small art created missing/blocky pixels. Bedrock already scales
 * the sprite to the inventory slot, so a tight crop fills the slot without
 * resample artifacts. Also does not downsample (e.g. 32→16).</p>
 *
 * <p>Attachable UVs must keep sampling the raw PNG — callers write the
 * normalized bytes to a separate {@code *_gui.png} and point
 * {@code item_texture.json} at that path only.</p>
 */
public final class IconSpriteNormalizer {

    /** Alpha above this counts as opaque content for the bounding box. */
    private static final int ALPHA_THRESHOLD = 8;

    private IconSpriteNormalizer() {}

    /**
     * Trims transparent margins (crop only). Alias of {@link #forInventoryIcon}.
     */
    public static byte[] trimAndFit(byte[] pngBytes, Logger logger) {
        return forInventoryIcon(pngBytes, logger);
    }

    /**
     * Inventory-icon normalization: crop to opaque content at native pixel
     * density (no stretch, no downsample).
     */
    public static byte[] forInventoryIcon(byte[] pngBytes, Logger logger) {
        if (pngBytes == null || pngBytes.length == 0) {
            return pngBytes;
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (source == null) {
                if (logger != null) {
                    logger.warning("[IconNorm] could not decode icon PNG ("
                        + pngBytes.length + " bytes) — skipping normalize.");
                }
                return pngBytes;
            }

            int w = source.getWidth();
            int h = source.getHeight();
            if (w <= 0 || h <= 0) {
                return pngBytes;
            }

            int minX = w;
            int minY = h;
            int maxX = -1;
            int maxY = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int alpha = (source.getRGB(x, y) >>> 24) & 0xFF;
                    if (alpha > ALPHA_THRESHOLD) {
                        if (x < minX) minX = x;
                        if (y < minY) minY = y;
                        if (x > maxX) maxX = x;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxX < minX) {
                return pngBytes;
            }

            int cropW = maxX - minX + 1;
            int cropH = maxY - minY + 1;
            // Already fills the canvas — keep original bytes (no resample).
            if (minX == 0 && minY == 0 && cropW == w && cropH == h) {
                return pngBytes;
            }

            BufferedImage cropped = source.getSubimage(minX, minY, cropW, cropH);
            // Copy into a fresh ARGB buffer: getSubimage shares the parent
            // raster and some PNG writers emit wrong bounds from subimages.
            BufferedImage target = new BufferedImage(cropW, cropH, BufferedImage.TYPE_INT_ARGB);
            var g = target.createGraphics();
            try {
                g.drawImage(cropped, 0, 0, null);
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(pngBytes.length);
            if (!ImageIO.write(target, "png", out)) {
                if (logger != null) {
                    logger.warning("[IconNorm] PNG encoder unavailable — "
                        + "keeping original icon bytes.");
                }
                return pngBytes;
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException ex) {
            if (logger != null) {
                logger.warning("[IconNorm] normalize failed ("
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                    + ") — keeping original icon bytes.");
            }
            return pngBytes;
        }
    }
}
