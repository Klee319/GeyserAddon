package com.geyserextra.paper.pack;

import com.geyserextra.core.config.GeyserExtraConfig.AttachableGenerationConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

@DisplayName("BedrockAttachableWriter geometry mode")
class BedrockAttachableWriterTest {

    /**
     * The frame is baked to {@code ZXY} now, but the guarantee that came with
     * it still has to hold: the change of basis may only ever move first
     * person. Third person must stay on java2bedrock's per-axis sign flips,
     * which is what {@code convertTranslation} is here as the reference for.
     *
     * <p>It holds because the third-person root is {@code [90, 0, 0]}: with two
     * angles zero the rotation matrix has a single non-zero off-diagonal term
     * per row and the transpose reduces to exactly those sign flips (the
     * {@code cos(90°) = 6.1e-17} residue is far below float precision at these
     * magnitudes). Asserted through the public writer, with the real greataxe
     * display including its {@code *_lefthand} slots, because the unit-level
     * proof on {@code convertTranslationInRootFrame} does not cover how the
     * writer feeds it — which hand's root rotation it passes, or whether it
     * mirrors X consistently for the off hand.</p>
     */
    @Test
    @DisplayName("baked frame leaves third person on the java2bedrock mapping")
    void bakedFrameKeepsThirdPersonOnReferenceMapping() {
        JavaModelDisplay display = new JavaModelDisplay(
            new JavaModelDisplay.Transform(
                new float[]{55f, 0f, 90f}, new float[]{0f, 4f, -9f},
                new float[]{1.7f, 1.7f, 1.7f}),
            new JavaModelDisplay.Transform(
                new float[]{45f, 0f, 90f}, new float[]{-15.5f, 13f, 1.5f},
                new float[]{2f, 2f, 2f}),
            null, null, null,
            new JavaModelDisplay.Transform(
                new float[]{55f, 0f, -90f}, new float[]{26f, 4f, -9f},
                new float[]{1.7f, 1.7f, 1.7f}),
            new JavaModelDisplay.Transform(
                new float[]{45f, 0f, -90f}, new float[]{15.5f, 13f, 1.5f},
                new float[]{2f, 2f, 2f}));

        JavaModelGeometry.Face face = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#0", 0);
        JavaModelGeometry geometry = new JavaModelGeometry(List.of(
            new JavaModelGeometry.Element(
                new float[]{0f, 0f, 0f}, new float[]{4f, 4f, 4f}, null,
                Map.of("north", face, "south", face))));

        String anim = animationJson(display, geometry);

        // Both third-person hands, against convertTranslation on the
        // translation Java renders for that hand — the declared value for the
        // main hand, the left-hand-negated one for the off hand.
        assertThat(extractAnimation(anim, "thirdperson_main_hand").replaceAll("\\s+", ""))
            .as("third-person main hand must stay on the reference mapping")
            .contains(positionLiteral(BedrockGeometryConverter.convertTranslation(
                new float[]{-15.5f, 13f, 1.5f}, false, true)));
        assertThat(extractAnimation(anim, "thirdperson_off_hand").replaceAll("\\s+", ""))
            .as("third-person off hand must stay on the reference mapping")
            .contains(positionLiteral(BedrockGeometryConverter.convertTranslation(
                BedrockGeometryConverter.applyJavaLeftHandTranslation(
                    new float[]{15.5f, 13f, 1.5f}), false, true)));
    }

    /** The emitted JSON form of a position triple, whitespace already stripped. */
    private static String positionLiteral(float[] xyz) {
        return "\"position\":[" + xyz[0] + "," + xyz[1] + "," + xyz[2] + "]";
    }

