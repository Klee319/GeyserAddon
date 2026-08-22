package com.geyserextra.extension;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shipped {@code invisible_glow_frames} pack against re-blanking a
 * texture that belongs to a <b>different</b> block.
 *
 * <p><b>The bug this exists to prevent (production, 2026-08-22):</b> the pack
 * shipped a fully transparent 16x16 {@code textures/blocks/itemframe_background.png}
 * plus a {@code terrain_texture.json} entry pointing at it. That texture is what
 * Bedrock draws inside the <em>ordinary</em> item frame — Bedrock's vanilla
 * {@code blocks.json} names it on the {@code frame} entry, not on
 * {@code glow_frame}. Every plain item frame on the server therefore lost its
 * backing texture and the client rendered the hole as solid black. Java players
 * saw nothing wrong, so the report arrived as "the inside of a normal item frame
 * went black" with no obvious link to a glow-frame pack.
 *
 * <p><b>Why removing it is safe:</b> what actually hides the glow frame is
 * {@code "blockshape": "invisible"} on the {@code glow_frame} entry of this
 * pack's own {@code blocks.json} — asserted below so a future edit cannot drop
 * the real mechanism and leave only the collateral damage behind.
 *
 * <p><b>Why the zip and not the source tree:</b> the pack reaches players as
 * {@code packs/invisible_glow_frames.zip}, assembled by the {@code
 * zipInvisibleGlowFrames} Gradle task from {@code resourcepack/invisible_glow_frames}
 * and baked into this jar's resources. Reading the classpath entry tests what
 * ships; reading the source directory would pass even if the packaging task
 * started pulling files from somewhere else.
 */
class InvisibleGlowFramesPackTest {

    private static final String PACK_RESOURCE = "packs/invisible_glow_frames.zip";

    /** Entry names inside the shipped pack, in zip order. */
    private static List<String> entryNames() throws Exception {
        List<String> names = new ArrayList<>();
        forEachEntry((name, body) -> names.add(name));
        return names;
    }

    private static String read(String entryName) throws Exception {
        String[] found = new String[1];
        forEachEntry((name, body) -> {
            if (name.equals(entryName)) {
                found[0] = new String(body, StandardCharsets.UTF_8);
            }
        });
        assertThat(found[0])
            .as("pack entry '%s' is missing; entries=%s", entryName, entryNames())
            .isNotNull();
        return found[0];
    }

    private interface EntryVisitor {
        void accept(String name, byte[] body) throws Exception;
    }

    private static void forEachEntry(EntryVisitor visitor) throws Exception {
        try (InputStream in = InvisibleGlowFramesPackTest.class.getClassLoader()
                .getResourceAsStream(PACK_RESOURCE)) {
            assertThat(in)
                .as("%s is not on the test classpath. It is produced by the"
                    + " zipInvisibleGlowFrames Gradle task via processResources —"
                    + " run the test through Gradle, not a bare IDE runner.", PACK_RESOURCE)
                .isNotNull();
            byte[] all = in.readAllBytes();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(all))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    visitor.accept(entry.getName().replace('\\', '/'), zip.readAllBytes());
                }
            }
        }
    }

    @Test
    void theNormalItemFrameTextureIsNotOverridden() throws Exception {
        JsonObject terrain = JsonParser.parseString(read("textures/terrain_texture.json"))
            .getAsJsonObject()
            .getAsJsonObject("texture_data");

        assertThat(terrain.keySet())
            .as("this pack may only redefine the glow frame's own texture."
                + " 'itemframe_background' belongs to the ordinary item frame and"
                + " blanking it renders every plain frame black for Bedrock players.")
            .containsExactly("glow_item_frame");
    }

    @Test
    void noBlankTextureIsShippedForTheNormalItemFrame() throws Exception {
        assertThat(entryNames())
            .as("a stray itemframe_background.png would be re-picked-up the moment"
                + " someone re-adds the terrain_texture.json entry")
            .noneMatch(name -> name.endsWith("itemframe_background.png"));
    }

    /**
     * A UTF-8 BOM in front of a pack JSON makes Bedrock reject the file, and the
     * pack then fails as a whole rather than in the one place that was edited.
     *
     * <p>This is not hypothetical: editing {@code terrain_texture.json} from
     * PowerShell put a BOM in it during the very change this test class was
     * written for. Gson skips a leading BOM in lenient mode, so every assertion
     * above still passed on the broken file — the byte-level check is the only
     * one that catches it.
     */
    @Test
    void noShippedJsonStartsWithAByteOrderMark() throws Exception {
        List<String> offenders = new ArrayList<>();
        forEachEntry((name, body) -> {
            if (!name.endsWith(".json")) {
                return;
            }
            if (body.length >= 3
                && (body[0] & 0xFF) == 0xEF
                && (body[1] & 0xFF) == 0xBB
                && (body[2] & 0xFF) == 0xBF) {
                offenders.add(name);
            }
        });

        assertThat(offenders)
            .as("Bedrock refuses a JSON that starts with a BOM. Write these files"
                + " as plain UTF-8 (PowerShell's Set-Content -Encoding UTF8 and"
                + " '>' both prepend one).")
            .isEmpty();
    }

    /**
     * Bedrock caches a resource pack by the version in its manifest, so an edit
     * that leaves the version alone never reaches a client that already joined —
     * the fix looks deployed on the server and dead in the game. Both version
     * arrays have to move together: Bedrock re-downloads on the header version,
     * but a module left behind produces a pack whose parts disagree.
     */
    @Test
    void theManifestHeaderAndModuleVersionsMatch() throws Exception {
        JsonObject manifest = JsonParser.parseString(read("manifest.json")).getAsJsonObject();

        String header = manifest.getAsJsonObject("header").get("version").toString();
        String module = manifest.getAsJsonArray("modules").get(0)
            .getAsJsonObject().get("version").toString();

        assertThat(module)
            .as("bump the module version with the header version, or Bedrock"
                + " serves a pack whose halves claim different releases")
            .isEqualTo(header);
    }

    @Test
    void theGlowFrameIsStillHiddenByBlockshapeNotByItsTexture() throws Exception {
        JsonObject blocks = JsonParser.parseString(read("blocks.json")).getAsJsonObject();

        assertThat(blocks.has("glow_frame"))
            .as("blocks.json must still describe glow_frame: %s", blocks.keySet())
            .isTrue();
        JsonObject glowFrame = blocks.getAsJsonObject("glow_frame");
        assertThat(glowFrame.get("blockshape").getAsString())
            .as("'invisible' is what actually hides the glow frame. If this is ever"
                + " dropped, the pack stops working and the temptation to blank"
                + " neighbouring textures instead comes straight back.")
            .isEqualTo("invisible");
        assertThat(glowFrame.get("textures").getAsString())
            .as("the glow frame must keep pointing at its own texture key")
            .isEqualTo("glow_item_frame");

        assertThat(blocks.keySet())
            .as("this pack must not describe the ordinary 'frame' block at all")
            .doesNotContain("frame");
    }
}
