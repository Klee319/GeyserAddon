package com.geyserextra.paper.pack;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ItemIconRenderer")
class ItemIconRendererTest {

    private static final String PACK_PROPERTY = "geyserextra.javaPack";
    private static final Path DEFAULT_PACK = Path.of(
        "..", "..", "trinityforge", "resourcepack", "trinityforge-items");

    /** A 4x4 image whose four quadrants are distinct opaque colours. */
    private static BufferedImage quadrantTexture() {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int argb;
                if (x < 2 && y < 2) {
                    argb = 0xFFFF0000;      // top-left  red
                } else if (x >= 2 && y < 2) {
                    argb = 0xFF00FF00;      // top-right green
                } else if (x < 2) {
                    argb = 0xFF0000FF;      // bottom-left blue
                } else {
                    argb = 0xFFFFFF00;      // bottom-right yellow
                }
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }

    /**
     * A full-face plane on the south side of a unit-thin element spanning the
     * whole 0..16 box, so the render fills the canvas edge to edge.
     */
    private static JavaModelGeometry fullQuad(int faceRotation) {
        JavaModelGeometry.Face face = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#0", faceRotation);
        return new JavaModelGeometry(List.of(new JavaModelGeometry.Element(
            new float[]{0f, 0f, 8f}, new float[]{16f, 16f, 8f}, null,
            Map.of("south", face))));
    }

    @Test
    @DisplayName("an unrotated full-face quad reproduces the texture orientation")
    void unrotatedQuadKeepsTextureOrientation() {
        BufferedImage icon = ItemIconRenderer.render(
            fullQuad(0), null, quadrantTexture(), Logger.getAnonymousLogger());

        assertThat(icon).isNotNull();
        // Java's V axis runs downward in texture space while the GUI Y axis
        // runs up, so texture top-left must land at icon top-left.
        assertThat(cornerColours(icon)).containsExactly(
            0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00);
    }

    @Test
    @DisplayName("face rotation 90 turns the sampled texture a quarter turn")
    void faceRotationTurnsTexture() {
        int[] plain = cornerColours(ItemIconRenderer.render(
            fullQuad(0), null, quadrantTexture(), null));
        int[] turned = cornerColours(ItemIconRenderer.render(
            fullQuad(90), null, quadrantTexture(), null));

        assertThat(turned).isNotEqualTo(plain);
        // A quarter turn is a cyclic permutation of the four quadrants, so the
        // same four colours must still be present — no corner may vanish.
        assertThat(turned).containsExactlyInAnyOrder(
            plain[0], plain[1], plain[2], plain[3]);
    }

    @Test
    @DisplayName("display.gui scale shrinks the drawn area and leaves the border clear")
    void guiScaleShrinksContent() {
        JavaModelDisplay.Transform half = new JavaModelDisplay.Transform(
            new float[]{0f, 0f, 0f}, new float[]{0f, 0f, 0f},
            new float[]{0.5f, 0.5f, 0.5f});

        BufferedImage icon = ItemIconRenderer.render(
            fullQuad(0), half, null, quadrantTexture(), 32, null);

        assertThat(icon).isNotNull();
        // Half scale on a canvas-filling quad: the outer ring must be empty and
        // the centre must still be painted.
        assertThat(icon.getRGB(0, 0) >>> 24).as("corner is transparent").isZero();
        assertThat(icon.getRGB(16, 16) >>> 24).as("centre is opaque").isEqualTo(255);
    }

    @Test
    @DisplayName("an edge-on projection is rejected rather than baked as a sliver")
    void edgeOnProjectionFallsBack() {
        // A plane lying in the XZ plane, viewed with an identity gui transform,
        // is seen exactly edge-on. Java would draw the same sliver, but as an
        // icon it is worse than the atlas, so the renderer must decline.
        JavaModelGeometry.Face face = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#0", 0);
        JavaModelGeometry flatInXz = new JavaModelGeometry(List.of(
            new JavaModelGeometry.Element(
                new float[]{0f, 8f, 0f}, new float[]{16f, 8f, 16f}, null,
                Map.of("up", face))));

        assertThat(ItemIconRenderer.render(flatInXz, null, quadrantTexture(), null))
            .as("edge-on render is rejected")
            .isNull();

        // The same model turned to face the camera (what display.gui [90,0,0]
        // does for every weapon in the reference pack) must still render.
        JavaModelDisplay.Transform faceCamera = new JavaModelDisplay.Transform(
            new float[]{90f, 0f, 0f}, new float[]{0f, 0f, 0f},
            new float[]{1f, 1f, 1f});
        assertThat(ItemIconRenderer.render(
            flatInXz, faceCamera, null, quadrantTexture(), 32, null)).isNotNull();
    }

    @Test
    @DisplayName("a tall non-square texture is sampled whole, not cropped as an animation strip")
    void tallTextureIsNotTreatedAsAnimated() {
        // 4x8: the top half is red, the bottom half blue. A filmstrip guess
        // would sample only the red half and silently lose the blue.
        BufferedImage tall = new BufferedImage(4, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 4; x++) {
                tall.setRGB(x, y, y < 4 ? 0xFFFF0000 : 0xFF0000FF);
            }
        }