    private static String animationJson(
        JavaModelDisplay display, JavaModelGeometry geometry
    ) {
        AttachableGenerationConfig config = new AttachableGenerationConfig(
            AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);
        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "probe", "probe", display, geometry,
            "textures/items/probe", 16, 16, config, null);
        return artifacts.entrySet().stream()
            .filter(e -> e.getKey().contains("animation"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no animation artifact: " + artifacts.keySet()));
    }

    @Test
    @DisplayName("offsets_only + elements uses full 3D cubes on the java2bedrock chain")
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
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

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
        // java2bedrock chain + geo leaf (not Rainbow single-bone).
        assertThat(geo).contains("\"binding\"");
        assertThat(geo).contains("\"geyserextra\"");
        assertThat(geo).contains("\"geyserextra_x\"");
        assertThat(geo).contains("\"geyserextra_y\"");
        assertThat(geo).contains("\"geyserextra_z\"");
        assertThat(geo).contains("\"geyserextra_geo\"");

        String anim = artifacts.get(BedrockAttachableWriter.animationEntryPath("test_hammer"));
        String thirdMain = extractAnimation(anim, "thirdperson_main_hand");
        // Third-person java2bedrock: root base (90,0,0)/(0,13,-3);
        // display rot (45,0,90) → (-45,0,90); trans (-15.5,13,1.5) → (15.5,13,1.5).
        assertThat(anim).contains("thirdperson_main_hand");
        assertThat(anim).contains("firstperson_main_hand");
        assertThat(thirdMain).contains("geyserextra_x");
        assertThat(thirdMain).contains("-45.0");
        assertThat(thirdMain).contains("90.0");
        assertThat(thirdMain).contains("15.5");
        assertThat(thirdMain).contains("13.0");
        assertThat(thirdMain).contains("1.5");
        assertThat(thirdMain).contains("2.0");
        assertThat(thirdMain).contains("-3.0");
    }

    @Test
    @DisplayName("first person + elements uses java2bedrock base pose + display decomposition")
    void firstPersonWithElementsUsesJava2BedrockMapping() {
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
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "test_hammer", display, geometry, "textures/items/test_hammer",
            32, 32, config, null);

        String anim = artifacts.get(BedrockAttachableWriter.animationEntryPath("test_hammer"));
        String firstMain = extractAnimation(anim, "firstperson_main_hand");
        String firstOff = extractAnimation(anim, "firstperson_off_hand");

        // First bone emitted in the bones block is geyserextra_x (display X).
        assertThat(firstPersonAnimatedBoneName(anim)).isEqualTo("geyserextra_x");

