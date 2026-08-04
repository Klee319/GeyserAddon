package com.geyserextra.paper.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Locks the Phase 6 per-face UV math against silent regression.
 *
 * <p>The tests in this file are intentionally tight on numeric output: the
 * Bedrock geometry JSON we emit is hashed into the auto-pack patch version,
 * and any UV-formula drift would shift that hash for every operator on every
 * pack rebuild. Locking the values here ensures the formulas survive future
 * refactors (and Codex round 2/3 hardening — empty faces, all-rotated faces,
 * zero-area guards) without re-deriving the math.</p>
 */
class BedrockGeometryConverterTest {

    // Tolerance for floating-point comparisons of UV math. The Java -> Bedrock
    // path multiplies by texture_width/16, which can introduce trivial
    // floating-point error for non-power-of-2 texture sizes.
    private static final org.assertj.core.data.Offset<Float> EPS = offset(1e-6f);

    @Nested
    @DisplayName("rotation 0 (identity)")
    class RotationZero {

        @Test
        @DisplayName("16x16 texture, full-face UV [0,0,16,16] -> uv=[0,0], uv_size=[16,16]")
        void identityFullFace() {
            JavaModelGeometry.Element element = singleFaceCube(
                "north", new float[]{0f, 0f, 16f, 16f}, 0);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            assertThat(cubes).hasSize(1);
            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("north");
            assertUv(faceUv, 0f, 0f, 16f, 16f);
        }

        @Test
        @DisplayName("32x32 texture, full-face UV [0,0,16,16] -> uv_size scaled to [32,32]")
        void identityHighRes() {
            JavaModelGeometry.Element element = singleFaceCube(
                "north", new float[]{0f, 0f, 16f, 16f}, 0);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 32, 32, null);

            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("north");
            // scale = 32/16 = 2, so each Java unit becomes 2 Bedrock pixels
            assertUv(faceUv, 0f, 0f, 32f, 32f);
        }

        @Test
        @DisplayName("Java pre-flipped UV [16,0,0,16] (horizontal mirror) -> uv=[16,0], uv_size=[-16,16]")
        void preFlippedHorizontal() {
            JavaModelGeometry.Element element = singleFaceCube(
                "north", new float[]{16f, 0f, 0f, 16f}, 0);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("north");
            assertUv(faceUv, 16f, 0f, -16f, 16f);
        }

        @Test
        @DisplayName("partial UV [4,8,12,16] (top-right quadrant) -> uv=[4,8], uv_size=[8,8]")
        void partialQuadrant() {
            JavaModelGeometry.Element element = singleFaceCube(
                "north", new float[]{4f, 8f, 12f, 16f}, 0);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("north");
            assertUv(faceUv, 4f, 8f, 8f, 8f);
        }

        @Test
        @DisplayName("up/down faces are point-mirrored (java2bedrock X-mirror convention)")
        void verticalFacesPointMirrored() {
            JavaModelGeometry.Element element = singleFaceCube(
                "up", new float[]{0f, 0f, 16f, 16f}, 0);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("up");
            // X-mirrored geometry flips top/bottom UV orientation: anchor at
            // (u2,v2) and walk backwards with negative sizes.
            assertUv(faceUv, 16f, 16f, -16f, -16f);
        }

