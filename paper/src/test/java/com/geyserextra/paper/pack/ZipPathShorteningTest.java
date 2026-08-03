package com.geyserextra.paper.pack;

import com.geyserextra.core.api.ArmorData;
import com.geyserextra.core.config.GeyserExtraConfig.ArmorGenerationConfig;
import com.geyserextra.core.config.GeyserExtraConfig.AttachableGenerationConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Geyser warns (and some Bedrock platforms fail) when a zip entry path
 * reaches 80 characters. {@code AutoBedrockPackBuilder.zipSafeFileBase}
 * shortens the file base name deterministically while the icon key /
 * attachable identifier stays untouched.
 */
@DisplayName("Zip path 80-char shortening")
class ZipPathShorteningTest {

    /** Real-world offender from the TrinityForge hints (71 chars). */
    private static final String LONG_KEY =
        "itemmodel_trinityforge_gui_connection_unlockable_endpoint_verti_bottom";

    @Nested
    @DisplayName("zipSafeFileBase")
    class ZipSafeFileBase {

        @Test
        @DisplayName("short keys pass through unchanged")
        void shortKeyUnchanged() {
            assertThat(AutoBedrockPackBuilder.zipSafeFileBase("sword"))
                .isEqualTo("sword");
        }

        @Test
        @DisplayName("a key exactly at the cap passes through unchanged")
        void boundaryKeyUnchanged() {
            String atCap = "a".repeat(AutoBedrockPackBuilder.MAX_ZIP_FILE_BASE_LENGTH);
            assertThat(AutoBedrockPackBuilder.zipSafeFileBase(atCap)).isEqualTo(atCap);
        }

        @Test
        @DisplayName("a key one over the cap is shortened to the cap with a hash suffix")
        void overCapShortened() {
            String overCap = "a".repeat(AutoBedrockPackBuilder.MAX_ZIP_FILE_BASE_LENGTH + 1);
            String base = AutoBedrockPackBuilder.zipSafeFileBase(overCap);
            assertThat(base).hasSize(AutoBedrockPackBuilder.MAX_ZIP_FILE_BASE_LENGTH);
            assertThat(base).matches(".*_[0-9a-f]{8}");
        }

        @Test
        @DisplayName("shortening is deterministic across calls")
        void deterministic() {
            assertThat(AutoBedrockPackBuilder.zipSafeFileBase(LONG_KEY))
                .isEqualTo(AutoBedrockPackBuilder.zipSafeFileBase(LONG_KEY));
        }

        @Test
        @DisplayName("keys differing only in the truncated-away head stay distinct")
        void distinctHeadsStayDistinct() {
            String tail = "_connection_unlockable_endpoint_verti_bottom";
            String a = "itemmodel_alpha_namespace_aaaaaaaaaa" + tail;
            String b = "itemmodel_bravo_namespace_bbbbbbbbbb" + tail;
            assertThat(AutoBedrockPackBuilder.zipSafeFileBase(a))
                .isNotEqualTo(AutoBedrockPackBuilder.zipSafeFileBase(b));
        }

        @Test
        @DisplayName("keeps the distinctive tail of the original key")
        void keepsTail() {
            String base = AutoBedrockPackBuilder.zipSafeFileBase(LONG_KEY);
            // 36 - 8 (hash) - 1 (separator) = 27 chars of original tail.
            assertThat(base).startsWith("nlockable_endpoint_verti_bottom"
                .substring("nlockable_endpoint_verti_bottom".length() - 27));
        }

        @Test
        @DisplayName("every derived zip path stays under Geyser's 80-char warning threshold")
        void allDerivedPathsUnderLimit() {
            String base = AutoBedrockPackBuilder.zipSafeFileBase(LONG_KEY);
            List<String> paths = List.of(
                "textures/items/" + base + ".png",
                "textures/items/" + base + "_gui.png",
                BedrockAttachableWriter.attachableEntryPath(base),
                BedrockAttachableWriter.geometryEntryPath(base),
                BedrockAttachableWriter.animationEntryPath(base),
                BedrockAttachableWriter.armorTextureEntryPath(base));
            for (String path : paths) {
                assertThat(path.length())
                    .as("zip path %s", path)
                    .isLessThan(80);
            }
        }
    }

    @Nested
    @DisplayName("BedrockAttachableWriter fileBase decoupling")
    class WriterFileBaseDecoupling {

        private static final AttachableGenerationConfig CONFIG =
            new AttachableGenerationConfig(
                AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        private static JavaModelDisplay displayWithHandTransform() {
            return new JavaModelDisplay(
                new JavaModelDisplay.Transform(
                    new float[]{55f, 0f, 90f},
                    new float[]{0f, 4f, -9f},
                    new float[]{1.7f, 1.7f, 1.7f}),
                null, null, null, null);
        }

        @Test
        @DisplayName("held-item artifacts: zip paths use fileBase, identifiers keep iconKey")
        void heldItemPathsUseFileBaseIdentifiersKeepIconKey() {
            String fileBase = AutoBedrockPackBuilder.zipSafeFileBase(LONG_KEY);
            Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
                LONG_KEY, fileBase, displayWithHandTransform(), null,
                "textures/items/" + fileBase, 16, 16, CONFIG, null);

            assertThat(artifacts).containsOnlyKeys(
                BedrockAttachableWriter.attachableEntryPath(fileBase),
                BedrockAttachableWriter.geometryEntryPath(fileBase),
                BedrockAttachableWriter.animationEntryPath(fileBase));
            for (String key : artifacts.keySet()) {
                assertThat(key.length()).as("zip path %s", key).isLessThan(80);
            }

            String attachable = artifacts.get(
                BedrockAttachableWriter.attachableEntryPath(fileBase));
            // The Bedrock identifier must keep matching the extension-side
            // registration (geyserextra:<iconKey>), never the shortened base.
            assertThat(attachable).contains("geyserextra:" + LONG_KEY);
            assertThat(attachable).contains("geometry.geyserextra." + LONG_KEY);
            // The texture reference must point at the shortened file path.
            assertThat(attachable).contains("textures/items/" + fileBase);
        }

        @Test
        @DisplayName("armor artifacts: zip path + texture ref use fileBase, identifier keeps iconKey")
        void armorPathsUseFileBaseIdentifierKeepsIconKey() {
            String fileBase = AutoBedrockPackBuilder.zipSafeFileBase(LONG_KEY);
            Map<String, String> artifacts = BedrockAttachableWriter.buildArmorArtifacts(
                LONG_KEY, fileBase,
                new ArmorData(ArmorData.SLOT_HEAD, "trinityforge:custom_helm"),
                new ArmorGenerationConfig(true));

            assertThat(artifacts).containsOnlyKeys(
                BedrockAttachableWriter.attachableEntryPath(fileBase));
            String attachable = artifacts.get(
                BedrockAttachableWriter.attachableEntryPath(fileBase));
            assertThat(attachable).contains("geyserextra:" + LONG_KEY);
            assertThat(attachable).contains("textures/entity/equipment/" + fileBase);
        }
    }
}
