package com.geyserextra.paper.pack;

import com.geyserextra.core.config.GeyserExtraConfig.AttachableGenerationConfig;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real conversion pipeline over the operator's actual Java pack and
 * asserts the properties that in-game screenshots were previously the only way
 * to check. Synthetic fixtures cannot catch a regression that only shows up on
 * Blockbench-authored weapon models (49 of them in the TrinityForge pack, all
 * with per-face UV rotations and large display translations).
 *
 * <p>The pack lives outside this repository, so the test resolves it relative
 * to the sibling {@code trinityforge} checkout and <b>skips</b> when that is not
 * present. Point {@code -Dgeyserextra.javaPack=<dir>} at any unzipped Java pack
 * to run it elsewhere.</p>
 *
 * <p>Converted artifacts are written to {@code build/pack-conversion-dump/} so
 * the output can be diffed against a Rainbow-generated pack for the same items
 * without needing a running server.</p>
 */
@DisplayName("TrinityForge pack conversion (real pack)")
class TrinityForgePackConversionIT {

    private static final String PACK_PROPERTY = "geyserextra.javaPack";
    private static final Path DEFAULT_PACK = Path.of(
        "..", "..", "trinityforge", "resourcepack", "trinityforge-items");

    private static Path resolvePack() {
        String override = System.getProperty(PACK_PROPERTY);
        Path pack = override != null ? Path.of(override) : DEFAULT_PACK;
        Assumptions.assumeTrue(Files.isDirectory(pack.resolve("assets")),
            "Java pack not available at " + pack.toAbsolutePath()
                + " (set -D" + PACK_PROPERTY + " to run this test)");
        return pack;
    }

    @Test
    @DisplayName("every 3D weapon model converts to cubes, and the dump is written for Rainbow diffing")
    void convertsRealPackAndDumpsArtifacts() throws IOException {
        Path pack = resolvePack();
        Logger logger = Logger.getLogger(TrinityForgePackConversionIT.class.getName());
        JavaPackReader reader = new JavaPackReader(pack, "AUTO", logger, false);
        AttachableGenerationConfig config = new AttachableGenerationConfig(
            AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        Path dump = Path.of("build", "pack-conversion-dump");
        Files.createDirectories(dump);

        List<String> threeD = new ArrayList<>();
        List<String> flat = new ArrayList<>();
        List<String> rotatedUv = new ArrayList<>();
        List<String> report = new ArrayList<>();

        for (Map.Entry<JavaPackReader.CmdKey, JavaPackReader.JavaModelDefinition> entry
                : readDefinitions(reader).entrySet()) {
            String key = sanitize(entry.getKey().toString());
            JavaPackReader.JavaModelDefinition def = entry.getValue();
            int[] size = pngSize(def.textureFile());
            Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
                key, def.display(), def.geometry(), "textures/items/" + key,
                size[0], size[1], config, null);
            if (artifacts.isEmpty()) {
                report.add(key + "\tSKIPPED (no usable hand transform)");
                continue;
            }
            String geo = artifacts.get(BedrockAttachableWriter.geometryEntryPath(key));
            boolean is3d = geo.contains("\"cubes\"");
            (is3d ? threeD : flat).add(key);
            if (geo.contains("\"uv_rotation\"")) {
                rotatedUv.add(key);
            }
            report.add(key + "\t" + (is3d ? "3D" : "flat")
                + "\tuv_rotation=" + geo.contains("\"uv_rotation\""));

            for (Map.Entry<String, String> artifact : artifacts.entrySet()) {
                Path out = dump.resolve(artifact.getKey().replace('/', '_'));
                Files.writeString(out, artifact.getValue(), StandardCharsets.UTF_8);
            }
        }

        Files.write(dump.resolve("_summary.tsv"), report, StandardCharsets.UTF_8);

        // The pack ships 49 Blockbench weapon models; every one of them must
        // reach the 3D path rather than silently degrading to a flat icon.
        assertThat(threeD).as("models converted to 3D cubes").isNotEmpty();
        // A third of the faces on those models carry a 90/270 UV rotation,
        // which used to be dropped outright.
        assertThat(rotatedUv).as("models that needed uv_rotation").isNotEmpty();
        assertThat(rotatedUv).as("every uv_rotation model is a 3D one")
            .allSatisfy(k -> assertThat(threeD).contains(k));
    }