        @Test
        @DisplayName("missing UV defaults to whole texture [0,0,16,16]")
        void missingUvDefault() {
            JavaModelGeometry.Element element = singleFaceCube("north", null, 0);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("north");
            assertUv(faceUv, 0f, 0f, 16f, 16f);
        }
    }

    @Nested
    @DisplayName("face rotation forwarded as Bedrock uv_rotation (geometry 1.21.0)")
    class FaceRotation {

        @Test
        @DisplayName("rotation=180 keeps the plain UV rect and sets uv_rotation")
        void fullFace180() {
            JavaModelGeometry.Element element = singleFaceCube(
                "north", new float[]{0f, 0f, 16f, 16f}, 180);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("north");
            assertUv(faceUv, 0f, 0f, 16f, 16f);
            assertThat(faceUv.get("uv_rotation")).isEqualTo(180);
        }

        @Test
        @DisplayName("rotation=90 is no longer dropped")
        void ninetyIsPreserved() {
            JavaModelGeometry.Element element = singleFaceCube(
                "east", new float[]{4f, 8f, 12f, 16f}, 90);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("east");
            assertUv(faceUv, 4f, 8f, 8f, 8f);
            assertThat(faceUv.get("uv_rotation")).isEqualTo(90);
        }

        @Test
        @DisplayName("rotation=270 on a vertical face keeps the up/down point mirror")
        void twoSeventyOnVerticalFace() {
            JavaModelGeometry.Element element = singleFaceCube(
                "up", new float[]{4f, 8f, 12f, 16f}, 270);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("up");
            assertUv(faceUv, 12f, 16f, -8f, -8f);
            assertThat(faceUv.get("uv_rotation")).isEqualTo(270);
        }

        @Test
        @DisplayName("rotation=0 omits uv_rotation entirely")
        void zeroOmitsField() {
            JavaModelGeometry.Element element = singleFaceCube(
                "north", new float[]{0f, 0f, 16f, 16f}, 0);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 32, 32, null);

            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("north");
            assertUv(faceUv, 0f, 0f, 32f, 32f);
            assertThat(faceUv.containsKey("uv_rotation")).isFalse();
        }
    }

    @Nested
    @DisplayName("rotation 90 / 270 (Bedrock 1.16.0 cannot express -> rendered unrotated)")
    class RotationNinetyAndTwoSeventy {

        @Test
        @DisplayName("rotation=90: face renders with unrotated UV (approximation, no hole)")
        void rotation90RenderedUnrotated() {
            JavaModelGeometry.Element element = singleFaceCube(
                "north", new float[]{0f, 0f, 16f, 16f}, 90);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            // 90° cannot be expressed in per-face UV, but a hole in the model
            // is far more visible than a mis-rotated texture — the face is
            // kept and sampled as if rotation were 0.
            assertThat(cubes).hasSize(1);
            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("north");
            assertUv(faceUv, 0f, 0f, 16f, 16f);
        }

        @Test
        @DisplayName("rotation=270: same unrotated-approximation behavior as 90")
        void rotation270RenderedUnrotated() {
            JavaModelGeometry.Element element = singleFaceCube(
                "north", new float[]{0f, 0f, 16f, 16f}, 270);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            assertThat(cubes).hasSize(1);
            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("north");
            assertUv(faceUv, 0f, 0f, 16f, 16f);
        }

        @Test
        @DisplayName("mixed rotation: both faces render, rotated one falls back to 0")
        void mixedRotationPartial() {
            // Two faces on the same cube: north rotation=0, south rotation=90.
            // Expected: both faces survive; south uses the unrotated UV.
            JavaModelGeometry.Element element = new JavaModelGeometry.Element(
                new float[]{0f, 0f, 0f}, new float[]{16f, 16f, 16f}, null,
                Map.of(
                    "north", new JavaModelGeometry.Face(new float[]{0f, 0f, 16f, 16f}, "#layer0", 0),
                    "south", new JavaModelGeometry.Face(new float[]{0f, 0f, 16f, 16f}, "#layer0", 90)
                ));
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            assertThat(cubes).hasSize(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> uvMap = (Map<String, Object>) cubes.get(0).get("uv");
            assertThat(uvMap).containsKey("north");
            assertThat(uvMap).containsKey("south");
            assertUv((Map<?, ?>) uvMap.get("south"), 0f, 0f, 16f, 16f);
        }
    }

    @Nested
    @DisplayName("degenerate / defensive cases")
    class Defensive {

        @Test
        @DisplayName("zero-area face (uv_size 0 on either axis) -> face omitted")
        void zeroAreaOmitted() {
            // u1 == u2 makes the UV strip 0-wide; per Codex round 2 fix this
            // must be skipped on EITHER axis being zero, not only when both are.
            JavaModelGeometry.Element element = singleFaceCube(
                "north", new float[]{5f, 0f, 5f, 16f}, 0);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);
            assertThat(cubes).isEmpty();
        }

        @Test
        @DisplayName("empty faces map -> cube omitted (matches Java 'missing face = invisible')")
        void emptyFacesOmitted() {
            JavaModelGeometry.Element element = new JavaModelGeometry.Element(
                new float[]{0f, 0f, 0f}, new float[]{16f, 16f, 16f}, null, Map.of());
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);
            assertThat(cubes).isEmpty();
        }

        @Test
        @DisplayName("from > to on any axis normalizes to (min, max) and renders correctly")
        void fromGreaterThanTo() {
            // Artist swapped from/to on X axis. Without normalization, the cube
            // would clamp to zero size on X. With normalization, the cube has
            // the same physical extent as the operator intended.
            JavaModelGeometry.Element element = new JavaModelGeometry.Element(
                new float[]{12f, 0f, 0f}, new float[]{4f, 16f, 16f}, null,
                Map.of(
                    "north", new JavaModelGeometry.Face(new float[]{0f, 0f, 16f, 16f}, "#layer0", 0)
                ));
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            assertThat(cubes).hasSize(1);
            // origin.x = min(12, 4) - 8 = -4; size.x = max - min = 8
            List<?> origin = (List<?>) cubes.get(0).get("origin");
            List<?> size = (List<?>) cubes.get(0).get("size");
            assertThat(((Number) origin.get(0)).floatValue()).isCloseTo(-4f, EPS);
            assertThat(((Number) size.get(0)).floatValue()).isCloseTo(8f, EPS);
        }

        @Test
        @DisplayName("element rotation composes with per-face UV (rotation/pivot set, UV unaffected)")
        void elementRotationCompositions() {
            JavaModelGeometry.Element element = new JavaModelGeometry.Element(
                new float[]{0f, 0f, 0f}, new float[]{16f, 16f, 16f},
                new JavaModelGeometry.ElementRotation(new float[]{8f, 8f, 8f}, "y", 22.5f),
                Map.of(
                    "north", new JavaModelGeometry.Face(new float[]{0f, 0f, 16f, 16f}, "#layer0", 0)
                ));
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            assertThat(cubes).hasSize(1);
            Map<String, Object> cube = cubes.get(0);
            // rotation/pivot present
            assertThat(cube).containsKey("rotation").containsKey("pivot");
            // Per-face UV unchanged by element rotation
            Map<?, ?> uvMap = (Map<?, ?>) cube.get("uv");
            Map<?, ?> northUv = (Map<?, ?>) uvMap.get("north");
            assertUv(northUv, 0f, 0f, 16f, 16f);
        }

        @Test
        @DisplayName("Phase-4 backward-compat overload (no texture dims) still works")
        void phase4BackwardCompatOverload() {
            JavaModelGeometry.Element element = singleFaceCube(
                "north", new float[]{0f, 0f, 16f, 16f}, 0);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)));
            assertThat(cubes).hasSize(1);
            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("north");
            // Default 16x16 dims used -> scale = 1
            assertUv(faceUv, 0f, 0f, 16f, 16f);
        }
    }

    @Nested
    @DisplayName("element rotation signs (Blockbench Bedrock codec: -x, -y, +z)")
    class ElementRotationSigns {

        private float[] rotationFor(String axis, float angle) {
            JavaModelGeometry.Element element = new JavaModelGeometry.Element(
                new float[]{0f, 0f, 0f}, new float[]{16f, 16f, 16f},
                new JavaModelGeometry.ElementRotation(new float[]{8f, 8f, 8f}, axis, angle),
                Map.of("north", new JavaModelGeometry.Face(
                    new float[]{0f, 0f, 16f, 16f}, "#layer0", 0)));
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);
            @SuppressWarnings("unchecked")
            List<Number> rotation = (List<Number>) cubes.get(0).get("rotation");
            return new float[]{
                rotation.get(0).floatValue(),
                rotation.get(1).floatValue(),
                rotation.get(2).floatValue()};
        }

        @Test
        @DisplayName("X-axis rotation negates")
        void xAxisNegates() {
            float[] rot = rotationFor("x", 22.5f);
            assertThat(rot[0]).isCloseTo(-22.5f, EPS);
            assertThat(rot[1]).isCloseTo(0f, EPS);
            assertThat(rot[2]).isCloseTo(0f, EPS);
        }

        @Test
        @DisplayName("Y-axis rotation negates")
        void yAxisNegates() {
            // Blockbench's Bedrock codec writes [-x, -y, +z] for the model it
            // is displaying, and its Java codec writes the same internal
            // rotation out verbatim — so Y negates between the two formats.
            // Rainbow's GeometryMapper keeps +y (under a "TODO check if these
            // angle transformations are right"); following it here rotated
            // every Y-axis element by 2*angle away from Java, which for the
            // 広辞苑 book covers (Y, pivot on the spine) read as the covers
            // sitting off the pages.
            float[] rot = rotationFor("y", 45f);
            assertThat(rot[0]).isCloseTo(0f, EPS);
            assertThat(rot[1]).isCloseTo(-45f, EPS);
            assertThat(rot[2]).isCloseTo(0f, EPS);
        }

        @Test
        @DisplayName("Z-axis rotation keeps its sign")
        void zAxisKeepsSign() {
            float[] rot = rotationFor("z", -22.5f);
            assertThat(rot[0]).isCloseTo(0f, EPS);
            assertThat(rot[1]).isCloseTo(0f, EPS);
            assertThat(rot[2]).isCloseTo(-22.5f, EPS);
        }

        @Test
        @DisplayName("cube rotations use the same sign convention as display rotations")
        void matchesDisplayRotationConvention() {
            // One convention for the whole pipeline. Two contradictory tables
            // is what let the Y sign be wrong on one side and right on the
            // other for as long as it was.
            float[] display = BedrockGeometryConverter.convertRotation(
                new float[]{22.5f, 45f, -22.5f});
            assertThat(rotationFor("x", 22.5f)[0]).isCloseTo(display[0], EPS);
            assertThat(rotationFor("y", 45f)[1]).isCloseTo(display[1], EPS);
            assertThat(rotationFor("z", -22.5f)[2]).isCloseTo(display[2], EPS);
        }

        @Test
        @DisplayName("a free-rotation triple that folds to one axis converts as that axis")
        void eulerFoldedToSingleAxisUsesTheAxisRule() {
            // 広辞苑's cover: {-180, -89, 180} composes (Rx·Ry·Rz, the order
            // vanilla's CuboidRotation.EulerXYZRotation names) to a plain
            // Ry(-91), so it must come out as the Y rule applied to -91 —
            // independent of whatever order Bedrock composes a triple in.
            JavaModelGeometry.Element element = new JavaModelGeometry.Element(
                new float[]{5.05f, 10f, 6.7f}, new float[]{11.075f, 18f, 6.75f},
                JavaModelGeometry.ElementRotation.ofEuler(
                    new float[]{6.5875f, 14f, 7.875f},
                    new float[]{-180f, -89f, 180f}, false),
                Map.of("north", new JavaModelGeometry.Face(
                    new float[]{0.5f, 0.5f, 6f, 7.5f}, "#0", 0)));
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 64, 64, null);
            @SuppressWarnings("unchecked")
            List<Number> rotation = (List<Number>) cubes.get(0).get("rotation");
            assertThat(rotation.get(0).floatValue()).isCloseTo(0f, EPS);
            assertThat(rotation.get(1).floatValue()).isCloseTo(91f, EPS);
            assertThat(rotation.get(2).floatValue()).isCloseTo(0f, EPS);
        }
    }

    @Nested
    @DisplayName("Mojang position-derived default UV (FaceBakery.defaultFaceUV)")
    class DefaultUvDerivation {

        // Partial cube from (2,3,4) to (10,8,12): default UVs are derived
        // from the element's from/to coordinates per face, NOT the whole
        // texture. Table (Mojang FaceBakery.defaultFaceUV):
        //   north: [16-to.x, 16-to.y, 16-from.x, 16-from.y] = [6, 8, 14, 13]
        //   east:  [16-to.z, 16-to.y, 16-from.z, 16-from.y] = [4, 8, 12, 13]
        //   up:    [from.x, from.z, to.x, to.z]             = [2, 4, 10, 12]
        private JavaModelGeometry.Element partialCube(String faceName) {
            return new JavaModelGeometry.Element(
                new float[]{2f, 3f, 4f}, new float[]{10f, 8f, 12f},
                null,
                Map.of(faceName, new JavaModelGeometry.Face(null, "#layer0", 0)));
        }

        private Map<?, ?> faceUvOf(String faceName) {
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(partialCube(faceName))), 16, 16, null);
            return (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get(faceName);
        }

        @Test
        @DisplayName("north face of a partial cube derives UV from element bounds")
        void northPartialCube() {
            assertUv(faceUvOf("north"), 6f, 8f, 8f, 5f);
        }

        @Test
        @DisplayName("east face of a partial cube derives UV from element bounds")
        void eastPartialCube() {
            assertUv(faceUvOf("east"), 4f, 8f, 8f, 5f);
        }

        @Test
        @DisplayName("up face derives UV from bounds and stays point-mirrored")
        void upPartialCube() {
            // Derived UV [2,4,10,12], then the vertical-face point mirror
            // anchors at (u2,v2) with negative sizes.
            assertUv(faceUvOf("up"), 10f, 12f, -8f, -8f);
        }
    }

    @Nested
    @DisplayName("convertTranslation (the single Java->Bedrock X mirror)")
    class TranslationSigns {

        @Test
        @DisplayName("both hands negate X once, on the transform Java renders")
        void bothHandsNegateRenderedX() {
            // Greataxe third person. The author writes the lefthand slot as the
            // mirror of the righthand one, so vanilla ItemTransform#apply's
            // negation lands both hands on the same rendered translation — and
            // the emitted Bedrock offset must therefore be the same too.
            float[] righthand = new float[]{-15.5f, 13f, 1.5f};
            float[] lefthand = new float[]{15.5f, 13f, 1.5f};
            float[] main = BedrockGeometryConverter.convertTranslation(righthand, false, true);
            float[] off = BedrockGeometryConverter.convertTranslation(
                BedrockGeometryConverter.applyJavaLeftHandTranslation(lefthand), false, true);
            assertThat(main[0]).isCloseTo(15.5f, EPS);
            assertThat(off[0]).isCloseTo(15.5f, EPS);
            assertThat(main[1]).isCloseTo(13f, EPS);
            assertThat(main[2]).isCloseTo(1.5f, EPS);
        }

        @Test
        @DisplayName("skipping the left-hand negation puts the off hand 2*x out")
        void skippingLeftHandNegationDoublesTheError() {
            // Regression lock for the fault this replaced: mirroring the
            // DECLARED lefthand X instead of the rendered one. The error is
            // 2*declared.x — 31 units for the greataxe, and exactly zero for an
            // item that declares no X offset, which is why a zero-offset mace
            // looked fine and was misread as clearing the sign of suspicion.
            float[] lefthand = new float[]{15.5f, 13f, 1.5f};
            float[] correct = BedrockGeometryConverter.convertTranslation(
                BedrockGeometryConverter.applyJavaLeftHandTranslation(lefthand), false, true);
            float[] wrong = BedrockGeometryConverter.convertTranslation(lefthand, false, true);
            assertThat(correct[0] - wrong[0]).isCloseTo(2f * 15.5f, EPS);

            float[] noOffset = new float[]{0f, 13f, 1.5f};
            assertThat(BedrockGeometryConverter.convertTranslation(
                    BedrockGeometryConverter.applyJavaLeftHandTranslation(noOffset), false, true)[0])
                .isCloseTo(BedrockGeometryConverter.convertTranslation(noOffset, false, true)[0], EPS);
        }

        @Test
        @DisplayName("first person negates Z on top of the X rule")
        void firstPersonAlsoNegatesZ() {
            float[] java = new float[]{1.13f, 3.2f, 1.13f};
            float[] mirrored = BedrockGeometryConverter.convertTranslation(java, true, true);
            float[] asIs = BedrockGeometryConverter.convertTranslation(java, true, false);
            assertThat(mirrored[0]).isCloseTo(-1.13f, EPS);
            assertThat(asIs[0]).isCloseTo(1.13f, EPS);
            assertThat(mirrored[2]).isCloseTo(-1.13f, EPS);
            assertThat(asIs[2]).isCloseTo(-1.13f, EPS);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static JavaModelGeometry.Element singleFaceCube(
        String faceName, float[] uv, int rotation
    ) {
        JavaModelGeometry.Face face = new JavaModelGeometry.Face(uv, "#layer0", rotation);
        return new JavaModelGeometry.Element(
            new float[]{0f, 0f, 0f}, new float[]{16f, 16f, 16f},
            null, Map.of(faceName, face));
    }

    @SuppressWarnings("unchecked")
    private static void assertUv(Map<?, ?> faceEntry, float u, float v, float uSize, float vSize) {
        List<Number> uvList = (List<Number>) faceEntry.get("uv");
        List<Number> uvSizeList = (List<Number>) faceEntry.get("uv_size");
        assertThat(uvList.get(0).floatValue()).isCloseTo(u, EPS);
        assertThat(uvList.get(1).floatValue()).isCloseTo(v, EPS);
        assertThat(uvSizeList.get(0).floatValue()).isCloseTo(uSize, EPS);
        assertThat(uvSizeList.get(1).floatValue()).isCloseTo(vSize, EPS);
    }

    @Nested
    @DisplayName("root-frame translation")
    class RootFrameTranslation {

        private static final float[] THIRD_PERSON_ROOT = {90f, 0f, 0f};
        private static final float[] FIRST_PERSON_ROOT = {90f, 60f, -40f};

        /**
         * The guarantee that makes the frame switch safe to ship: the
         * third-person root has only one non-zero angle, so every Euler order
         * collapses to the same matrix and the change of basis must reproduce
         * java2bedrock's sign flips exactly. If this fails, the switch has
         * started moving items that are already correct in game.
         */
        @Test
        @DisplayName("every frame reproduces j2b exactly in third person")
        void thirdPersonIsFrameIndependent() {
            float[][] samples = {
                {-15.5f, 13f, 1.5f},    // greataxe / warhammer
                {1.13f, 3.2f, -2.12f},  // dagger
                {-5.25f, -7.25f, -1f},  // long spear
                {0f, 0f, 0f}
            };
            for (float[] t : samples) {
                for (boolean mirrorX : new boolean[]{true, false}) {
                    float[] j2b = BedrockGeometryConverter
                        .convertTranslation(t, false, mirrorX);
                    for (BedrockGeometryConverter.TranslationFrame frame
                        : BedrockGeometryConverter.TranslationFrame.values()) {
                        if (frame == BedrockGeometryConverter.TranslationFrame.J2B) {
                            continue;
                        }
                        float[] actual = BedrockGeometryConverter
                            .convertTranslationInRootFrame(
                                t, THIRD_PERSON_ROOT, mirrorX, frame);
                        assertThat(actual)
                            .as("frame %s, translation %s, mirrorX %s",
                                frame, java.util.Arrays.toString(t), mirrorX)
                            .usingComparatorWithPrecision(1e-4f)
                            .containsExactly(j2b);
                    }
                }
            }
        }

        /**
         * The axis map is not a free parameter: it is pinned by confirmed-good
         * third-person output. Java {@code [-15.5, 13, 1.5]} is emitted as
         * {@code [15.5, 13, 1.5]} under a {@code [90, 0, 0]} root.
         */
        @Test
        @DisplayName("axis map matches the observed third-person output")
        void axisMapMatchesObservedOutput() {
            float[] actual = BedrockGeometryConverter.convertTranslationInRootFrame(
                new float[]{-15.5f, 13f, 1.5f}, THIRD_PERSON_ROOT, true,
                BedrockGeometryConverter.TranslationFrame.ZYX);
            assertThat(actual).usingComparatorWithPrecision(1e-4f)
                .containsExactly(new float[]{15.5f, 13f, 1.5f});
        }

        /** A rotation is length-preserving, so no frame may resize the offset. */
        @Test
        @DisplayName("first-person frames preserve offset magnitude")
        void firstPersonPreservesMagnitude() {
            float[] t = {0f, 4f, -9f};
            double expected = Math.sqrt(4 * 4 + 9 * 9);
            for (BedrockGeometryConverter.TranslationFrame frame
                : BedrockGeometryConverter.TranslationFrame.values()) {
                if (frame == BedrockGeometryConverter.TranslationFrame.J2B) {
                    continue;
                }
                float[] p = BedrockGeometryConverter.convertTranslationInRootFrame(
                    t, FIRST_PERSON_ROOT, true, frame);
                double len = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
                assertThat(len).as("frame %s", frame).isCloseTo(expected, offset(1e-3));
            }
        }

        /**
         * The greataxe is the item the switch exists for: under j2b its 9-unit
         * push away from the camera is misdirected, and only a frame that
         * actually rotates the vector redistributes it.
         */
        @Test
        @DisplayName("first-person frames differ from j2b for a large offset")
        void firstPersonDiffersForLargeOffset() {
            float[] t = {0f, 4f, -9f};
            float[] j2b = BedrockGeometryConverter.convertTranslation(t, true, true);
            for (BedrockGeometryConverter.TranslationFrame frame
                : BedrockGeometryConverter.TranslationFrame.values()) {
                if (frame == BedrockGeometryConverter.TranslationFrame.J2B) {
                    continue;
                }
                float[] p = BedrockGeometryConverter.convertTranslationInRootFrame(
                    t, FIRST_PERSON_ROOT, true, frame);
                assertThat(p).as("frame %s", frame).isNotEqualTo(j2b);
            }
        }

        @Test
        @DisplayName("unknown and blank frame names fall back to j2b")
        void parseFallsBack() {
            assertThat(BedrockGeometryConverter.TranslationFrame.parse(null))
                .isEqualTo(BedrockGeometryConverter.TranslationFrame.J2B);
            assertThat(BedrockGeometryConverter.TranslationFrame.parse("  "))
                .isEqualTo(BedrockGeometryConverter.TranslationFrame.J2B);
            assertThat(BedrockGeometryConverter.TranslationFrame.parse("nonsense"))
                .isEqualTo(BedrockGeometryConverter.TranslationFrame.J2B);
            assertThat(BedrockGeometryConverter.TranslationFrame.parse(" zXy "))
                .isEqualTo(BedrockGeometryConverter.TranslationFrame.ZXY);
        }
    }
}
