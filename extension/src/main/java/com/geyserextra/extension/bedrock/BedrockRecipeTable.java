package com.geyserextra.extension.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The corrected recipe tables shipped by the backends, as read on the proxy.
 *
 * <h2>What problem this data solves</h2>
 * Bedrock computes crafting results client-side. Geyser's recipe translation turns a Java
 * ingredient into the <em>vanilla</em> Bedrock item — CustomModelData is not carried on the Java
 * {@code Ingredient}, so the information is simply absent at the source — while the
 * <em>result</em> is translated into the real custom Bedrock item. The client therefore holds
 * "vanilla ingredient -&gt; custom result" recipes that can never match the custom items actually
 * placed on the grid, which is why a Bedrock player sees no result at all for every
 * custom-ingredient recipe.
 *
 * <p>TrinityForge and ArsPaper write the missing half (material + CustomModelData per ingredient)
 * and the Paper plugin ships it here. This class only parses it; turning it into packets is
 * {@link BedrockRecipeInjector}'s job, so this half stays testable without Geyser.
 */
public record BedrockRecipeTable(List<Recipe> recipes) {

    /**
     * Format version this build understands.
     *
     * <p>Must match the writers ({@code TrinityForge.BedrockRecipeTable.FORMAT_VERSION},
     * {@code ArsPaper.BedrockRecipeExporter.FORMAT_VERSION}) and the Paper-side collector.
     * A file at any other version is skipped whole and reported: a partly-understood table
     * would inject recipes with the wrong ingredients, which is worse than injecting none.
     */
    public static final int SUPPORTED_FORMAT_VERSION = 1;

    /** Sub-folder of the extension data folder the Paper plugins write into. */
    public static final String DIRECTORY = "bedrock-recipes";

    public BedrockRecipeTable {
        recipes = List.copyOf(recipes);
    }

    public boolean isEmpty() {
        return recipes.isEmpty();
    }

    /** One candidate item for one grid slot. {@code cmd} null means the plain vanilla item. */
    public record ItemRef(String material, Integer cmd, int count) {

        /** Java item identifier, e.g. {@code minecraft:amethyst_shard}. */
        public String javaIdentifier() {
            return "minecraft:" + material.toLowerCase(Locale.ROOT);
        }

        public boolean isCustom() {
            return cmd != null;
        }
    }

    /**
     * One recipe. For {@code shaped}, {@code slots} holds {@code width * height} entries in
     * row-major order and an empty list means an empty square. For shapeless it is just the
     * ingredient list.
     */
    public record Recipe(String id, boolean shaped, int width, int height,
                         List<List<ItemRef>> slots, ItemRef result) {

        public Recipe {
            slots = List.copyOf(slots);
        }
    }

    /**
     * Reads and unions every {@code <dataFolder>/bedrock-recipes/*.json}.
     *
     * <p>One file per backend, so a union is expected. Duplicate ids keep the first occurrence:
     * two backends running the same config ship byte-identical recipes, and injecting them twice
     * would only waste packet space.
     *
     * <p>{@code warn} receives one line per unusable file. A read failure must never look like
     * "this backend has no custom recipes" — that is indistinguishable from the feature working
     * and finding nothing to fix.
     */
    public static BedrockRecipeTable readAll(Path dataFolder, Consumer<String> warn) {
        Path directory = dataFolder.resolve(DIRECTORY);
        if (!Files.isDirectory(directory)) {
            return new BedrockRecipeTable(List.of());
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory, "*.json")) {
            children.forEach(files::add);
        } catch (IOException e) {
            warn.accept("could not list " + directory + ": " + e.getMessage());
            return new BedrockRecipeTable(List.of());
        }
        files.sort((a, b) -> a.toString().compareTo(b.toString()));

