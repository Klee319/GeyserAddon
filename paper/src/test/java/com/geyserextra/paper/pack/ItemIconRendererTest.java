package com.geyserextra.paper.pack;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
        // runs up, so texture top-left must land at icon top-left. Every
        // corner shares the same shading factor (one flat south-facing quad;
        // see southFaceGetsPartialShade() below for the 0.5130679 derivation),
        // so the colours here are the originals with each channel scaled by
        // 0x83 = round(255 * 0.5130679).
        assertThat(cornerColours(icon)).containsExactly(
            0xFF830000, 0xFF008300, 0xFF000083, 0xFF838300);
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
        // Shaded by the same south-face factor as unrotatedQuadKeepsTextureOrientation
        // (0x83 = round(255 * 0.5130679)) — this test is about sampling the
        // right row of the PNG, not about the shading arithmetic, so it just
        // carries the same scaled channel through.
        assertThat(corners[0]).as("top of the icon samples the top of the PNG")
            .isEqualTo(0xFF830000);
        assertThat(corners[2]).as("bottom of the icon samples the bottom of the PNG")
            .isEqualTo(0xFF000083);
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
        // Both elements are south-facing at identity gui, so both get the
        // same shading factor as unrotatedQuadKeepsTextureOrientation
        // (0x83 = round(255 * 0.5130679)); this test is about which texture
        // layer paints which half, not about the shading arithmetic.
        assertThat(icon.getRGB(8, 16)).as("left half uses #0").isEqualTo(0xFF830000);
        assertThat(icon.getRGB(24, 16)).as("right half uses #1").isEqualTo(0xFF008300);
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

    // -----------------------------------------------------------------
    // shading arithmetic
    //
    // ItemIconRenderer.shadeFactor is package-visible specifically so these
    // tests can pin it against hand-fed normals: an axis-aligned normal like
    // (1,0,0) cannot be exercised through render() at identity display.gui,
    // because a face whose normal has zero Z is by construction edge-on to
    // this renderer's orthographic-along-Z camera (zero screen area — the
    // same degeneracy edgeOnProjectionFallsBack tests above), and there is no
    // rotation that makes such a face visible without also rotating its
    // normal away from the axis-aligned value being pinned here.
    //
    // Expected factors were computed independently in Python from the two
    // citable sources documented on ItemIconRenderer.LIGHT0_3D and
    // LIGHT0_FLAT: DIFFUSE_LIGHT_0/1 = normalize(0.2,1,-0.7) /
    // normalize(-0.2,1,0.7), pre-rotated by the matrix each of
    // GlStateManager#setupGui3DDiffuseLighting / #setupGuiFlatDiffuseLighting
    // builds. As stored:
    //   LIGHT0_3D   ~= (-0.93344, -0.26269, -0.24430)
    //   LIGHT1_3D   ~= (-0.10357, -0.97661, +0.18845)
    //   LIGHT0_FLAT ~= (-0.22252, -0.17150, +0.95973)
    //   LIGHT1_FLAT ~= (-0.21501, -0.97183, +0.09657)
    // shadeFactor dots these against flipY(normal), not the caller's normal
    // (see its javadoc), so a hand-fed n=(0,1,0) meets the light's -Y term.
    // Working the 3D rig through that for the three cases below:
    //   n=( 1,0,0): dot(L0)=-0.9334, dot(L1)=-0.1036 (both <=0) -> 0.4 exactly
    //   n=(-1,0,0): dot(L0)=+0.9334, dot(L1)=+0.1036 -> (1.037)*0.6+0.4=1.022 -> clamped 1.0
    //   n=( 0,0,1): dot(L0)=-0.2443, dot(L1)=+0.1884 -> 0.1884*0.6+0.4=0.51307 (matches the
    //               south-face corners pinned in unrotatedQuadKeepsTextureOrientation)
    // Only LIGHT0_3D/LIGHT1_3D carry a baked scaling(1,-1,1); the flat matrix
    // has none, and that asymmetry is vanilla's, not a porting slip — it is
    // unobservable in vanilla because the flat rig only ever meets flat quads
    // whose normal has no Y term, but we apply it to the reference pack's 49
    // front-lit models that do have real geometry.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a face normal with both dot products negative is fully unlit at the 0.4 ambient floor")
    void fullyUnlitFaceHitsAmbientFloor() {
        // Both LIGHT0_3D.x and LIGHT1_3D.x are negative (the rotated light
        // pair points generally toward -X here), so against n=(1,0,0),
        // max(0,dot) is 0 for both lights regardless of floating-point noise
        // in the light vectors — this pins the additive term at exactly 0,
        // not just "small".
        assertThat(ItemIconRenderer.shadeFactor(new double[]{1, 0, 0}, false))
            .as("unlit normal lands on the ambient floor")
            .isEqualTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("a face normal with a large combined dot product clamps at full brightness")
    void clampedFaceStaysAtFullBrightness() {
        // n=(-1,0,0) is the negation of the unlit case above, so both dot
        // products flip positive and sum to ~1.037, which after
        // *0.6+0.4 = ~1.022 would overshoot 1.0 without the min() clamp.
        assertThat(ItemIconRenderer.shadeFactor(new double[]{-1, 0, 0}, false))
            .as("clamped normal is capped at 1.0, not left at the ~1.022 raw sum")
            .isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("the south face's normal lands at the specific mid-range factor 0.51307")
    void southFaceGetsPartialShade() {
        // n=(0,0,1) is exactly the unrotated south face's outward normal
        // (verified geometrically in ItemIconRenderer#faceNormal's javadoc),
        // so this also documents the multiplier behind
        // unrotatedQuadKeepsTextureOrientation's 0x83 corner colours.
        assertThat(ItemIconRenderer.shadeFactor(new double[]{0, 0, 1}, false))
            .isEqualTo(0.5130679, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("the top of an item is lit and its underside is not, never the other way round")
    void shadeIsBrighterOnTopThanUnderneath() {
        // The one assertion in this group that is about physics rather than
        // arithmetic, and the only one that can catch a Y-sign error: the
        // three tests above all use normals whose Y component is zero, so
        // they pass identically whether the light rig is right way up or
        // upside down. It shipped upside down once. Mojang's light matrix
        // ends in scaling(1, -1, 1), which compensates for the GUI pose stack
        // having flipped the geometry to Y-down; this renderer keeps pos[]
        // Y-up and flips only in toScreenY, so carrying that scale over
        // inverted the Y term and lit every icon from below - top face 0.4,
        // bottom face 1.0, exactly reversed.
        double up = ItemIconRenderer.shadeFactor(new double[]{0, 1, 0}, false);
        double down = ItemIconRenderer.shadeFactor(new double[]{0, -1, 0}, false);
        assertThat(up).as("top face").isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(down).as("underside")
            .isEqualTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(up).as("the top must be the brighter of the two").isGreaterThan(down);
    }

    @Test
    @DisplayName("a camera-facing surface is untouched under the flat rig but dimmed under the 3D one")
    void frontLitCameraFacingSurfaceIsUnshaded() {
        // This is the whole reason gui_light is plumbed through at all. A
        // camera-facing normal is what almost every front-lit model presents
        // at identity display.gui, and Java leaves it at full brightness -
        // that is why an inventory sprite is pixel-identical to its source
        // PNG. Picking the wrong rig here does not merely shift a highlight,
        // it multiplies the entire icon by 0.513, which is *darker* than the
        // unshaded output this renderer produced before shading existed.
        assertThat(ItemIconRenderer.shadeFactor(new double[]{0, 0, 1}, true))
            .as("front-lit, camera-facing")
            .isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ItemIconRenderer.shadeFactor(new double[]{0, 0, 1}, false))
            .as("the same surface under the 3D rig, for contrast")
            .isEqualTo(0.5130679, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("the flat rig is a genuinely different rig, not the 3D one under another name")
    void flatRigDiffersFromTheThreeDimensionalRigAwayFromTheCamera() {
        // Guards the frontLit flag actually reaching a different light pair.
        // If someone collapsed the two rigs back into one, the camera-facing
        // assertion above would still be the only thing to fail and could be
        // "fixed" by tweaking a constant; these off-axis normals pin that the
        // two matrices genuinely diverge.
        assertThat(ItemIconRenderer.shadeFactor(new double[]{-1, 0, 0}, true))
            .as("-X is partially lit under the flat rig but clamps under the 3D one")
            .isEqualTo(0.6625187, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(ItemIconRenderer.shadeFactor(new double[]{0, 0, -1}, true))
            .as("the back face sits on the ambient floor under the flat rig")
            .isEqualTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ItemIconRenderer.shadeFactor(new double[]{0, 0, -1}, false))
            .as("the 3D rig still finds light on that same back face")
            .isEqualTo(0.5465801, org.assertj.core.data.Offset.offset(1e-6));
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
        // Sanity check requested alongside the shading change: render each
        // model a second time against a flat white texture so every opaque
        // pixel's channel value *is* the shading factor (255*factor), then
        // track the global min/max across the whole pack. If normals came
        // out backwards this would read as uniformly ~0.4 (everything facing
        // away from both lights) or uniformly ~1.0 (everything facing both);
        // a real spread confirms per-face normals vary as expected.
        BufferedImage white = solid(0xFFFFFFFF);
        int shadeMin = 255;
        int shadeMax = 0;
        java.util.Map<String, int[]> perIconRange = new java.util.LinkedHashMap<>();
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

            BufferedImage whiteIcon = ItemIconRenderer.render(
                def.geometry(), gui, ignored -> white, white,
                ItemIconRenderer.DEFAULT_SIZE, logger);
            if (whiteIcon != null) {
                int iconMin = 255;
                int iconMax = 0;
                for (int y = 0; y < whiteIcon.getHeight(); y++) {
                    for (int x = 0; x < whiteIcon.getWidth(); x++) {
                        int argb = whiteIcon.getRGB(x, y);
                        if ((argb >>> 24) <= 8) {
                            continue;
                        }
                        int channel = (argb >> 16) & 0xFF; // r==g==b on a white texel
                        iconMin = Math.min(iconMin, channel);
                        iconMax = Math.max(iconMax, channel);
                    }
                }
                if (iconMax > 0) {
                    perIconRange.put(e.getKey().toString(), new int[]{iconMin, iconMax});
                    shadeMin = Math.min(shadeMin, iconMin);
                    shadeMax = Math.max(shadeMax, iconMax);
                }
            }
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

        System.out.println("[IconRender shading] min factor=" + (shadeMin / 255.0)
            + " (byte " + shadeMin + "), max factor=" + (shadeMax / 255.0)
            + " (byte " + shadeMax + ") across " + (rendered + blank) + " real 3D icons");
        // Name a couple of concrete icons and their own per-icon spread, so a
        // uniformly-flat individual icon (the actual failure mode a wrong
        // normal produces — see the class javadoc) is visible even if it
        // happened to average out against the rest of the pack.
        perIconRange.entrySet().stream().limit(3).forEach(en ->
            System.out.println("[IconRender shading]   " + en.getKey()
                + ": min=" + (en.getValue()[0] / 255.0) + " max=" + (en.getValue()[1] / 255.0)));
        // 0x66 = round(255*0.4) is the ambient floor; nothing can render
        // darker than that, and nothing can exceed the min(1,...) clamp.
        assertThat(shadeMin).as("no pixel is darker than the 0.4 ambient floor")
            .isGreaterThanOrEqualTo(0x66 - 1);
        assertThat(shadeMax).as("no pixel exceeds the clamp").isLessThanOrEqualTo(255);
        // Guard against the exact backwards-normal failure mode this check
        // exists to catch: every face landing on the same value.
        assertThat(shadeMax).as("shading is not uniformly flat across the pack")
            .isGreaterThan(shadeMin);
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

    @Nested
    @DisplayName("output size follows the source resolution")
    class OutputSize {

        @Test
        @DisplayName("a high-resolution texture is never rendered smaller than itself")
        void highResolutionArtIsNotDownscaled() {
            // The actual defect behind "size and centre are right but the
            // quality is bad": every source above 64px was being resampled
            // down to 64. The reference pack ships 128, 256 and 512px items.
            assertThat(ItemIconRenderer.sizeFor(128)).isEqualTo(128);
            assertThat(ItemIconRenderer.sizeFor(256)).isEqualTo(256);
            assertThat(ItemIconRenderer.sizeFor(100)).isEqualTo(100);
        }

        @Test
        @DisplayName("very large art is capped rather than shipped at full size")
        void oversizedArtIsCapped() {
            // A 512px icon is past what an inventory slot can show, so the cap
            // is a deliberate downscale — the only one left.
            assertThat(ItemIconRenderer.sizeFor(512)).isEqualTo(ItemIconRenderer.MAX_SIZE);
            assertThat(ItemIconRenderer.sizeFor(4096)).isEqualTo(ItemIconRenderer.MAX_SIZE);
        }

        @Test
        @DisplayName("small art is upscaled by a whole multiple so texels stay square")
        void smallArtUpscalesByAWholeMultiple() {
            // Whole multiples keep every source texel on output-pixel
            // boundaries. A non-integer ratio is what turns pixel art into
            // mush even when the output is larger than the input.
            assertThat(ItemIconRenderer.sizeFor(16)).isEqualTo(64);
            assertThat(ItemIconRenderer.sizeFor(32)).isEqualTo(64);
            assertThat(ItemIconRenderer.sizeFor(64)).isEqualTo(64);
            for (int source : new int[]{16, 32, 64}) {
                assertThat(ItemIconRenderer.sizeFor(source) % source)
                    .as("size for %dpx art divides evenly", source)
                    .isZero();
            }
        }

        @Test
        @DisplayName("art that does not divide 64 is rounded up, never below the old fixed 64")
        void nonDivisorArtRoundsUpRatherThanDown() {
            // The bug this pins: the multiple was computed with a floor
            // division, so anything that is not an exact divisor of 64 landed
            // BELOW 64 -- 48px art rendered at 48, 33px at 33, worse than the
            // fixed size this method replaced. Only the divisors {16, 32, 64}
            // reached 64, and those were the only values the sibling test
            // exercised, so the suite stayed green.
            for (int source = 1; source < ItemIconRenderer.DEFAULT_SIZE; source++) {
                int size = ItemIconRenderer.sizeFor(source);
                assertThat(size)
                    .as("size for %dpx art is at least the old fixed default", source)
                    .isGreaterThanOrEqualTo(ItemIconRenderer.DEFAULT_SIZE);
                assertThat(size % source)
                    .as("size for %dpx art (%d) is a whole multiple of it", source, size)
                    .isZero();
            }
            // Spot values, so a failure names the case rather than a loop index.
            assertThat(ItemIconRenderer.sizeFor(48)).isEqualTo(96);
            assertThat(ItemIconRenderer.sizeFor(24)).isEqualTo(72);
            assertThat(ItemIconRenderer.sizeFor(20)).isEqualTo(80);
        }

        @Test
        @DisplayName("a missing or degenerate source size falls back to the default")
        void degenerateSourceFallsBack() {
            assertThat(ItemIconRenderer.sizeFor(0)).isEqualTo(ItemIconRenderer.DEFAULT_SIZE);
            assertThat(ItemIconRenderer.sizeFor(-1)).isEqualTo(ItemIconRenderer.DEFAULT_SIZE);
        }

        @Test
        @DisplayName("a 128px sprite keeps its detail instead of being crushed to 64")
        void highResolutionRenderKeepsDetail() {
            // End-to-end rather than arithmetic: renders the same model from a
            // 128px texture and checks the icon comes out at 128, which is the
            // property the pixel comparison in the bug report turned on.
            BufferedImage texture = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 128; y++) {
                for (int x = 0; x < 128; x++) {
                    // Fine checkerboard: survives a 1:1 render, disappears
                    // under a 2:1 downscale.
                    texture.setRGB(x, y, ((x + y) % 2 == 0) ? 0xFFFF0000 : 0xFF0000FF);
                }
            }
            JavaModelGeometry geometry = new JavaModelGeometry(List.of(
                new JavaModelGeometry.Element(
                    new float[]{0f, 0f, 7.5f}, new float[]{16f, 16f, 8.5f}, null,
                    Map.of("south", new JavaModelGeometry.Face(
                        new float[]{0f, 0f, 16f, 16f}, "#layer0", 0)))));

            BufferedImage icon = ItemIconRenderer.render(
                geometry, null, null, texture,
                ItemIconRenderer.sizeFor(128), null);

            assertThat(icon).isNotNull();
            assertThat(icon.getWidth()).isEqualTo(128);
            assertThat(icon.getHeight()).isEqualTo(128);
        }
    }
}
