package com.geyserextra.paper.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects every {@code plugins/<Plugin>/bedrock-recipes.json} written by the backend plugins
 * and merges them into the shared Geyser extension folder.
 *
 * <h2>Why this exists</h2>
 * Bedrock computes crafting results client-side. Geyser's recipe translation turns a Java
 * ingredient into the <em>vanilla</em> Bedrock item (CustomModelData is not carried on the Java
 * {@code Ingredient}, so the information is absent at the source) while the <em>result</em> is
 * translated into the real custom Bedrock item. The client therefore holds recipes that read
 * "vanilla ingredient -&gt; custom result" and can never match the custom items actually placed
 * on the grid.
 *
 * <p>TrinityForge and ArsPaper write out the missing half — which custom item each ingredient
 * really is, as material + CustomModelData. This class ships those tables to the proxy, because
 * <b>Geyser runs on the Velocity proxy, not on these backends</b>: the extension cannot read a
 * backend's {@code plugins/} folder itself.
 *
 * <h2>One file per backend</h2>
 * All backends share one extension folder, so they must not write to the same name — that is the
 * same collision that forces {@code skinFixOnlyMode} for pack generation. Each backend writes
 * {@code <extension>/bedrock-recipes/<backend>.json} and the extension unions them.
 *
 * <p>A union can hand a Bedrock player a recipe that only exists on another backend. That is not
 * a regression: the client shows a result, the server refuses it — which is exactly today's
 * behaviour for every custom recipe. It never makes a working recipe wrong.
 */
public final class BedrockRecipeTableCollector {

    /** Name each backend plugin writes inside its own data folder. */
    public static final String SOURCE_FILE_NAME = "bedrock-recipes.json";

    /** Sub-folder of the extension data folder that receives the merged tables. */
    public static final String OUTPUT_DIR = "bedrock-recipes";

    /**
     * Format version this collector understands.
     *
     * <p>Must match {@code TrinityForge}'s {@code BedrockRecipeTable.FORMAT_VERSION} and
     * {@code ArsPaper}'s {@code BedrockRecipeExporter.FORMAT_VERSION}. A file with any other
     * version is <b>rejected loudly</b> rather than parsed optimistically — a half-understood
     * table would inject wrong ingredients, which is worse than injecting nothing.
     */
    public static final int SUPPORTED_FORMAT_VERSION = 1;

    private BedrockRecipeTableCollector() {
    }

    /** One accepted input table. */
    public record Source(String plugin, String source, int recipes) {
    }

    /** Outcome of one collection pass. {@code rejected} holds a human-readable reason per file. */
    public record Result(List<Source> accepted, List<String> rejected, int recipes) {

        public Result {
            accepted = List.copyOf(accepted);
            rejected = List.copyOf(rejected);
        }

        public boolean isEmpty() {
            return recipes == 0;
        }

        public String describe() {
            StringBuilder text = new StringBuilder();
            text.append(recipes).append(" recipes from ").append(accepted.size()).append(" plugin(s)");
            for (Source source : accepted) {
                text.append(" [").append(source.plugin()).append('=').append(source.recipes()).append(']');
            }
            if (!rejected.isEmpty()) {
                text.append("; rejected: ").append(String.join(", ", rejected));
            }
            return text.toString();
        }
    }

    /**
     * Merges parsed tables into the single document the extension reads.
     *
     * <p>Pure: no filesystem, no Bukkit. The dedup rule is <b>first table wins per recipe id</b>,
     * which keeps the output stable when two backends ship the same recipe.
     */
    public static JsonObject merge(List<JsonObject> tables, String backend) {
        JsonObject root = new JsonObject();
        root.addProperty("version", SUPPORTED_FORMAT_VERSION);
        root.addProperty("backend", backend);
        JsonArray sources = new JsonArray();
        JsonArray recipes = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonObject table : tables) {
            JsonArray own = table.getAsJsonArray("recipes");
            int kept = 0;
            for (JsonElement element : own) {
                JsonObject recipe = element.getAsJsonObject();
                // An id-less recipe cannot be deduped or reported on; dropping it is safer than
                // injecting an anonymous one twice.
                if (!recipe.has("id") || !seen.add(recipe.get("id").getAsString())) {
                    continue;
                }
                recipes.add(recipe);
                kept++;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("source", table.has("source") ? table.get("source").getAsString() : "unknown");
            entry.addProperty("recipes", kept);
            sources.add(entry);
        }
        root.add("sources", sources);
        root.add("recipes", recipes);
        return root;
    }

