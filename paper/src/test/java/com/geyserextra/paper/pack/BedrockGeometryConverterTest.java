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
    @DisplayName("rotation 180 (point-symmetric via negative uv_size)")
    class RotationOneEighty {

        @Test
        @DisplayName("16x16 full-face UV with rotation=180 -> uv=[16,16], uv_size=[-16,-16]")
        void fullFace180() {
            JavaModelGeometry.Element element = singleFaceCube(
                "north", new float[]{0f, 0f, 16f, 16f}, 180);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("north");
            // 180 deg rotation maps face TL to texture BR (u2,v2) and the
            // mapping walks "backwards" with -(u2-u1), -(v2-v1).
            assertUv(faceUv, 16f, 16f, -16f, -16f);
        }

        @Test
        @DisplayName("partial UV [4,8,12,16] with rotation=180 -> uv=[12,16], uv_size=[-8,-8]")
        void partialRegion180() {
            JavaModelGeometry.Element element = singleFaceCube(
                "north", new float[]{4f, 8f, 12f, 16f}, 180);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("north");
            assertUv(faceUv, 12f, 16f, -8f, -8f);
        }

        @Test
        @DisplayName("pre-flipped UV [16,0,0,16] + rotation=180 composes correctly")
        void preFlippedPlus180() {
            JavaModelGeometry.Element element = singleFaceCube(
                "north", new float[]{16f, 0f, 0f, 16f}, 180);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 16, 16, null);

            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("north");
            // u1=16, v1=0, u2=0, v2=16 -> uv=(u2,v2)=(0,16),
            // uv_size = (-(u2-u1), -(v2-v1)) = (-(0-16), -(16-0)) = (16, -16)
            assertUv(faceUv, 0f, 16f, 16f, -16f);
        }

        @Test
        @DisplayName("32x32 high-res texture with rotation=180 scales correctly")
        void highRes180() {
            JavaModelGeometry.Element element = singleFaceCube(
                "north", new float[]{0f, 0f, 16f, 16f}, 180);
            List<Map<String, Object>> cubes =
                BedrockGeometryConverter.convertElementsToCubes(
                    new JavaModelGeometry(List.of(element)), 32, 32, null);

            Map<?, ?> faceUv = (Map<?, ?>) ((Map<?, ?>) cubes.get(0).get("uv")).get("north");
            assertUv(faceUv, 32f, 32f, -32f, -32f);
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
    @DisplayName("element rotation signs (Rainbow build-39+ convention: -x, +y, +z)")
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
        @DisplayName("X-axis rotation negates (Rainbow: X axis inverted on Bedrock)")
        void xAxisNegates() {
            float[] rot = rotationFor("x", 22.5f);
            assertThat(rot[0]).isCloseTo(-22.5f, EPS);
            assertThat(rot[1]).isCloseTo(0f, EPS);
            assertThat(rot[2]).isCloseTo(0f, EPS);
        }

        @Test
        @DisplayName("Y-axis rotation keeps its sign (Rainbow build 39+: Y NOT inverted)")
        void yAxisKeepsSign() {
            // Regression lock: the pre-review code negated Y (java2bedrock
            // convention), which mirrors 45-degree cross pieces / angled
            // blades relative to Rainbow's validated output.
            float[] rot = rotationFor("y", 45f);
            assertThat(rot[0]).isCloseTo(0f, EPS);
            assertThat(rot[1]).isCloseTo(45f, EPS);
            assertThat(rot[2]).isCloseTo(0f, EPS);
        }

        @Test
        @DisplayName("Z-axis rotation keeps its sign (Rainbow build 39+ fixed Z inversion)")
        void zAxisKeepsSign() {
            float[] rot = rotationFor("z", -22.5f);
            assertThat(rot[0]).isCloseTo(0f, EPS);
            assertThat(rot[1]).isCloseTo(0f, EPS);
            assertThat(rot[2]).isCloseTo(-22.5f, EPS);
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
}
