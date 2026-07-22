package com.geyserextra.paper.pack;

import com.geyserextra.core.config.GeyserExtraConfig.AttachableGenerationConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

@DisplayName("BedrockAttachableWriter geometry mode")
class BedrockAttachableWriterTest {

    @Test
    @DisplayName("offsets_only + elements uses full 3D cubes (not flat 16x16 quad)")
    void offsetsOnlyWithElementsUsesFullGeometry() {
        JavaModelDisplay display = new JavaModelDisplay(
            new JavaModelDisplay.Transform(
                new float[]{55f, 0f, 90f},
                new float[]{0f, 4f, -9f},
                new float[]{1.7f, 1.7f, 1.7f}),
            new JavaModelDisplay.Transform(
                new float[]{45f, 0f, 90f},
                new float[]{-15.5f, 13f, 1.5f},
                new float[]{2f, 2f, 2f}),
            null, null, null);

        JavaModelGeometry.Face face = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#0", 0);
        JavaModelGeometry.Element a = new JavaModelGeometry.Element(
            new float[]{0f, 0f, 0f}, new float[]{4f, 4f, 4f}, null,
            Map.of("north", face, "south", face));
        JavaModelGeometry.Element b = new JavaModelGeometry.Element(
            new float[]{4f, 0f, 0f}, new float[]{8f, 4f, 4f}, null,
            Map.of("north", face, "south", face));
        JavaModelGeometry geometry = new JavaModelGeometry(List.of(a, b));

        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false, false);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "test_hammer", display, geometry, "textures/items/test_hammer",
            32, 32, config, null);

        assertThat(artifacts).containsKey(
            BedrockAttachableWriter.geometryEntryPath("test_hammer"));
        String geo = artifacts.get(BedrockAttachableWriter.geometryEntryPath("test_hammer"));
        // Two sourced elements with faces → two cubes. Empty faces would
        // yield cubes:[]. Cube origins are the only "origin" keys emitted.
        assertThat(geo).contains("\"cubes\"");
        assertThat(countOccurrences(geo, "\"origin\"")).isEqualTo(2);
        assertThat(geo).doesNotContain("\"cubes\": []");
        // Rainbow single-bone geometry: binding on the cube bone, no java2bedrock chain.
        assertThat(geo).contains("\"binding\"");
        assertThat(geo).contains("\"geyserextra\"");
        assertThat(geo).doesNotContain("\"geyserextra_x\"");
        assertThat(geo).doesNotContain("\"geyserextra_y\"");
        assertThat(geo).doesNotContain("\"geyserextra_z\"");
        assertThat(geo).doesNotContain("\"geyserextra_geo\"");

        String anim = artifacts.get(BedrockAttachableWriter.animationEntryPath("test_hammer"));
        String thirdMain = extractAnimation(anim, "thirdperson_main_hand");
        // Third-person Rainbow (AnimationMapper): rot (45,0,90) → (90, -rz, -ry)
        // = (90, -90, -0); trans (-15.5, 13, 1.5) → (15.5, 14.0, -13.0).
        assertThat(anim).contains("thirdperson_main_hand");
        assertThat(anim).contains("firstperson_main_hand");
        assertThat(thirdMain).contains("90.0");
        assertThat(thirdMain).contains("-90.0");
        assertThat(thirdMain).contains("15.5");
        assertThat(thirdMain).contains("14.0");
        assertThat(thirdMain).contains("-13.0");
        assertThat(thirdMain).contains("2.0");
        assertThat(thirdMain).doesNotContain("geyserextra_x");
        // Legacy java2bedrock third-person base pose must not appear on Rainbow path.
        assertThat(thirdMain).doesNotContain("-3.0");
    }

    @Test
    @DisplayName("first person + elements uses the Rainbow axis-permutation mapping on the root bone")
    void firstPersonWithElementsUsesRainbowMapping() {
        // ValhallaMMO golden warhammer display values (real-world case).
        JavaModelDisplay display = new JavaModelDisplay(
            new JavaModelDisplay.Transform(
                new float[]{55f, 0f, 90f},
                new float[]{0f, 4f, -9f},
                new float[]{1.7f, 1.7f, 1.7f}),
            new JavaModelDisplay.Transform(
                new float[]{45f, 0f, 90f},
                new float[]{-15.5f, 13f, 1.5f},
                new float[]{2f, 2f, 2f}),
            null, null, null);

        JavaModelGeometry.Face face = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#0", 0);
        JavaModelGeometry geometry = new JavaModelGeometry(List.of(
            new JavaModelGeometry.Element(
                new float[]{0f, 0f, 0f}, new float[]{4f, 4f, 4f}, null,
                Map.of("north", face))));

        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false, false);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "test_hammer", display, geometry, "textures/items/test_hammer",
            32, 32, config, null);

        String anim = artifacts.get(BedrockAttachableWriter.animationEntryPath("test_hammer"));
        String firstMain = extractAnimation(anim, "firstperson_main_hand");
        String firstOff = extractAnimation(anim, "firstperson_off_hand");

        assertThat(firstPersonAnimatedBoneName(anim)).isEqualTo("geyserextra");

        // Rainbow mapping: rotation = (-90 + ry, -rz, rx) = (-90, -90, 55);
        // position = (-ty, 12.5 + tz, tx) = (-4, 3.5, 0); scale unchanged.
        assertThat(firstMain).contains("-90.0");
        assertThat(firstMain).contains("55.0");
        assertThat(firstMain).contains("-4.0");
        assertThat(firstMain).contains("3.5");
        assertThat(firstMain).contains("1.7");
        // Whole pose on BONE_ROOT — no per-axis decomposition.
        assertThat(firstMain).doesNotContain("geyserextra_x");
        assertThat(firstMain).doesNotContain("geyserextra_y");
        assertThat(firstMain).doesNotContain("geyserextra_z");
        assertThat(firstMain).doesNotContain("0.1");
        // Bedrock cannot address hands separately in first person: off hand
        // reuses the right-hand values verbatim (Rainbow behaviour).
        assertThat(firstOff).isEqualTo(firstMain);
        // The legacy java2bedrock first-person base pose must be gone from
        // the full-geometry first-person path.
        assertThat(firstMain).doesNotContain("60.0");
        assertThat(firstMain).doesNotContain("-40.0");
    }

    @Test
    @DisplayName("full 3D geometry pivots the cube bone at the model-bounds centre (Rainbow), not fixed [0,8,0]")
    void fullGeometryUsesModelCentrePivot() {
        // ValhallaMMO golden great-axe (real-world case): after Java->Bedrock
        // conversion the cubes span bounds min[-4.5,0,-4.5] max[8,0.5,8].
        // Java rotates the display transform around the model's geometric
        // centre, so — matching GeyserMC/Rainbow's GeometryMapper — the
        // cube bone's pivot must be that centre [1.75,0.25,1.75], NOT the
        // legacy fixed java2bedrock pivot [0,8,0].
        JavaModelDisplay display = greatAxeDisplay();
        JavaModelGeometry geometry = greatAxeGeometry();

        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false, false);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "golden_great_axe", display, geometry, "textures/items/golden_great_axe",
            32, 32, config, null);

        String geo = artifacts.get(BedrockAttachableWriter.geometryEntryPath("golden_great_axe"));
        float[] pivot = cubeBonePivot(geo);
        assertThat(pivot[0]).isCloseTo(1.75f, offset(1e-4f));
        assertThat(pivot[1]).isCloseTo(0.25f, offset(1e-4f));
        assertThat(pivot[2]).isCloseTo(1.75f, offset(1e-4f));
    }

    @Test
    @DisplayName("first person rotates the model around its geometric centre (Rainbow), not [0,8,0]")
    void firstPersonRotatesAroundModelCentre() {
        // The first-person Rainbow pose ([-90,-90,55] / [-4,3.5,0] / 1.7) is
        // already correct, but it only renders correctly if applied to a bone
        // pivoted at the model centre. Assert the bone the first-person
        // animation targets is pivoted at [1.75,0.25,1.75] in the geometry.
        JavaModelDisplay display = greatAxeDisplay();
        JavaModelGeometry geometry = greatAxeGeometry();

        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false, false);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "golden_great_axe", display, geometry, "textures/items/golden_great_axe",
            32, 32, config, null);

        String geo = artifacts.get(BedrockAttachableWriter.geometryEntryPath("golden_great_axe"));
        String anim = artifacts.get(BedrockAttachableWriter.animationEntryPath("golden_great_axe"));

        // The bone the first-person animation drives must be the same bone
        // that carries the cubes, and that bone must be pivoted at the model
        // centre — otherwise the (correct) Rainbow rotation swings the model
        // around the wrong point in first person.
        String animatedBone = firstPersonAnimatedBoneName(anim);
        String cubeBone = cubeBoneName(geo);
        assertThat(animatedBone).isEqualTo("geyserextra");
        assertThat(cubeBone).isEqualTo("geyserextra");

        float[] pivot = cubeBonePivot(geo);
        assertThat(pivot[0]).isCloseTo(1.75f, offset(1e-4f));
        assertThat(pivot[1]).isCloseTo(0.25f, offset(1e-4f));
        assertThat(pivot[2]).isCloseTo(1.75f, offset(1e-4f));
    }

    @Test
    @DisplayName("explicit firstPersonBasePose forces the legacy path even with elements")
    void explicitBasePoseKeepsLegacyFirstPerson() {
        JavaModelDisplay display = new JavaModelDisplay(
            new JavaModelDisplay.Transform(
                new float[]{55f, 0f, 90f},
                new float[]{0f, 4f, -9f},
                new float[]{1.7f, 1.7f, 1.7f}),
            null, null, null, null);

        JavaModelGeometry.Face face = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#0", 0);
        JavaModelGeometry geometry = new JavaModelGeometry(List.of(
            new JavaModelGeometry.Element(
                new float[]{0f, 0f, 0f}, new float[]{4f, 4f, 4f}, null,
                Map.of("north", face))));

        AttachableGenerationConfig config = new AttachableGenerationConfig(
            AttachableGenerationConfig.MODE_OFFSETS_ONLY, false, false,
            new AttachableGenerationConfig.BasePose(
                new float[]{90f, 60f, -40f}, new float[]{4f, 10f, 4f}, 1.5f));

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "test_hammer", display, geometry, "textures/items/test_hammer",
            32, 32, config, null);

        String geo = artifacts.get(BedrockAttachableWriter.geometryEntryPath("test_hammer"));
        assertThat(geo).contains("\"geyserextra_x\"");
        assertThat(geo).contains("\"geyserextra_geo\"");

        String firstMain = extractAnimation(
            artifacts.get(BedrockAttachableWriter.animationEntryPath("test_hammer")),
            "firstperson_main_hand");
        // Legacy base pose values present; the per-axis decomposition bones
        // are used again on this path.
        assertThat(firstMain).contains("60.0");
        assertThat(firstMain).contains("-40.0");
        assertThat(firstMain).contains("geyserextra_x");
    }

    @Test
    @DisplayName("flat items (no elements) keep the java2bedrock first-person base pose")
    void flatItemsKeepLegacyFirstPerson() {
        JavaModelDisplay display = new JavaModelDisplay(
            new JavaModelDisplay.Transform(
                new float[]{55f, 0f, 90f},
                new float[]{0f, 4f, -9f},
                new float[]{1.7f, 1.7f, 1.7f}),
            null, null, null, null);

        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false, false);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "flat_item", display, null, "textures/items/flat_item",
            16, 16, config, null);

        String firstMain = extractAnimation(
            artifacts.get(BedrockAttachableWriter.animationEntryPath("flat_item")),
            "firstperson_main_hand");
        // Default java2bedrock base pose (90, 60, -40) / (4, 10, 4) / 1.5.
        assertThat(firstMain).contains("60.0");
        assertThat(firstMain).contains("-40.0");
        assertThat(firstMain).contains("10.0");
        assertThat(firstMain).contains("geyserextra_x");
    }

    /**
     * Slices one named animation object out of the rendered animation JSON
     * so assertions don't accidentally match values from a sibling
     * animation (e.g. third person). Relies on the writer's stable
     * "animation.geyserextra.&lt;key&gt;.&lt;slot&gt;" naming.
     */
    private static String extractAnimation(String animJson, String slot) {
        int key = animJson.indexOf("." + slot + "\"");
        assertThat(key).as("animation slot " + slot + " present").isGreaterThan(0);
        // Slice the balanced JSON object after the key (brace counting) so
        // main/off-hand slices are directly comparable regardless of what
        // follows them in the document.
        int bodyStart = animJson.indexOf('{', key);
        int depth = 0;
        for (int i = bodyStart; i < animJson.length(); i++) {
            char c = animJson.charAt(i);
            if (c == '{') depth++;
            if (c == '}') {
                depth--;
                if (depth == 0) {
                    return animJson.substring(bodyStart, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced JSON after slot " + slot);
    }

    @Test
    @DisplayName("offsets_only without elements uses texture_meshes (extruded PNG)")
    void offsetsOnlyWithoutElementsUsesTextureMesh() {
        JavaModelDisplay display = new JavaModelDisplay(
            JavaModelDisplay.Transform.identity(),
            JavaModelDisplay.Transform.identity(),
            null, null, null);

        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false, false);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "flat_icon", display, null, "textures/items/flat_icon",
            16, 16, config, null);

        String geo = artifacts.get(BedrockAttachableWriter.geometryEntryPath("flat_icon"));
        // item/generated-style models extrude the PNG like Java's renderer
        // instead of a hand-built quad (java2bedrock scheme).
        assertThat(geo).contains("\"texture_meshes\"");
        assertThat(geo).doesNotContain("\"cubes\"");
    }

    /**
     * ValhallaMMO golden great-axe display block (first- and third-person
     * right hand). Shared by the pivot regression tests so both assert
     * against the same real-world transform values.
     */
    private static JavaModelDisplay greatAxeDisplay() {
        return new JavaModelDisplay(
            new JavaModelDisplay.Transform(
                new float[]{55f, 0f, 90f},
                new float[]{0f, 4f, -9f},
                new float[]{1.7f, 1.7f, 1.7f}),
            new JavaModelDisplay.Transform(
                new float[]{45f, 0f, 90f},
                new float[]{-15.5f, 13f, 1.5f},
                new float[]{2f, 2f, 2f}),
            null, null, null);
    }

    /**
     * Two-element great-axe geometry (blade + handle) whose converted Bedrock
     * cubes span exactly the golden bounds min[-4.5,0,-4.5] max[8,0.5,8]:
     * the blade element sets the full extent and the handle stays inside it,
     * so the bounds centre is [1.75,0.25,1.75].
     */
    private static JavaModelGeometry greatAxeGeometry() {
        JavaModelGeometry.Face face = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#0", 0);
        JavaModelGeometry.Element blade = new JavaModelGeometry.Element(
            new float[]{0f, 0f, 3.5f}, new float[]{12.5f, 0.5f, 16f}, null,
            Map.of("north", face, "up", face));
        JavaModelGeometry.Element handle = new JavaModelGeometry.Element(
            new float[]{5f, 0f, 7f}, new float[]{7f, 0.5f, 10f}, null,
            Map.of("north", face));
        return new JavaModelGeometry(List.of(blade, handle));
    }

    /**
     * Pivot of the (single) bone that carries the geometry cubes. The writer
     * emits {@code "pivot"} immediately before {@code "cubes"} on that bone,
     * so the nearest pivot preceding the cubes key belongs to the cube bone.
     */
    private static float[] cubeBonePivot(String geometryJson) {
        int cubesIdx = geometryJson.indexOf("\"cubes\"");
        assertThat(cubesIdx).as("cubes present in geometry JSON").isGreaterThan(0);
        int pivotIdx = geometryJson.lastIndexOf("\"pivot\"", cubesIdx);
        assertThat(pivotIdx).as("pivot precedes cubes on the cube bone").isGreaterThan(0);
        return parseFloat3After(geometryJson, pivotIdx);
    }

    /** Name of the bone that carries the geometry cubes. */
    private static String cubeBoneName(String geometryJson) {
        int cubesIdx = geometryJson.indexOf("\"cubes\"");
        assertThat(cubesIdx).as("cubes present in geometry JSON").isGreaterThan(0);
        int nameIdx = geometryJson.lastIndexOf("\"name\"", cubesIdx);
        assertThat(nameIdx).as("name precedes cubes on the cube bone").isGreaterThan(0);
        int colon = geometryJson.indexOf(':', nameIdx);
        return parseStringAfter(geometryJson, colon);
    }

    /**
     * Name of the bone the {@code firstperson_main_hand} animation drives.
     * Relies on the writer's stable
     * {@code animation.geyserextra.<icon>.firstperson_main_hand} key and its
     * single-bone {@code "bones": { "<name>": ... }} body.
     */
    private static String firstPersonAnimatedBoneName(String animJson) {
        int key = animJson.indexOf(".firstperson_main_hand\"");
        assertThat(key).as("firstperson_main_hand animation present").isGreaterThan(0);
        int bonesIdx = animJson.indexOf("\"bones\"", key);
        assertThat(bonesIdx).as("bones block present on first-person animation").isGreaterThan(0);
        int braceIdx = animJson.indexOf('{', bonesIdx);
        return parseStringAfter(animJson, braceIdx);
    }

    /** Parses the first {@code [a, b, c]} array appearing after {@code fromIndex}. */
    private static float[] parseFloat3After(String json, int fromIndex) {
        int open = json.indexOf('[', fromIndex);
        int close = json.indexOf(']', open);
        String[] parts = json.substring(open + 1, close).split(",");
        return new float[]{
            Float.parseFloat(parts[0].trim()),
            Float.parseFloat(parts[1].trim()),
            Float.parseFloat(parts[2].trim())
        };
    }

    /** Parses the first double-quoted string value appearing after {@code fromIndex}. */
    private static String parseStringAfter(String json, int fromIndex) {
        int open = json.indexOf('"', fromIndex);
        int close = json.indexOf('"', open + 1);
        return json.substring(open + 1, close);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
