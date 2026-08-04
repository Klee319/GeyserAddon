package com.geyserextra.paper.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code gui_light} resolution through the model parent chain.
 *
 * <p>The flag decides which of Java's two inventory diffuse light rigs
 * {@link ItemIconRenderer} uses, and the two are far apart — a camera-facing
 * surface is {@code 1.0} under {@code "front"} and {@code 0.513} under
 * {@code "side"} — so resolving it wrongly darkens a whole icon rather than
 * shifting a highlight. It is also almost never declared on the model that
 * carries the elements: in the reference pack the front-lit models inherit it
 * from {@code item/generated}, a parent the pack does not even ship.
 */
class JavaPackReaderGuiLightTest {

    @TempDir
    Path tempDir;

    private static final String CUBE = """
        "elements":[{"from":[0,0,0],"to":[16,16,16],
        "faces":{"south":{"uv":[0,0,16,16],"texture":"#layer0"}}}]""";

    private JavaPackReader reader() {
        return new JavaPackReader(tempDir, "AUTO", Logger.getAnonymousLogger(), false);
    }

    private void model(String ref, String body) throws Exception {
        Path file = tempDir.resolve("assets/minecraft/models/" + ref + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{" + body + "}");
    }

    @Test
    @DisplayName("a model that declares gui_light front itself resolves to the flat rig")
    void ownDeclarationWins() throws Exception {
        model("item/book", "\"gui_light\":\"front\"," + CUBE);

        assertThat(reader().resolveElementsFromModel("item/book").guiLightFront())
            .isTrue();
    }

    @Test
    @DisplayName("gui_light is inherited from an ancestor that ships in the pack")
    void inheritedFromShippedParent() throws Exception {
        model("item/base", "\"gui_light\":\"front\"");
        model("item/book", "\"parent\":\"item/base\"," + CUBE);

        assertThat(reader().resolveElementsFromModel("item/book").guiLightFront())
            .isTrue();
    }

    @Test
    @DisplayName("the nearest declaration wins, so an explicit side overrides a front ancestor")
    void nearestDeclarationWins() throws Exception {
        model("item/base", "\"gui_light\":\"front\"");
        model("item/book", "\"parent\":\"item/base\",\"gui_light\":\"side\"," + CUBE);

        assertThat(reader().resolveElementsFromModel("item/book").guiLightFront())
            .isFalse();
    }

    @Test
    @DisplayName("a chain ending at the unshipped vanilla item/generated still resolves to front")
    void inheritedFromUnshippedVanillaParent() throws Exception {
        // The common shape by a wide margin: a Blockbench export parents on
        // item/generated, which vanilla declares "front" but which no resource
        // pack ships. Stopping the walk at the missing file and defaulting to
        // "side" would mis-light most of the pack's 3D models.
        model("item/book", "\"parent\":\"item/generated\"," + CUBE);

        assertThat(reader().resolveElementsFromModel("item/book").guiLightFront())
            .isTrue();
    }

    @Test
    @DisplayName("gui_light declared above the elements is still found")
    void declarationAboveTheElementsIsStillFound() throws Exception {
        // The elements walk stops at the first file carrying geometry, but
        // gui_light may sit further up. These are two independent resolutions
        // of the same chain, not one walk that ends early.
        model("item/base", "\"gui_light\":\"front\"");
        model("item/mid", "\"parent\":\"item/base\"");
        model("item/book", "\"parent\":\"item/mid\"," + CUBE);

        assertThat(reader().resolveElementsFromModel("item/book").guiLightFront())
            .isTrue();
    }

    @Test
    @DisplayName("a chain with no gui_light anywhere falls back to the format default, side")
    void formatDefaultIsSide() throws Exception {
        // block/cube is not one of the vanilla item builtins, so nothing in
        // this chain implies "front" and the model format's own default holds.
        model("item/book", "\"parent\":\"block/cube\"," + CUBE);

        assertThat(reader().resolveElementsFromModel("item/book").guiLightFront())
            .isFalse();
    }
}