    @Test
    @DisplayName("first-person offsets stay bounded once the root scale is divided out")
    void firstPersonOffsetsStayBounded() {
        Path pack = resolvePack();
        Logger logger = Logger.getLogger(TrinityForgePackConversionIT.class.getName());
        JavaPackReader reader = new JavaPackReader(pack, "AUTO", logger, false);
        AttachableGenerationConfig config = new AttachableGenerationConfig(
            AttachableGenerationConfig.MODE_OFFSETS_ONLY, false);

        for (Map.Entry<JavaPackReader.CmdKey, JavaPackReader.JavaModelDefinition> entry
                : readDefinitions(reader).entrySet()) {
            JavaPackReader.JavaModelDefinition def = entry.getValue();
            if (def.display() == null || def.display().firstpersonRighthand() == null) {
                continue;
            }
            String key = sanitize(entry.getKey().toString());
            int[] size = pngSize(def.textureFile());
            Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
                key, def.display(), def.geometry(), "textures/items/" + key,
                size[0], size[1], config, null);
            if (artifacts.isEmpty()) {
                continue;
            }
            String anim = artifacts.get(BedrockAttachableWriter.animationEntryPath(key));
            float[] pos = firstPersonPosition(anim);
            // Java's own first-person translations on this pack top out at
            // ~9 px on a single axis (greataxe [0,4,-9], long spear
            // [-5.25,-7.25,-1]). Anything beyond 16 px would put the item a
            // full block off the hand, which is what the previous 1.5x/2.0x
            // amplification produced before the translation was zeroed.
            for (float v : pos) {
                assertThat(Math.abs(v)).as("first-person offset axis for " + key)
                    .isLessThanOrEqualTo(16f);
            }
            // ...and it must not be zeroed either: the item-specific offset is
            // the whole reason different weapons sit differently in the hand.
            if (hasNonZeroTranslation(def.display().firstpersonRighthand().translation())) {
                assertThat(Math.abs(pos[0]) + Math.abs(pos[1]) + Math.abs(pos[2]))
                    .as("first-person offset preserved for " + key)
                    .isGreaterThan(0f);
            }
        }
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private static Map<JavaPackReader.CmdKey, JavaPackReader.JavaModelDefinition> readDefinitions(
        JavaPackReader reader
    ) {
        Map<JavaPackReader.CmdKey, JavaPackReader.JavaModelDefinition> defs = reader.scan();
        Assumptions.assumeFalse(defs.isEmpty(), "pack produced no model definitions");
        return defs;
    }

    /**
     * Reads a PNG's pixel dimensions from its IHDR chunk so the dump uses the
     * same texture size the production builder probes. Falls back to 16x16,
     * matching the builder's own defensive default.
     */
    private static int[] pngSize(Path png) {
        if (png == null || !Files.isRegularFile(png)) {
            return new int[]{16, 16};
        }
        try {
            byte[] header = new byte[24];
            try (var in = Files.newInputStream(png)) {
                if (in.readNBytes(header, 0, 24) < 24) {
                    return new int[]{16, 16};
                }
            }
            return new int[]{readBigEndianInt(header, 16), readBigEndianInt(header, 20)};
        } catch (IOException ex) {
            return new int[]{16, 16};
        }
    }

    private static int readBigEndianInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
            | ((bytes[offset + 1] & 0xFF) << 16)
            | ((bytes[offset + 2] & 0xFF) << 8)
            | (bytes[offset + 3] & 0xFF);
    }

    private static boolean hasNonZeroTranslation(float[] translation) {
        return translation[0] != 0f || translation[1] != 0f || translation[2] != 0f;
    }

    private static String sanitize(String raw) {
        return raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    /** Reads {@code geyserextra_x.position} out of the first-person main-hand animation. */
    private static float[] firstPersonPosition(String animJson) {
        String compact = animJson.replaceAll("\\s+", "");
        int slot = compact.indexOf(".firstperson_main_hand\"");
        assertThat(slot).as("first-person main hand animation present").isGreaterThan(0);
        int posAt = compact.indexOf("\"position\":[", slot);
        assertThat(posAt).as("position channel present").isGreaterThan(0);
        int start = posAt + "\"position\":[".length();
        int end = compact.indexOf(']', start);
        String[] parts = compact.substring(start, end).split(",");
        return new float[]{
            Float.parseFloat(parts[0]),
            Float.parseFloat(parts[1]),
            Float.parseFloat(parts[2])
        };
    }
}
