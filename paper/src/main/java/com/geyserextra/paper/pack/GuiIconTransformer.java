package com.geyserextra.paper.pack;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Bakes a Java {@code display.gui} transform into a Bedrock item-icon PNG
 * at pack build time (applied to the raw Java-pack sprite; no prior trim).
 *
 * <p><b>Why:</b> Java applies {@code display.gui} to inventory renders.
 * Bedrock shows the {@code item_texture.json} sprite verbatim with no
 * transform hook. Baking gui scale/translation (e.g. ValhallaMMO
 * {@code gui.scale = 1.3913}) closes that gap. {@code display.ground} is
 * intentionally not used here — it is for world drops on Java and shrinks
 * shared INV icons when baked.</p>
 *
 * <p><b>2D approximation:</b> a flat sprite cannot reproduce arbitrary 3D
 * rotation. The bake keeps the components a 2D affine can express:</p>
 * <ul>
 *   <li>translation X/Y (Java GUI screen space: +x right, +y up),</li>
 *   <li>Z rotation (in-plane spin),</li>
 *   <li>scale X/Y.</li>
 * </ul>
 * <p>X/Y rotation components are ignored: for flat-lay "generic_sword"-style
 * models the canonical {@code rotation = [90, 0, 0]} merely means "face the
 * camera", which is exactly what the raw sprite already shows. Values other
 * than the face-the-camera set are logged at FINE and skipped rather than
 * failing the pack build.</p>
 *
 * <p>Content pushed beyond the canvas by the transform is clipped — matching
 * Java's own GUI slot cropping for oversized {@code gui.scale} values.</p>
 */
public final class GuiIconTransformer {

    private GuiIconTransformer() {}

    /**
     * Applies the 2D-expressible part of {@code gui} to {@code pngBytes}.
     *
     * <p>Returns the input reference unchanged when the transform is null or
     * a visual no-op for a flat sprite. On any decode/encode failure the
     * ORIGINAL bytes are returned (a slightly mis-scaled icon beats a
     * missing one), so callers never need a try/catch and the pack build
     * can never fail on a malformed texture here.</p>
     *
     * @param pngBytes source PNG bytes (never mutated)
     * @param transform nullable {@code display.ground} or {@code display.gui}
     * @param logger    optional diagnostics sink
     * @return baked PNG bytes, or {@code pngBytes} itself when nothing to do
     */
    public static byte[] bake(byte[] pngBytes, JavaModelDisplay.Transform transform, Logger logger) {
        if (pngBytes == null || transform == null) {
            return pngBytes;
        }
        float[] scale = transform.scale();
        float[] rotation = transform.rotation();
        float[] translation = transform.translation();

        boolean noop = scale[0] == 1f && scale[1] == 1f
            && rotation[2] == 0f
            && translation[0] == 0f && translation[1] == 0f;
        if (noop) {
            return pngBytes;
        }

        warnAboutUnbakeableAxes(rotation, logger);

        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (source == null) {
                if (logger != null) {
                    logger.warning("[GuiIcon] could not decode icon PNG ("
                        + pngBytes.length + " bytes) — skipping gui-transform bake.");
                }
                return pngBytes;
            }

            int w = source.getWidth();
            int h = source.getHeight();
            BufferedImage target = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            var g = target.createGraphics();
            try {
                // Pixel art: nearest neighbour keeps edges crisp instead of
                // smearing them across neighbouring texels.
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

                // Mirror Mojang's ItemTransform.apply composite order
                // (translate ∘ rotate ∘ scale, translate outermost), all
                // about the canvas centre. Java GUI +y is up; image +y is
                // down, hence the sign flips on translation-Y and rotation-Z.
                double cx = w / 2.0;
                double cy = h / 2.0;
                AffineTransform at = new AffineTransform();
                at.translate(
                    translation[0] * (w / 16.0),
                    -translation[1] * (h / 16.0));
                at.translate(cx, cy);
                at.rotate(-Math.toRadians(rotation[2]));
                at.scale(scale[0], scale[1]);
                at.translate(-cx, -cy);
                g.drawImage(source, at, null);
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(pngBytes.length);
            if (!ImageIO.write(target, "png", out)) {
                if (logger != null) {
                    logger.warning("[GuiIcon] PNG encoder unavailable — "
                        + "keeping original icon bytes.");
                }
                return pngBytes;
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException ex) {
            // ImageIO throws RuntimeException on some malformed PNGs; either
            // way the original sprite is the safe fallback.
            if (logger != null) {
                logger.warning("[GuiIcon] gui-transform bake failed ("
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                    + ") — keeping original icon bytes.");
            }
            return pngBytes;
        }
    }

    /**
     * FINE-logs X/Y rotation components the 2D bake cannot express. The
     * face-the-camera set (X ∈ {0, ±90, ±180, ±270}, Y ∈ {0, ±180}) is
     * treated as expected and stays silent — flat-lay models use those to
     * orient the sprite toward the GUI camera, which the raw texture
     * already represents.
     */
    private static void warnAboutUnbakeableAxes(float[] rotation, Logger logger) {
        if (logger == null) {
            return;
        }
        float rx = Math.abs(rotation[0]) % 360f;
        float ry = Math.abs(rotation[1]) % 360f;
        boolean xExpected = rx == 0f || rx == 90f || rx == 180f || rx == 270f;
        boolean yExpected = ry == 0f || ry == 180f;
        if (!xExpected || !yExpected) {
            logger.fine("[GuiIcon] gui rotation [" + rotation[0] + ", " + rotation[1]
                + ", " + rotation[2] + "] has X/Y components a flat sprite cannot "
                + "express — only the Z component is baked; the icon may differ "
                + "slightly from Java's 3D GUI render.");
        }
    }
}
