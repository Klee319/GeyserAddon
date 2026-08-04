package com.geyserextra.paper.pack;

import com.geyserextra.core.config.GeyserExtraConfig.AttachableGenerationConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("Vanilla builtin display injection for CMD handheld items")
class VanillaBuiltinDisplaysTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("parent item/handheld only resolves Mojang handheld + generated slots")
    void handheldParentInjectsBuiltinDisplay() throws Exception {
        Path model = tempDir.resolve(
            "assets/minecraft/models/item/custom_cmd_sword.json");
        Path texture = tempDir.resolve(
            "assets/minecraft/textures/item/custom_cmd_sword.png");
        Files.createDirectories(model.getParent());
        Files.createDirectories(texture.getParent());
        Files.writeString(model, """
            {"parent":"item/handheld","textures":{
            "layer0":"minecraft:item/custom_cmd_sword"}}
            """);
        Files.write(texture, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        JavaPackReader reader = new JavaPackReader(
            tempDir, "AUTO", Logger.getAnonymousLogger(), false);
        JavaModelDisplay display =
            reader.resolveDisplayFromModel("minecraft:item/custom_cmd_sword");

        assertThat(display).isNotNull();
        assertThat(display.hasAnyHandTransform()).isTrue();

        float[] tpRot = display.thirdpersonRighthand().rotation();
        float[] tpTrans = display.thirdpersonRighthand().translation();
        float[] tpScale = display.thirdpersonRighthand().scale();
        assertThat(tpRot[0]).isCloseTo(0f, within(0.001f));
        assertThat(tpRot[1]).isCloseTo(-90f, within(0.001f));
        assertThat(tpRot[2]).isCloseTo(55f, within(0.001f));
        assertThat(tpTrans[1]).isCloseTo(4f, within(0.001f));
        assertThat(tpTrans[2]).isCloseTo(0.5f, within(0.001f));
        assertThat(tpScale[0]).isCloseTo(0.85f, within(0.001f));

        // generated fills ground after handheld → generated walk
        assertThat(display.ground()).isNotNull();
        assertThat(display.ground().scale()[0]).isCloseTo(0.5f, within(0.001f));

        // The builtin merge has to carry the left-hand slots across as well.
        // Copying only the five right-hand/gui/ground/head slots left these
        // null, which reads as "mirror the right hand" and turned every
        // handheld-inherited tool backwards in the off hand.
        assertThat(display.thirdpersonLefthand())
            .as("thirdperson_lefthand inherited from item/handheld").isNotNull();
        assertThat(display.thirdpersonLefthand().rotation())
            .containsExactly(0f, 90f, -55f);
        assertThat(display.firstpersonLefthand())
            .as("firstperson_lefthand inherited from item/handheld").isNotNull();
        assertThat(display.firstpersonLefthand().rotation())
            .containsExactly(0f, 90f, -25f);
    }

    @Test
    @DisplayName("handheld builtin display produces third-person attachable animation")
    void handheldDisplayEmitsThirdPersonAttachable() {
        AttachableGenerationConfig config = new AttachableGenerationConfig(
            AttachableGenerationConfig.MODE_FULL, false);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "custom_cmd_sword",
            VanillaBuiltinDisplays.HANDHELD,
            null,
            "textures/items/custom_cmd_sword",
            16, 16, config, null);

        assertThat(artifacts).isNotEmpty();
        String anim = artifacts.get(
            BedrockAttachableWriter.animationEntryPath("custom_cmd_sword"));
        assertThat(anim).contains("thirdperson_main_hand");
        // Java [0,-90,55] → Bedrock rot [0,90,55]; trans TP main (0,4,0.5)
        assertThat(anim).contains("90.0");
        assertThat(anim).contains("55.0");
        assertThat(anim).contains("4.0");
        assertThat(anim).contains("0.5");
        assertThat(anim).contains("0.85");
    }

    @Test
    @DisplayName("handheld declares its left-hand slots, so Java renders both hands alike")
    void handheldDeclaresLeftHandSlots() {
        // assets/minecraft/models/item/handheld.json declares
        //   thirdperson_lefthand rotation [0, 90, -55]
        //   firstperson_lefthand rotation [0, 90, -25]
        // Mojang negates rotation Y/Z for the left hand, so those come back out
        // as the right-hand values: an omitted slot would render mirrored, a
        // declared one renders identically. Pinning the declaration is what
        // stops the constant from silently reverting to "mirror the right hand".
        JavaModelDisplay.Transform tpLeft = VanillaBuiltinDisplays.HANDHELD.thirdpersonLefthand();
        JavaModelDisplay.Transform fpLeft = VanillaBuiltinDisplays.HANDHELD.firstpersonLefthand();
        assertThat(tpLeft).isNotNull();
        assertThat(fpLeft).isNotNull();
        assertThat(tpLeft.rotation()).containsExactly(0f, 90f, -55f);
        assertThat(fpLeft.rotation()).containsExactly(0f, 90f, -25f);
        assertThat(tpLeft.translation()).containsExactly(0f, 4f, 0.5f);
        assertThat(fpLeft.translation()).containsExactly(1.13f, 3.2f, 1.13f);

        // generated.json genuinely omits both, and mirroring is then correct.
        assertThat(VanillaBuiltinDisplays.GENERATED.thirdpersonLefthand()).isNull();
        assertThat(VanillaBuiltinDisplays.GENERATED.firstpersonLefthand()).isNull();
    }

    @Test
    @DisplayName("a handheld item's off hand comes out identical to its main hand")
    void handheldOffHandMatchesMainHand() {
        // The visible symptom of dropping handheld's left-hand slots: the off
        // hand came out y=-90/z=-55 against the main hand's y=+90/z=+55, i.e.
        // turned 180 degrees about Y with the roll reversed - blade backwards.
        // Third person is the clean comparison because handheld's translation X
        // is 0, so the two blocks must agree byte for byte.
        AttachableGenerationConfig config = new AttachableGenerationConfig(
            AttachableGenerationConfig.MODE_FULL, false);
        String anim = BedrockAttachableWriter.buildArtifacts(
            "custom_cmd_sword",
            VanillaBuiltinDisplays.HANDHELD,
            null,
            "textures/items/custom_cmd_sword",
            16, 16, config, null)
            .get(BedrockAttachableWriter.animationEntryPath("custom_cmd_sword"));

        String main = jsonBlockAfter(anim, "thirdperson_main_hand");
        String off = jsonBlockAfter(anim, "thirdperson_off_hand");
        assertThat(main).isNotNull();
        assertThat(off).as("off-hand third-person animation").isEqualTo(main);
    }

    /**
     * Returns the balanced {@code { ... }} object that follows {@code key},
     * so two animation blocks can be compared as wholes instead of by
     * substring probes that cannot tell {@code 90.0} from {@code -90.0}.
     */
    private static String jsonBlockAfter(String json, String key) {
        int at = json.indexOf(key);
        if (at < 0) {
            return null;
        }
        int open = json.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return json.substring(open, i + 1);
            }
        }
        return null;
    }
}