        BufferedImage icon = ItemIconRenderer.render(fullQuad(0), null, tall, null);

        assertThat(icon).isNotNull();
        int[] corners = cornerColours(icon);
        assertThat(corners[0]).as("top of the icon samples the top of the PNG")
            .isEqualTo(0xFFFF0000);
        assertThat(corners[2]).as("bottom of the icon samples the bottom of the PNG")
            .isEqualTo(0xFF0000FF);
    }

    @Test
    @DisplayName("each face is painted from the texture layer it names")
    void perFaceTextureReferencesAreHonoured() {
        BufferedImage primary = solid(0xFFFF0000);
        BufferedImage secondary = solid(0xFF00FF00);

        // Left half references #0, right half references #1. Painting both with
        // the primary texture — the old behaviour — makes the icon all red.
        JavaModelGeometry geometry = new JavaModelGeometry(List.of(
            new JavaModelGeometry.Element(
                new float[]{0f, 0f, 8f}, new float[]{8f, 16f, 8f}, null,
                Map.of("south", new JavaModelGeometry.Face(
                    new float[]{0f, 0f, 16f, 16f}, "#0", 0))),
            new JavaModelGeometry.Element(
                new float[]{8f, 0f, 8f}, new float[]{16f, 16f, 8f}, null,
                Map.of("south", new JavaModelGeometry.Face(
                    new float[]{0f, 0f, 16f, 16f}, "#1", 0)))));

        BufferedImage icon = ItemIconRenderer.render(
            geometry, null, ref -> "1".equals(ref) ? secondary : null,
            primary, 32, null);

        assertThat(icon).isNotNull();
        assertThat(icon.getRGB(8, 16)).as("left half uses #0").isEqualTo(0xFFFF0000);
        assertThat(icon.getRGB(24, 16)).as("right half uses #1").isEqualTo(0xFF00FF00);
    }

    @Test
    @DisplayName("a free-rotation element cancels against display.gui instead of projecting edge-on")
    void eulerElementRotationIsApplied() {
        // The shape of the 広辞苑 book model: flat slabs pre-turned about Y by a
        // Blockbench {x, y, z} triple, plus a display.gui that turns them back so
        // the cover faces the camera. Skipping the element half leaves only the
        // gui half, which stands every slab on edge and renders the icon as a few
        // stripes of page edge — the artifact reported on Bedrock 2026-07-28.
        JavaModelGeometry.Face face = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#0", 0);
        JavaModelGeometry preTurned = new JavaModelGeometry(List.of(
            new JavaModelGeometry.Element(
                new float[]{0f, 0f, 8f}, new float[]{16f, 16f, 8f},
                JavaModelGeometry.ElementRotation.ofEuler(
                    new float[]{8f, 8f, 8f}, new float[]{-180f, -90f, 180f}, false),
                Map.of("south", face))));
        JavaModelDisplay.Transform gui = new JavaModelDisplay.Transform(
            new float[]{180f, -90f, 180f}, new float[]{0f, 0f, 0f},
            new float[]{1f, 1f, 1f});

        BufferedImage icon = ItemIconRenderer.render(
            preTurned, gui, null, quadrantTexture(), 32, null);

        assertThat(icon).as("the two rotations cancel, so the face is visible").isNotNull();
        assertThat(coverage(icon)).as("the quad fills the canvas").isGreaterThan(0.9);
    }

    @Test
    @DisplayName("element rotation rescale stretches the perpendicular axes")
    void elementRescaleStretchesGeometry() {
        // A 45-degree rotation with rescale spans sqrt(2) times as much as the
        // same rotation without it, so the drawn area must grow.
        assertThat(coverage(rotatedQuad(true)))
            .as("rescaled render covers more canvas")
            .isGreaterThan(coverage(rotatedQuad(false)));
    }

    private static BufferedImage rotatedQuad(boolean rescale) {
        JavaModelGeometry.Face face = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#0", 0);
        JavaModelGeometry geometry = new JavaModelGeometry(List.of(
            new JavaModelGeometry.Element(
                new float[]{4f, 4f, 8f}, new float[]{12f, 12f, 8f},
                new JavaModelGeometry.ElementRotation(
                    new float[]{8f, 8f, 8f}, "z", 45f, rescale),
                Map.of("south", face))));
        return ItemIconRenderer.render(geometry, null, null, solid(0xFFFFFFFF), 64, null);
    }

    private static BufferedImage solid(int argb) {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }

    private static double coverage(BufferedImage img) {
        return countOpaque(img) / (double) (img.getWidth() * img.getHeight());
    }

    @Test
    @DisplayName("a model with no elements yields null so callers keep the raw sprite")
    void emptyGeometryYieldsNull() {
        assertThat(ItemIconRenderer.render(
            new JavaModelGeometry(List.of()), null, quadrantTexture(), null)).isNull();
        assertThat(ItemIconRenderer.render(null, null, quadrantTexture(), null)).isNull();
    }

    @Test
    @DisplayName("fully transparent texels never claim the depth buffer")
    void transparentTexelsDoNotOccludeed() {
        BufferedImage cutout = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        cutout.setRGB(0, 0, 0x00000000);
        cutout.setRGB(1, 0, 0x00000000);
        cutout.setRGB(0, 1, 0x00000000);
        cutout.setRGB(1, 1, 0x00000000);

        // A transparent quad in FRONT of an opaque one must not hide it.
        JavaModelGeometry.Face front = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#clear", 0);
        JavaModelGeometry.Face back = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#solid", 0);
        JavaModelGeometry geometry = new JavaModelGeometry(List.of(
            new JavaModelGeometry.Element(
                new float[]{0f, 0f, 14f}, new float[]{16f, 16f, 14f}, null,
                Map.of("south", front)),
            new JavaModelGeometry.Element(
                new float[]{0f, 0f, 2f}, new float[]{16f, 16f, 2f}, null,
                Map.of("south", back))));

        BufferedImage solid = quadrantTexture();
        BufferedImage icon = ItemIconRenderer.render(
            geometry, null,
            ref -> "#clear".equals(ref) ? cutout : solid,
            solid, 16, null);

        assertThat(icon).isNotNull();
        assertThat(icon.getRGB(4, 4) >>> 24)
            .as("the opaque quad behind the cut-out still shows")
            .isEqualTo(255);
    }

    @Test
    @DisplayName("renders every 3D model in the operator's real pack to a non-empty icon")
    void rendersRealPackModels() throws IOException {
        Path pack = System.getProperty(PACK_PROPERTY) != null
            ? Path.of(System.getProperty(PACK_PROPERTY))
            : DEFAULT_PACK;
        Assumptions.assumeTrue(Files.isDirectory(pack.resolve("assets")),
            "Java pack not available at " + pack.toAbsolutePath());

        Logger logger = Logger.getLogger(ItemIconRendererTest.class.getName());
        JavaPackReader reader = new JavaPackReader(pack, "AUTO", logger, false);
        Path dump = Path.of("build", "icon-render-dump");
        Files.createDirectories(dump);

        int rendered = 0;
        int blank = 0;
        int multiTexture = 0;
        for (Map.Entry<JavaPackReader.CmdKey, JavaPackReader.JavaModelDefinition> e
                : reader.scan().entrySet()) {
            JavaPackReader.JavaModelDefinition def = e.getValue();
            if (def.geometry() == null || !def.geometry().hasElements()
                || def.textureFile() == null || !Files.isRegularFile(def.textureFile())) {
                continue;
            }
            BufferedImage texture = ImageIO.read(def.textureFile().toFile());
            if (texture == null) {
                continue;
            }
            JavaModelDisplay.Transform gui =
                def.display() != null ? def.display().gui() : null;
            Map<String, BufferedImage> perFace = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Path> t : def.textureFiles().entrySet()) {
                BufferedImage img = ImageIO.read(t.getValue().toFile());
                if (img != null) {
                    perFace.put(t.getKey(), img);
                }
            }
            multiTexture += perFace.size() > 1 ? 1 : 0;
            BufferedImage icon = ItemIconRenderer.render(
                def.geometry(), gui, perFace::get, texture,
                ItemIconRenderer.DEFAULT_SIZE, logger);

            assertThat(icon).as("icon for " + e.getKey()).isNotNull();
            int opaque = countOpaque(icon);
            if (opaque == 0) {
                blank++;
            } else {
                rendered++;
            }
            ImageIO.write(icon, "PNG",
                dump.resolve(sanitize(e.getKey().toString()) + ".png").toFile());
        }

        Assumptions.assumeTrue(rendered + blank > 0, "pack has no 3D models");
        // Every 3D model must produce visible pixels: a blank icon is strictly
        // worse than the atlas it replaces.
        assertThat(blank).as("3D models that rendered to nothing").isZero();
        assertThat(rendered).isGreaterThan(0);
        // The reference pack has models whose faces reference a second texture
        // layer; if the reader stops carrying the map, those silently regress
        // to painting the primary artwork everywhere.
        assertThat(multiTexture).as("models resolving more than one texture layer")
            .isGreaterThan(0);
    }

    // -----------------------------------------------------------------

    /** Colours at the four inner corners, clockwise from top-left. */
    private static int[] cornerColours(BufferedImage img) {
        int n = img.getWidth();
        int lo = n / 4;
        int hi = n - 1 - n / 4;
        return new int[]{
            img.getRGB(lo, lo), img.getRGB(hi, lo),
            img.getRGB(lo, hi), img.getRGB(hi, hi)
        };
    }

    private static int countOpaque(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) > 8) {
                    n++;
                }
            }
        }
        return n;
    }

    private static String sanitize(String raw) {
        return raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