    /**
     * Reads one source file. Returns {@code null} and appends a reason to {@code rejected}
     * when the file is unusable — a caller must never treat that as "no recipes".
     */
    static JsonObject read(Path file, List<String> rejected) {
        String label = file.getParent() == null ? file.toString() : file.getParent().getFileName().toString();
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                rejected.add(label + " (not a JSON object)");
                return null;
            }
            JsonObject table = parsed.getAsJsonObject();
            int version = table.has("version") ? table.get("version").getAsInt() : -1;
            if (version != SUPPORTED_FORMAT_VERSION) {
                rejected.add(label + " (format version " + version
                    + ", expected " + SUPPORTED_FORMAT_VERSION + ")");
                return null;
            }
            if (!table.has("recipes") || !table.get("recipes").isJsonArray()) {
                rejected.add(label + " (no recipes array)");
                return null;
            }
            return table;
        } catch (IOException | JsonSyntaxException | IllegalStateException | NumberFormatException e) {
            rejected.add(label + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            return null;
        }
    }

    /** Every {@code <plugins>/<Plugin>/bedrock-recipes.json} that currently exists, in name order. */
    public static List<Path> findSourceFiles(Path pluginsFolder) throws IOException {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(pluginsFolder)) {
            return found;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(pluginsFolder)) {
            for (Path child : children) {
                Path candidate = child.resolve(SOURCE_FILE_NAME);
                if (Files.isDirectory(child) && Files.isRegularFile(candidate)) {
                    found.add(candidate);
                }
            }
        }
        found.sort((a, b) -> a.toString().compareTo(b.toString()));
        return found;
    }

    /**
     * Collects, merges and writes {@code <extension>/bedrock-recipes/<backend>.json}.
     *
     * <p>Writes through a temp file in the same folder: the reader is a different process
     * (Geyser on the proxy) and would otherwise be able to read a half-written document.
     *
     * <p>When no usable source is found the previously written file is <b>removed</b> rather than
     * left behind. A stale table would keep injecting recipes for plugins that are no longer
     * installed, and nothing on the proxy could tell that it had gone stale.
     */
    public static Result collect(Path pluginsFolder, Path extensionDataFolder, String backend)
            throws IOException {
        List<String> rejected = new ArrayList<>();
        List<JsonObject> tables = new ArrayList<>();
        List<Source> accepted = new ArrayList<>();
        for (Path file : findSourceFiles(pluginsFolder)) {
            JsonObject table = read(file, rejected);
            if (table == null) {
                continue;
            }
            String plugin = file.getParent().getFileName().toString();
            tables.add(table);
            accepted.add(new Source(plugin,
                table.has("source") ? table.get("source").getAsString() : "unknown",
                table.getAsJsonArray("recipes").size()));
        }

        Path outputDir = extensionDataFolder.resolve(OUTPUT_DIR);
        Path target = outputDir.resolve(backend + ".json");
        if (tables.isEmpty()) {
            Files.deleteIfExists(target);
            return new Result(accepted, rejected, 0);
        }

        JsonObject merged = merge(tables, backend);
        Files.createDirectories(outputDir);
        Path temp = outputDir.resolve(backend + ".json.tmp");
        Files.writeString(temp, merged.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new Result(accepted, rejected, merged.getAsJsonArray("recipes").size());
    }

    /**
     * A file-system safe identity for this backend, derived from the server's working directory.
     *
     * <p>Derived rather than configured on purpose: every backend already has a distinct folder,
     * and one more config key that must be set differently per backend is one more way to have
     * two backends silently overwrite each other's table.
     */
    public static String backendId(Path serverDirectory, int port) {
        Path name = serverDirectory.toAbsolutePath().normalize().getFileName();
        String raw = name == null ? "" : name.toString();
        String sanitized = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "backend-" + port : sanitized;
    }
}
