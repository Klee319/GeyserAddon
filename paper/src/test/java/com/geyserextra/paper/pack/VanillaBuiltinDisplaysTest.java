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

}