        List<Recipe> recipes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path file : files) {
            try {
                for (Recipe recipe : parse(Files.readString(file, StandardCharsets.UTF_8), file.getFileName().toString(), warn)) {
                    if (seen.add(recipe.id())) {
                        recipes.add(recipe);
                    }
                }
            } catch (IOException e) {
                warn.accept(file.getFileName() + ": " + e.getMessage());
            }
        }
        return new BedrockRecipeTable(recipes);
    }

    /** Parses one shipped document. Returns an empty list (and warns) for anything unusable. */
    public static List<Recipe> parse(String json, String label, Consumer<String> warn) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                warn.accept(label + ": not a JSON object");
                return List.of();
            }
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            warn.accept(label + ": malformed JSON (" + e.getMessage() + ")");
            return List.of();
        }
        int version = root.has("version") ? root.get("version").getAsInt() : -1;
        if (version != SUPPORTED_FORMAT_VERSION) {
            warn.accept(label + ": format version " + version + ", expected " + SUPPORTED_FORMAT_VERSION
                + " — skipped whole rather than parsed partially");
            return List.of();
        }
        if (!root.has("recipes") || !root.get("recipes").isJsonArray()) {
            warn.accept(label + ": no recipes array");
            return List.of();
        }

        List<Recipe> recipes = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("recipes")) {
            Recipe recipe = parseRecipe(element, label, warn);
            if (recipe != null) {
                recipes.add(recipe);
            }
        }
        return recipes;
    }

    private static Recipe parseRecipe(JsonElement element, String label, Consumer<String> warn) {
        try {
            JsonObject object = element.getAsJsonObject();
            String id = object.get("id").getAsString();
            boolean shaped = "shaped".equals(object.get("type").getAsString());
            int width = shaped ? object.get("width").getAsInt() : 0;
            int height = shaped ? object.get("height").getAsInt() : 0;

            List<List<ItemRef>> slots = new ArrayList<>();
            for (JsonElement slot : object.getAsJsonArray("slots")) {
                if (slot.isJsonNull()) {
                    slots.add(List.of());
                    continue;
                }
                List<ItemRef> candidates = new ArrayList<>();
                for (JsonElement candidate : slot.getAsJsonArray()) {
                    candidates.add(parseItem(candidate.getAsJsonObject()));
                }
                slots.add(List.copyOf(candidates));
            }
            if (shaped && slots.size() != width * height) {
                warn.accept(label + ": " + id + " has " + slots.size()
                    + " slots for a " + width + "x" + height + " grid — skipped");
                return null;
            }
            if (!shaped && slots.isEmpty()) {
                warn.accept(label + ": " + id + " is shapeless with no ingredients — skipped");
                return null;
            }
            return new Recipe(id, shaped, width, height, slots, parseItem(object.getAsJsonObject("result")));
        } catch (RuntimeException e) {
            // One malformed entry must not cost the whole file: the rest of the table is still
            // correct, and dropping everything would silently un-fix unrelated recipes.
            warn.accept(label + ": skipped a malformed recipe (" + e.getClass().getSimpleName()
                + ": " + e.getMessage() + ")");
            return null;
        }
    }

    private static ItemRef parseItem(JsonObject object) {
        String material = object.get("material").getAsString();
        Integer cmd = object.has("cmd") ? object.get("cmd").getAsInt() : null;
        int count = object.has("count") ? object.get("count").getAsInt() : 1;
        return new ItemRef(material, cmd, count);
    }

    /**
     * Enumerates up to {@code limit} complete candidate combinations of {@code slots}.
     *
     * <p>A {@code list:} ingredient accepts several items, but a Bedrock recipe accepts exactly
     * one descriptor per square, so every combination has to become its own recipe. The count is
     * the product of the slot sizes and grows fast, hence the cap — the caller reports what it
     * dropped rather than silently shipping a partial set.
     *
     * <p>Each returned row has exactly {@code slots.size()} entries, with {@code null} for an
     * empty square, so callers can walk it row-major against the grid.
     */
    public static List<List<ItemRef>> expand(List<List<ItemRef>> slots, int limit) {
        long total = combinationCount(slots);
        int rows = (int) Math.min(total, Math.max(1, limit));
        List<List<ItemRef>> combinations = new ArrayList<>(rows);
        for (int index = 0; index < rows; index++) {
            List<ItemRef> row = new ArrayList<>(slots.size());
            int remaining = index;
            for (List<ItemRef> slot : slots) {
                if (slot.isEmpty()) {
                    row.add(null);
                    continue;
                }
                // Mixed-radix decomposition: each slot is a digit whose base is its own size.
                row.add(slot.get(remaining % slot.size()));
                remaining /= slot.size();
            }
            // Not List.copyOf: an empty square is a null entry and List.copyOf rejects nulls.
            combinations.add(java.util.Collections.unmodifiableList(row));
        }
        return List.copyOf(combinations);
    }

    /**
     * How many combinations {@code slots} would produce, saturating at {@link #COMBINATION_CEILING}
     * so a pathological table cannot overflow the count itself.
     */
    public static long combinationCount(List<List<ItemRef>> slots) {
        long total = 1;
        for (List<ItemRef> slot : slots) {
            if (slot.isEmpty()) {
                continue;
            }
            total *= slot.size();
            if (total >= COMBINATION_CEILING) {
                return COMBINATION_CEILING;
            }
        }
        return total;
    }

    /** Saturation point for {@link #combinationCount(List)}. */
    public static final long COMBINATION_CEILING = 1_000_000L;
}