        // j2b FP base, identical for every item: root rot (90,60,-40),
        // pos [4,10,4] raised by the height correction → [4,12.7,4],
        // scale 1.5. The item-specific part is the display transform on
        // x/y/z: rot (55,0,90) → (-55,0,90), and translation [0,4,-9]
        // resolved in the root's own frame, then divided by the 1.5 root
        // scale → [2.05, 2.67, -5.64].
        //
        // java2bedrock's sign flips would give [-0, 2.67, 6.0] here. That is
        // the whole difference the baked ZXY frame makes: the root carries
        // [90,60,-40], so a Java Z offset does not land on Bedrock's Z, and
        // for a translation this large the error threw the item out of view.
        String compact = firstMain.replaceAll("\\s+", "");
        assertThat(compact).contains(
            "\"geyserextra\":{\"rotation\":[90.0,60.0,-40.0],\"position\":[4.0,12.7,4.0],\"scale\":1.5");
        assertThat(compact).contains("\"position\":[2.05,2.67,-5.64]");
        assertThat(firstMain).contains("-55.0");
        assertThat(firstMain).contains("1.7");
        assertThat(firstMain).contains("geyserextra_x");
        assertThat(firstMain).contains("geyserextra_y");
        assertThat(firstMain).contains("geyserextra_z");
        assertThat(firstOff).contains("geyserextra_x");
    }

    @Test
    @DisplayName("full 3D geometry pivots the cube bone at Java model-space centre [0,8,0], not AABB centre")
    void fullGeometryUsesJavaModelSpacePivot() {
        // ValhallaMMO golden great-axe: cubes span AABB centre [1.75,0.25,1.75],
        // but Java item display rotates around baked origin (8,8,8) → Bedrock
        // [0,8,0]. Using AABB centre made FP/TP poses swing off the hand.
        JavaModelDisplay display = greatAxeDisplay();
        JavaModelGeometry geometry = greatAxeGeometry();

        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "golden_great_axe", display, geometry, "textures/items/golden_great_axe",
            32, 32, config, null);

        String geo = artifacts.get(BedrockAttachableWriter.geometryEntryPath("golden_great_axe"));
        float[] pivot = cubeBonePivot(geo);
        assertThat(pivot[0]).isCloseTo(0f, offset(1e-4f));
        assertThat(pivot[1]).isCloseTo(8f, offset(1e-4f));
        assertThat(pivot[2]).isCloseTo(0f, offset(1e-4f));
    }

    @Test
    @DisplayName("first person drives x/y/z; cubes live on geyserextra_geo pivoted at [0,8,0]")
    void firstPersonRotatesAroundJavaModelSpacePivot() {
        JavaModelDisplay display = greatAxeDisplay();
        JavaModelGeometry geometry = greatAxeGeometry();

        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "golden_great_axe", display, geometry, "textures/items/golden_great_axe",
            32, 32, config, null);

        String geo = artifacts.get(BedrockAttachableWriter.geometryEntryPath("golden_great_axe"));
        String anim = artifacts.get(BedrockAttachableWriter.animationEntryPath("golden_great_axe"));

        String animatedBone = firstPersonAnimatedBoneName(anim);
        String cubeBone = cubeBoneName(geo);
        assertThat(animatedBone).isEqualTo("geyserextra_x");
        assertThat(cubeBone).isEqualTo("geyserextra_geo");

        float[] pivot = cubeBonePivot(geo);
        assertThat(pivot[0]).isCloseTo(0f, offset(1e-4f));
        assertThat(pivot[1]).isCloseTo(8f, offset(1e-4f));
        assertThat(pivot[2]).isCloseTo(0f, offset(1e-4f));
    }

    @Test
    @DisplayName("explicit firstPersonBasePose overrides global FP base constants (same java2bedrock path)")
    void explicitBasePoseOverridesFirstPersonDefaults() {
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
            AttachableGenerationConfig.MODE_OFFSETS_ONLY, false,
            new AttachableGenerationConfig.BasePose(
                new float[]{90f, 45f, -30f}, new float[]{5f, 11f, 3f}, 1.25f));

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "test_hammer", display, geometry, "textures/items/test_hammer",
            32, 32, config, null);

        String geo = artifacts.get(BedrockAttachableWriter.geometryEntryPath("test_hammer"));
        assertThat(geo).contains("\"geyserextra_x\"");
        assertThat(geo).contains("\"geyserextra_geo\"");
        float[] geoPivot = cubeBonePivot(geo);
        assertThat(geoPivot[0]).isCloseTo(0f, offset(1e-4f));
        assertThat(geoPivot[1]).isCloseTo(8f, offset(1e-4f));
        assertThat(geoPivot[2]).isCloseTo(0f, offset(1e-4f));

        String firstMain = extractAnimation(
            artifacts.get(BedrockAttachableWriter.animationEntryPath("test_hammer")),
            "firstperson_main_hand");
        assertThat(firstMain).contains("45.0");
        assertThat(firstMain).contains("-30.0");
        // The override is applied verbatim: the root pose is the same for
        // every item, so nothing here depends on the model's display scale.
        String compact = firstMain.replaceAll("\\s+", "");
        assertThat(compact).contains(
            "\"geyserextra\":{\"rotation\":[90.0,45.0,-30.0],\"position\":[5.0,11.0,3.0],\"scale\":1.25");
        assertThat(firstMain).contains("geyserextra_x");
    }

    @Test
    @DisplayName("3D FP keeps the Java display translation and the fixed java2bedrock root pose")
    void firstPerson3dKeepsJavaTranslationOnFixedRoot() {
        // Greataxe-class: display scale 1.7, translation [0,4,-9]. This is the
        // case the removed oversize heuristics were invented for.
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
            AttachableGenerationConfig.MODE_OFFSETS_ONLY, false, null);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "test_axe", display, geometry, "textures/items/test_axe",
            32, 32, config, null);

        String firstMain = extractAnimation(
            artifacts.get(BedrockAttachableWriter.animationEntryPath("test_axe")),
            "firstperson_main_hand");
        String compact = firstMain.replaceAll("\\s+", "");
        // [0,4,-9] resolved in the root's [90,60,-40] frame, then divided by
        // the 1.5 root scale.
        assertThat(compact).contains("\"position\":[2.05,2.67,-5.64]");
        assertThat(compact).contains("\"scale\":[1.7,1.7,1.7]");
        assertThat(firstMain).contains("-55.0");
        // Root is the java2bedrock constant, untouched by display scale.
        assertThat(compact).contains(
            "\"geyserextra\":{\"rotation\":[90.0,60.0,-40.0],\"position\":[4.0,12.7,4.0],\"scale\":1.5");
    }

    @Test
    @DisplayName("texture-only uses flat FP pose (not Valhalla 3D frame) and keeps translation")
    void textureOnlyUsesFlatFpPoseAndKeepsTranslation() {
        // infinity_sword-style: parent item/handheld, custom layer0 only.
        JavaModelDisplay display = VanillaBuiltinDisplays.HANDHELD;
        AttachableGenerationConfig config = new AttachableGenerationConfig(
            AttachableGenerationConfig.MODE_OFFSETS_ONLY, false,
            new AttachableGenerationConfig.BasePose(
                new float[]{90f, 60f, -28f}, new float[]{25f, 7f, 10f}, 3.2f));

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "infinity_sword", display, null, "textures/items/infinity_sword",
            16, 16, config, null);

        String firstMain = extractAnimation(
            artifacts.get(BedrockAttachableWriter.animationEntryPath("infinity_sword")),
            "firstperson_main_hand");
        String compact = firstMain.replaceAll("\\s+", "");
        // Must NOT use Valhalla 3D frame.
        assertThat(compact).doesNotContain("\"position\":[25.0,7.0,10.0]");
        assertThat(compact).doesNotContain("\"scale\":3.2");
        // Flat-only pose: [0,15,4]/1.5.
        assertThat(compact).contains(
            "\"geyserextra\":{\"rotation\":[90.0,60.0,-40.0],\"position\":[0.0,17.7,4.0],\"scale\":1.5");
        // Handheld FP translation kept.
        assertThat(compact).doesNotContainPattern(
            "\"geyserextra_x\":\\{\"rotation\":\\[[^]]+\\],\"position\":\\[-?0\\.0,-?0\\.0,-?0\\.0\\]");
    }

    @Test
    @DisplayName("flat FP root pose is the same for a small-scale item (morningstar-class)")
    void morningstarScaleKeepsBaseFpRootPosition() {
        JavaModelDisplay display = new JavaModelDisplay(
            new JavaModelDisplay.Transform(
                new float[]{55f, 0f, 90f},
                new float[]{1.13f, 3.2f, -2.12f},
                new float[]{0.68f, 0.68f, 0.68f}),
            null, null, null, null);

        AttachableGenerationConfig config = new AttachableGenerationConfig(
            AttachableGenerationConfig.MODE_OFFSETS_ONLY, false, null);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "test_mace", display, null, "textures/items/test_mace",
            16, 16, config, null);

        String firstMain = extractAnimation(
            artifacts.get(BedrockAttachableWriter.animationEntryPath("test_mace")),
            "firstperson_main_hand");
        String compact = firstMain.replaceAll("\\s+", "");
        // Flat pose [0,15,4]/1.5 (no oversize when display scale 0.68).
        assertThat(compact).contains(
            "\"geyserextra\":{\"rotation\":[90.0,60.0,-40.0],\"position\":[0.0,17.7,4.0],\"scale\":1.5");
    }

    @Test
    @DisplayName("flat FP root pose does not change with an oversized display scale")
    void flatItemsKeepFlatFpRootWhenOversized() {
        JavaModelDisplay display = new JavaModelDisplay(
            new JavaModelDisplay.Transform(
                new float[]{55f, 0f, 90f},
                new float[]{0f, 4f, -9f},
                new float[]{1.7f, 1.7f, 1.7f}),
            null, null, null, null);

        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "flat_item", display, null, "textures/items/flat_item",
            16, 16, config, null);

        String firstMain = extractAnimation(
            artifacts.get(BedrockAttachableWriter.animationEntryPath("flat_item")),
            "firstperson_main_hand");
        String compact = firstMain.replaceAll("\\s+", "");
        // Fixed flat pose [0,15,4]/1.5 regardless of the display scale.
        assertThat(compact).contains(
            "\"geyserextra\":{\"rotation\":[90.0,60.0,-40.0],\"position\":[0.0,17.7,4.0],\"scale\":1.5");
        assertThat(firstMain).contains("geyserextra_x");
    }

    @Test
    @DisplayName("flat and 3D items get the same first-person root scale")
    void flatAndGeometryPathsAgreeOnFirstPersonRootScale() {
        // The regression this pins: the flat root scale was an eyeballed 1.75
        // against the 3D path's 1.5, so the same Java display scale rendered
        // 16.7% larger whenever a model happened to lack an `elements` block.
        // Whether the operator authored real cubes is not a property of the
        // frame mapping, so it must not change how big the item is; the two
        // paths having drifted apart is what made the mace visibly outsize
        // Bedrock's own vanilla mace. Asserting they agree — rather than
        // re-pinning 1.5 a fourth time — is what catches a future one-sided
        // nudge, which is exactly how this arose.
        JavaModelDisplay display = VanillaBuiltinDisplays.HANDHELD_MACE;
        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        String flat = rootScaleOfFirstPersonMainHand("flat_mace", display, null, config);
        String solid = rootScaleOfFirstPersonMainHand(
            "solid_mace", display, cubeGeometry(), config);

        assertThat(flat)
            .as("texture-only and cube-bearing items must be the same size in hand")
            .isEqualTo(solid);
    }

    /** Single 1x1x1 cube — enough to send an item down the full-geometry path. */
    private static JavaModelGeometry cubeGeometry() {
        return new JavaModelGeometry(List.of(new JavaModelGeometry.Element(
            new float[]{7f, 7f, 7f}, new float[]{9f, 9f, 9f}, null)));
    }

    private static String rootScaleOfFirstPersonMainHand(
        String iconKey,
        JavaModelDisplay display,
        JavaModelGeometry geometry,
        AttachableGenerationConfig config
    ) {
        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            iconKey, display, geometry, "textures/items/" + iconKey, 16, 16, config, null);
        String anim = extractAnimation(
            artifacts.get(BedrockAttachableWriter.animationEntryPath(iconKey)),
            "firstperson_main_hand").replaceAll("\\s+", "");
        Matcher m = Pattern.compile("\"geyserextra\":\\{[^}]*\"scale\":([0-9.]+)").matcher(anim);
        assertThat(m.find()).as("root scale present in %s", iconKey).isTrue();
        return m.group(1);
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
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

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
    @Test
    @DisplayName("off hand emits the declared *_lefthand rotation as authored")
    void offHandUsesLeftHandTransform() {
        // Dagger-class: the left-hand slot states rotation.z = -90 against a
        // right hand of +90. That declared -90 is what gets emitted. Vanilla
        // would negate it back to +90 (ItemTransform#apply), but that negation
        // compensates Java's mirrored left arm and Bedrock does not need it —
        // see BedrockAttachableWriter's "Off hand" javadoc.
        JavaModelDisplay display = new JavaModelDisplay(
            new JavaModelDisplay.Transform(
                new float[]{55f, 0f, 90f}, new float[]{1.13f, 3.2f, -2.12f},
                new float[]{0.68f, 0.68f, 0.68f}),
            new JavaModelDisplay.Transform(
                new float[]{45f, 0f, 90f}, new float[]{-6.5f, 4f, 0.5f},
                new float[]{0.85f, 0.85f, 0.85f}),
            null, null, null,
            new JavaModelDisplay.Transform(
                new float[]{55f, 0f, -90f}, new float[]{11.3f, 3.2f, -2.12f},
                new float[]{0.68f, 0.68f, 0.68f}),
            new JavaModelDisplay.Transform(
                new float[]{45f, 0f, -90f}, new float[]{7.5f, 4f, 0.5f},
                new float[]{0.85f, 0.85f, 0.85f}));

        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "test_dagger", display, null, "textures/items/test_dagger",
            16, 16, config, null);
        String anim = artifacts.get(BedrockAttachableWriter.animationEntryPath("test_dagger"));

        String thirdMain = extractAnimation(anim, "thirdperson_main_hand").replaceAll("\\s+", "");
        String thirdOff = extractAnimation(anim, "thirdperson_off_hand").replaceAll("\\s+", "");
        // Translation X still negates for the off hand — the one half of
        // vanilla's left-hand rule that does carry over. The declared +7.5
        // becomes -7.5, and the Java->Bedrock mirror negates it back to +7.5,
        // putting the weapon on the off-hand side. Emitting the declared value
        // unmirrored put it 15 units out on the main-hand side.
        assertThat(thirdMain).contains("\"position\":[6.5,4.0,0.5]");
        assertThat(thirdOff).contains("\"position\":[7.5,4.0,0.5]");
        // Rotation is NOT negated, so the hands come out opposite: the author's
        // -90 stays -90. Forcing them equal (by also applying vanilla's
        // rotation negation) is what put every off-hand item 180 degrees round.
        assertThat(thirdMain).contains("\"geyserextra_z\":{\"rotation\":[0.0,0.0,90.0]}");
        assertThat(thirdOff).contains("\"geyserextra_z\":{\"rotation\":[0.0,0.0,-90.0]}");
    }

    @Test
    @DisplayName("off hand falls back to the right-hand slot, unnegated, when no *_lefthand exists")
    void offHandFallsBackToMirroredRightHand() {
        JavaModelDisplay display = new JavaModelDisplay(
            null,
            new JavaModelDisplay.Transform(
                new float[]{45f, 0f, 90f}, new float[]{-6.5f, 4f, 0.5f},
                new float[]{0.85f, 0.85f, 0.85f}),
            null, null, null);

        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "test_plain", display, null, "textures/items/test_plain",
            16, 16, config, null);
        String anim = artifacts.get(BedrockAttachableWriter.animationEntryPath("test_plain"));

        // ItemTransforms.Deserializer substitutes the right-hand transform for
        // the missing slot, so that is what the off hand renders — unnegated,
        // which leaves its rotation identical to the main hand's. Note this is
        // the exact inverse of the declared-slot case above, where the two
        // hands come out opposite: a model that pre-mirrors its lefthand slot
        // gets mirrored hands, one that omits it gets matching hands. The
        // rotation therefore cannot be decided from which hand it is, only
        // from which slot supplied it.
        String off = extractAnimation(anim, "thirdperson_off_hand").replaceAll("\\s+", "");
        assertThat(off).contains("\"geyserextra_z\":{\"rotation\":[0.0,0.0,90.0]}");
        // Translation X still mirrors, so the item sits on the off-hand side
        // even though the rotation matched.
        assertThat(off).contains("\"position\":[-6.5,4.0,0.5]");
        assertThat(extractAnimation(anim, "thirdperson_main_hand").replaceAll("\\s+", ""))
            .contains("\"position\":[6.5,4.0,0.5]");
    }

    @Test
    @DisplayName("visible bounds grow past the java2bedrock defaults for an oversized mesh")
    void visibleBoundsGrowForOversizedMesh() {
        JavaModelDisplay display = greatAxeDisplay();
        // A mesh that reaches outside Java's 0..16 box (legal: models may span
        // -16..32) exceeds the fixed 4 x 4.5 default once the display and root
        // scales are applied.
        JavaModelGeometry.Face face = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#0", 0);
        JavaModelGeometry geometry = new JavaModelGeometry(List.of(
            new JavaModelGeometry.Element(
                new float[]{-8f, -8f, -8f}, new float[]{24f, 24f, 24f}, null,
                Map.of("north", face))));

        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        String geo = BedrockAttachableWriter.buildArtifacts(
                "big_axe", display, geometry, "textures/items/big_axe",
                32, 32, config, null)
            .get(BedrockAttachableWriter.geometryEntryPath("big_axe"));

        float width = descriptorFloat(geo, "visible_bounds_width");
        float height = descriptorFloat(geo, "visible_bounds_height");
        assertThat(width).isGreaterThan(4f);
        assertThat(height).isGreaterThan(4.5f);
    }

    @Test
    @DisplayName("visible bounds never shrink below the java2bedrock defaults for a small mesh")
    void visibleBoundsNeverShrink() {
        // A small mesh AND a display that barely moves it — the great-axe
        // display would legitimately widen the box, since it throws the mesh
        // 15.5 units out of the bone (see visibleBoundsCoverAnimationReach).
        JavaModelDisplay display = new JavaModelDisplay(
            new JavaModelDisplay.Transform(
                new float[]{0f, 0f, 0f}, new float[]{0f, 1f, 0f},
                new float[]{1f, 1f, 1f}),
            new JavaModelDisplay.Transform(
                new float[]{0f, 0f, 0f}, new float[]{0f, 1f, 0f},
                new float[]{1f, 1f, 1f}),
            null, null, null);
        JavaModelGeometry.Face face = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 1f, 1f}, "#0", 0);
        JavaModelGeometry geometry = new JavaModelGeometry(List.of(
            new JavaModelGeometry.Element(
                new float[]{8f, 8f, 8f}, new float[]{9f, 9f, 9f}, null,
                Map.of("north", face))));

        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        String geo = BedrockAttachableWriter.buildArtifacts(
                "tiny", display, geometry, "textures/items/tiny",
                16, 16, config, null)
            .get(BedrockAttachableWriter.geometryEntryPath("tiny"));

        assertThat(descriptorFloat(geo, "visible_bounds_width")).isEqualTo(4f);
        assertThat(descriptorFloat(geo, "visible_bounds_height")).isEqualTo(4.5f);
    }

    @Test
    @DisplayName("geometry format_version is raised to 1.21.0 only when uv_rotation is emitted")
    void formatVersionTracksUvRotationUse() {
        JavaModelDisplay display = greatAxeDisplay();
        JavaModelGeometry.Face plain = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#0", 0);
        JavaModelGeometry.Face rotated = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#0", 90);
        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        String plainGeo = BedrockAttachableWriter.buildArtifacts(
                "plain", display,
                new JavaModelGeometry(List.of(new JavaModelGeometry.Element(
                    new float[]{0f, 0f, 0f}, new float[]{4f, 4f, 4f}, null,
                    Map.of("north", plain)))),
                "textures/items/plain", 16, 16, config, null)
            .get(BedrockAttachableWriter.geometryEntryPath("plain"));
        assertThat(plainGeo.replaceAll("\\s+", "")).contains("\"format_version\":\"1.16.0\"");

        String rotatedGeo = BedrockAttachableWriter.buildArtifacts(
                "rotated", display,
                new JavaModelGeometry(List.of(new JavaModelGeometry.Element(
                    new float[]{0f, 0f, 0f}, new float[]{4f, 4f, 4f}, null,
                    Map.of("east", rotated)))),
                "textures/items/rotated", 16, 16, config, null)
            .get(BedrockAttachableWriter.geometryEntryPath("rotated"));
        assertThat(rotatedGeo.replaceAll("\\s+", "")).contains("\"format_version\":\"1.21.0\"");
        assertThat(rotatedGeo.replaceAll("\\s+", "")).contains("\"uv_rotation\":90");

        // A geometry at 1.21.0 is unreadable by a client that honours a
        // 1.16.100 manifest, so the pack manifest has to move with it.
        assertThat(AutoBedrockPackBuilder.requiresModernGeometry(
            Map.of(BedrockAttachableWriter.geometryEntryPath("rotated"), rotatedGeo))).isTrue();
        assertThat(AutoBedrockPackBuilder.requiresModernGeometry(
            Map.of(BedrockAttachableWriter.geometryEntryPath("plain"), plainGeo))).isFalse();
        assertThat(AutoBedrockPackBuilder.buildManifestJson(3, true).replaceAll("\\s+", ""))
            .contains("\"min_engine_version\":[1,21,0]");
        assertThat(AutoBedrockPackBuilder.buildManifestJson(3, false).replaceAll("\\s+", ""))
            .contains("\"min_engine_version\":[1,16,100]");
    }

    @Test
    @DisplayName("per-face uv_rotation is always emitted, pinning geometry to 1.21.0")
    void faceUvRotationIsAlwaysOn() {
        // The rollback that emitted legacy 1.16.0 geometry (mirror 180, drop
        // 90/270) is gone along with its config key: pre-1.21.0 Bedrock clients
        // can no longer load the pack, and there is no lever left to let them.
        JavaModelDisplay display = greatAxeDisplay();
        JavaModelGeometry.Face rotated90 = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#0", 90);
        JavaModelGeometry.Face rotated180 = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 8f, 8f}, "#0", 180);
        AttachableGenerationConfig config = new AttachableGenerationConfig(
            AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        String geo = BedrockAttachableWriter.buildArtifacts(
                "rotated", display,
                new JavaModelGeometry(List.of(new JavaModelGeometry.Element(
                    new float[]{0f, 0f, 0f}, new float[]{4f, 4f, 4f}, null,
                    Map.of("east", rotated90, "north", rotated180)))),
                "textures/items/rotated", 16, 16, config, null)
            .get(BedrockAttachableWriter.geometryEntryPath("rotated"));
        String compact = geo.replaceAll("\\s+", "");

        assertThat(compact).contains("uv_rotation");
        assertThat(compact).contains("\"format_version\":\"1.21.0\"");
        // The 180 face is stated outright rather than emulated by a
        // point-mirrored rect with negative sizes.
        assertThat(compact).doesNotContain("\"uv_size\":[-8.0,-8.0]");
        assertThat(AutoBedrockPackBuilder.requiresModernGeometry(
            Map.of(BedrockAttachableWriter.geometryEntryPath("rotated"), geo))).isTrue();
    }

    @Test
    @DisplayName("first-person off hand mirrors the base pose, third person leaves it alone")
    void firstPersonOffHandMirrorsBasePose() {
        JavaModelDisplay display = greatAxeDisplay();
        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        String anim = BedrockAttachableWriter.buildArtifacts(
                "mirror_axe", display, null, "textures/items/mirror_axe",
                16, 16, config, null)
            .get(BedrockAttachableWriter.animationEntryPath("mirror_axe"));

        String fpMain = extractAnimation(anim, "firstperson_main_hand").replaceAll("\\s+", "");
        String fpOff = extractAnimation(anim, "firstperson_off_hand").replaceAll("\\s+", "");
        // Flat items ride FLAT_FIRST_PERSON_POSE: [90, 60, -40] / [0, 15, 4].
        assertThat(fpMain).contains("\"geyserextra\":{\"rotation\":[90.0,60.0,-40.0]");
        // The off hand gets its mirror image, or the right-arm mapping swings
        // the item clean out of the first-person viewport.
        assertThat(fpOff).contains("\"geyserextra\":{\"rotation\":[90.0,-60.0,40.0]");

        // Third person's base pose is already mirror-invariant, so both hands
        // must keep it byte-identical.
        String tpMain = extractAnimation(anim, "thirdperson_main_hand").replaceAll("\\s+", "");
        String tpOff = extractAnimation(anim, "thirdperson_off_hand").replaceAll("\\s+", "");
        assertThat(tpMain).contains("\"geyserextra\":{\"rotation\":[90.0,0.0,0.0],\"position\":[0.0,13.0,-3.0]}");
        assertThat(tpOff).contains("\"geyserextra\":{\"rotation\":[90.0,0.0,0.0],\"position\":[0.0,13.0,-3.0]}");
    }

    @Test
    @DisplayName("visible bounds cover where the animation puts the mesh, not just the mesh")
    void visibleBoundsCoverAnimationReach() {
        // A small mesh with a large display translation: the geometry alone
        // would fit the default box, but the hold animation throws it 26 units
        // sideways, which is where Bedrock actually has to look for it.
        JavaModelDisplay farOffset = new JavaModelDisplay(
            new JavaModelDisplay.Transform(
                new float[]{55f, 0f, 90f}, new float[]{26f, 4f, -9f},
                new float[]{1f, 1f, 1f}),
            new JavaModelDisplay.Transform(
                new float[]{45f, 0f, 90f}, new float[]{0f, 0f, 0f},
                new float[]{1f, 1f, 1f}),
            null, null, null);
        JavaModelGeometry.Face face = new JavaModelGeometry.Face(
            new float[]{0f, 0f, 16f, 16f}, "#0", 0);
        JavaModelGeometry small = new JavaModelGeometry(List.of(
            new JavaModelGeometry.Element(
                new float[]{7f, 7f, 7f}, new float[]{9f, 9f, 9f}, null,
                Map.of("north", face))));

        AttachableGenerationConfig config =
            new AttachableGenerationConfig(AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        String geo = BedrockAttachableWriter.buildArtifacts(
                "far_item", farOffset, small, "textures/items/far_item",
                16, 16, config, null)
            .get(BedrockAttachableWriter.geometryEntryPath("far_item"));

        assertThat(descriptorFloat(geo, "visible_bounds_width"))
            .as("box widened to reach the 26-unit display translation")
            .isGreaterThan(4f);
        assertThat(descriptorFloat(geo, "visible_bounds_height"))
            .isGreaterThan(4.5f);
    }

    @Test
    @DisplayName("removed tuning keys in an old config.json are ignored, not fatal")
    void retiredKeysDoNotBreakDeserialisation() {
        // Every install carries these five in its config.json from before they
        // were settled and compiled in. Gson must skip them silently — throwing
        // here would take the whole plugin down on enable, and
        // firstPersonHeightOffset (the one key still live) has to survive the
        // company.
        AttachableGenerationConfig parsed = com.geyserextra.core.util.JsonUtil.fromJson(
            "{\"firstPersonTranslationFrame\":\"j2b\",\"faceUvRotation\":false,"
                + "\"rainbowFirstPersonMapping\":true,\"mirrorOffHandTranslation\":false,"
                + "\"debugDumpArtifacts\":true,\"firstPersonHeightOffset\":4.25}",
            AttachableGenerationConfig.class);
        assertThat(parsed.firstPersonHeightOffset()).isEqualTo(4.25f);
        // An absent key still falls back to the tuned default rather than 0.
        assertThat(com.geyserextra.core.util.JsonUtil.fromJson(
            "{}", AttachableGenerationConfig.class).firstPersonHeightOffset())
            .isEqualTo(AttachableGenerationConfig.BasePose.FIRST_PERSON_HEIGHT_TRIM);
    }

    /** Reads a single numeric field out of the geometry descriptor block. */
    private static float descriptorFloat(String geometryJson, String field) {
        String compact = geometryJson.replaceAll("\\s+", "");
        int at = compact.indexOf("\"" + field + "\":");
        assertThat(at).as(field + " present").isGreaterThan(0);
        int start = at + field.length() + 3;
        int end = start;
        while (end < compact.length()
            && (Character.isDigit(compact.charAt(end)) || compact.charAt(end) == '.'
                || compact.charAt(end) == '-')) {
            end++;
        }
        return Float.parseFloat(compact.substring(start, end));
    }

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
     * cubes span AABB min[-4.5,0,-4.5] max[8,0.5,8] (centre [1.75,0.25,1.75]).
     * The java2bedrock geo leaf must still pivot at [0,8,0], not that AABB centre.
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
     * Name of the first bone listed under {@code firstperson_main_hand}.
     * On the java2bedrock path that is {@code geyserextra_x}.
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
