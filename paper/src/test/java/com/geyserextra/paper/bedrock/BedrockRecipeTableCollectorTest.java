package com.geyserextra.paper.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixes what the collector ships to the proxy.
 *
 * <p>The thing that must not break is that a wrong or unreadable table is dropped <b>whole</b>.
 * A half-understood table injects recipes with the wrong ingredients, which is worse than
 * injecting nothing: today a Bedrock player merely cannot craft, whereas a wrong ingredient list
 * lets a different recipe succeed. None of that is visible from the server side, so it has to be
 * pinned here.
 */
class BedrockRecipeTableCollectorTest {

    private static String table(int version, String source, String... recipeIds) {
        StringBuilder json = new StringBuilder();
        json.append("{\"version\":").append(version)
            .append(",\"source\":\"").append(source).append("\",\"recipes\":[");
        for (int i = 0; i < recipeIds.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"").append(recipeIds[i])
                .append("\",\"type\":\"shapeless\",\"slots\":[[{\"material\":\"STONE\",\"cmd\":1}]],")
                .append("\"result\":{\"material\":\"STONE\",\"cmd\":2}}");
        }
        return json.append("],\"skipped\":[]}").toString();
    }

    private static void write(Path pluginsFolder, String plugin, String content) throws IOException {
        Path folder = pluginsFolder.resolve(plugin);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(BedrockRecipeTableCollector.SOURCE_FILE_NAME),
            content, StandardCharsets.UTF_8);
    }

    private static JsonObject readOutput(Path extension, String backend) throws IOException {
        Path file = extension.resolve(BedrockRecipeTableCollector.OUTPUT_DIR).resolve(backend + ".json");
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void mergesEveryPluginTableIntoOneFile(@TempDir Path root) throws IOException {
        Path plugins = root.resolve("plugins");
        Path extension = root.resolve("extension");
        write(plugins, "TrinityForge", table(1, "TrinityForge", "trinityforge:a", "trinityforge:b"));
        write(plugins, "ArsPaper", table(1, "ArsPaper", "arspaper:c"));
        // A plugin folder with no table at all must simply be ignored.
        Files.createDirectories(plugins.resolve("SomeOtherPlugin"));

        var result = BedrockRecipeTableCollector.collect(plugins, extension, "main");

        assertThat(result.recipes()).isEqualTo(3);
        assertThat(result.rejected()).isEmpty();
        assertThat(result.accepted()).extracting(BedrockRecipeTableCollector.Source::plugin)
            .containsExactly("ArsPaper", "TrinityForge");

        JsonObject output = readOutput(extension, "main");
        assertThat(output.get("version").getAsInt())
            .isEqualTo(BedrockRecipeTableCollector.OUTPUT_FORMAT_VERSION);
        assertThat(output.get("backend").getAsString()).isEqualTo("main");
        assertThat(output.getAsJsonArray("recipes")).hasSize(3);
    }

    /**
     * A table written in a format this build does not know is dropped whole and reported.
     * Parsing it optimistically is the failure mode this test exists to prevent.
     */
    @Test
    void rejectsAnUnknownFormatVersionInsteadOfGuessing(@TempDir Path root) throws IOException {
        Path plugins = root.resolve("plugins");
        Path extension = root.resolve("extension");
        write(plugins, "TrinityForge", table(99, "TrinityForge", "trinityforge:a"));
        write(plugins, "ArsPaper", table(1, "ArsPaper", "arspaper:c"));

        var result = BedrockRecipeTableCollector.collect(plugins, extension, "main");

        assertThat(result.recipes()).isEqualTo(1);
        assertThat(result.rejected()).singleElement().asString()
            .contains("TrinityForge").contains("format version 99");
        assertThat(readOutput(extension, "main").getAsJsonArray("recipes")).hasSize(1);
    }

    /**
     * A v1 writer and a v2 writer must merge together. The backends live in other repositories
     * deployed by other scripts, so "one side is a release behind" is the normal state during a
     * rollout — rejecting the older half would silently un-fix its recipes.
     */
    @Test
    void mergesBothSupportedFormatVersions(@TempDir Path root) throws IOException {
        Path plugins = root.resolve("plugins");
        Path extension = root.resolve("extension");
        write(plugins, "TrinityForge", table(2, "TrinityForge", "trinityforge:a"));
        write(plugins, "ArsPaper", table(1, "ArsPaper", "arspaper:c"));

        var result = BedrockRecipeTableCollector.collect(plugins, extension, "main");

        assertThat(result.rejected()).isEmpty();
        assertThat(result.recipes()).isEqualTo(2);
        assertThat(readOutput(extension, "main").get("version").getAsInt())
            .isEqualTo(BedrockRecipeTableCollector.OUTPUT_FORMAT_VERSION);
    }

    @Test
    void rejectsMalformedJsonWithoutLosingTheOtherTables(@TempDir Path root) throws IOException {
        Path plugins = root.resolve("plugins");
        Path extension = root.resolve("extension");
        write(plugins, "TrinityForge", "{\"version\":1,\"recipes\":[");
        write(plugins, "ArsPaper", table(1, "ArsPaper", "arspaper:c"));

        var result = BedrockRecipeTableCollector.collect(plugins, extension, "main");

        assertThat(result.recipes()).isEqualTo(1);
        assertThat(result.rejected()).singleElement().asString().contains("TrinityForge");
    }

    /** Two backends can ship the same recipe; the client must not receive it twice. */
    @Test
    void dedupesRecipesByIdAcrossTables(@TempDir Path root) throws IOException {
        Path plugins = root.resolve("plugins");
        Path extension = root.resolve("extension");
        write(plugins, "TrinityForge", table(1, "TrinityForge", "shared:x", "trinityforge:a"));
        write(plugins, "ArsPaper", table(1, "ArsPaper", "shared:x"));

        var result = BedrockRecipeTableCollector.collect(plugins, extension, "main");

        assertThat(result.recipes()).isEqualTo(2);
        // ArsPaper sorts first, so it is the one that keeps shared:x.
        assertThat(result.accepted()).extracting(BedrockRecipeTableCollector.Source::plugin)
            .containsExactly("ArsPaper", "TrinityForge");
        JsonObject output = readOutput(extension, "main");
        List<String> ids = new ArrayList<>();
        output.getAsJsonArray("recipes").forEach(e -> ids.add(e.getAsJsonObject().get("id").getAsString()));
        assertThat(ids).containsExactly("shared:x", "trinityforge:a");
    }

    /**
     * When the last source disappears the shipped file must go too. Leaving it behind keeps the
     * proxy injecting recipes for a plugin that is no longer installed, and nothing on the proxy
     * side can tell that the table went stale.
     */
    @Test
    void removesTheShippedFileWhenNoSourceRemains(@TempDir Path root) throws IOException {
        Path plugins = root.resolve("plugins");
        Path extension = root.resolve("extension");
        write(plugins, "TrinityForge", table(1, "TrinityForge", "trinityforge:a"));
        BedrockRecipeTableCollector.collect(plugins, extension, "main");
        Path shipped = extension.resolve(BedrockRecipeTableCollector.OUTPUT_DIR).resolve("main.json");
        assertThat(shipped).exists();

        Files.delete(plugins.resolve("TrinityForge").resolve(BedrockRecipeTableCollector.SOURCE_FILE_NAME));
        var result = BedrockRecipeTableCollector.collect(plugins, extension, "main");

        assertThat(result.isEmpty()).isTrue();
        assertThat(shipped).doesNotExist();
    }

    /** Backends share one extension folder, so their output names must differ. */
    @Test
    void backendIdComesFromTheServerDirectoryAndIsFileSystemSafe(@TempDir Path root) throws IOException {
        Path weird = root.resolve("Velocity for TF!");
        Files.createDirectories(weird);

        assertThat(BedrockRecipeTableCollector.backendId(weird, 25566)).isEqualTo("Velocity_for_TF_");
        assertThat(BedrockRecipeTableCollector.backendId(root.resolve("resource"), 25567))
            .isEqualTo("resource");
    }

    @Test
    void aMissingPluginsFolderIsNotAnError(@TempDir Path root) throws IOException {
        var result = BedrockRecipeTableCollector.collect(
            root.resolve("does-not-exist"), root.resolve("extension"), "main");

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.rejected()).isEmpty();
    }
}
