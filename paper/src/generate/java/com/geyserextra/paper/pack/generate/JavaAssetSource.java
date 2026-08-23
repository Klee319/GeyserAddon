package com.geyserextra.paper.pack.generate;

import com.geyserextra.core.util.JavaBlockModel;
import com.geyserextra.core.util.JavaBlockModel.Direction;
import com.geyserextra.core.util.JavaBlockModel.Element;
import com.geyserextra.core.util.JavaBlockModel.Face;
import com.geyserextra.core.util.JavaBlockModel.Rotation;
import com.geyserextra.core.util.JavaBlockModel.Transform;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads Minecraft's own Java assets and flattens one block's item model into
 * {@link JavaBlockModel}.
 *
 * <p>Assets come from a mirror of the extracted client jar, cached on disk: the first build
 * downloads, later builds reuse. Nothing here runs at runtime — this is a build-time source for
 * {@link BedrockBlockIconGenerator}.</p>
 *
 * <p>Resolution follows Minecraft's own order: {@code items/<id>.json} names the model (possibly
 * behind a select/condition/range dispatch), that model's {@code parent} chain supplies the
 * geometry and the {@code display.gui} transform, and {@code #name} texture variables are resolved
 * against the merged texture map.</p>
 *
 * <p>A model with no {@code elements} anywhere in its chain is not an error: Minecraft draws
 * decorated pots, conduits, chests, beds, banners and heads from code, and those models carry only
 * a particle texture. The caller falls back to the cube approximation for them.</p>
 */
final class JavaAssetSource {

    private final String baseUrl;
    private final Path cacheDir;
    private final Map<String, JsonObject> jsonCache = new HashMap<>();
    private final Map<String, BufferedImage> textureCache = new HashMap<>();

    JavaAssetSource(String baseUrl, Path cacheDir) throws IOException {
        this.baseUrl = baseUrl;
        this.cacheDir = cacheDir;
        Files.createDirectories(cacheDir);
    }

    /**
     * @param blockId bare Java block id, e.g. {@code lectern}
     * @return the flattened model, or {@code null} when it cannot be resolved or has no geometry
     */
    JavaBlockModel modelFor(String blockId) throws IOException {
        String modelName = itemModelName(blockId);
        if (modelName == null) {
            // Pre-1.21.4 layout, or a block with no item definition: fall back to the model paths
            // Minecraft itself would have used.
            modelName = json("models/item/" + blockId + ".json") != null
                ? "item/" + blockId
                : "block/" + blockId;
        }
        List<JsonObject> chain = resolveChain(modelName);
        if (chain.isEmpty()) {
            return null;
        }
        JsonArray elements = firstArray(chain, "elements");
        if (elements == null || elements.isEmpty()) {
            return null;
        }
        Map<String, String> textures = mergedTextures(chain);
        Transform gui = guiTransform(chain);
        boolean shaded = !"front".equals(firstString(chain, "gui_light"));

        List<Element> out = new ArrayList<>();
        for (JsonElement raw : elements) {
            Element element = readElement(raw.getAsJsonObject(), textures);
            if (element != null && !element.faces().isEmpty()) {
                out.add(element);
            }
        }
        return out.isEmpty() ? null : new JavaBlockModel(out, gui, shaded);
    }

    // ------------------------------------------------------------------
    // Model resolution
    // ------------------------------------------------------------------

    /** The model an item definition points at, or {@code null} for code-rendered items. */
    private String itemModelName(String blockId) throws IOException {
        JsonObject item = json("items/" + blockId + ".json");
        if (item == null) {
            return null;
        }
        JsonElement model = item.get("model");
        return model != null && model.isJsonObject() ? modelNameOf(model.getAsJsonObject()) : null;
    }

    /**
     * Digs a model name out of an item definition node.
     *
     * <p>The 1.21.4+ item format wraps the model in dispatchers — {@code select} by component,
     * {@code condition}, {@code range_dispatch} by damage. An inventory icon only needs one, so the
     * declared fallback is taken, then the first case. {@code special} means Minecraft renders it
     * from code and there is no model to take.</p>
     */
    private String modelNameOf(JsonObject node) {
        String type = bare(asString(node.get("type")));
        if ("special".equals(type)) {
            return null;
        }
        if (node.has("model") && node.get("model").isJsonPrimitive()) {
            return node.get("model").getAsString();
        }
        for (String key : new String[] {"fallback", "on_false", "on_true"}) {
            if (node.has(key) && node.get(key).isJsonObject()) {
                String nested = modelNameOf(node.getAsJsonObject(key));
                if (nested != null) {
                    return nested;
                }
            }
        }
        for (String key : new String[] {"cases", "entries", "models"}) {
            if (!node.has(key) || !node.get(key).isJsonArray()) {
                continue;
            }
            for (JsonElement entry : node.getAsJsonArray(key)) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject object = entry.getAsJsonObject();
                JsonElement inner = object.get("model");
                String nested = inner != null && inner.isJsonObject()
                    ? modelNameOf(inner.getAsJsonObject())
                    : (inner != null && inner.isJsonPrimitive() ? inner.getAsString() : null);
                if (nested == null) {
                    nested = modelNameOf(object);
                }
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /** The model and its ancestors, child first. */
    private List<JsonObject> resolveChain(String modelName) throws IOException {
        List<JsonObject> chain = new ArrayList<>();
        String name = modelName;
        // Bounded so a malformed parent cycle fails the build's block, not the whole build.
        for (int depth = 0; name != null && depth < 16; depth++) {
            JsonObject model = json("models/" + bare(name) + ".json");
            if (model == null) {
                break;
            }
            chain.add(model);
            name = asString(model.get("parent"));
        }
        return chain;
    }

    private static JsonArray firstArray(List<JsonObject> chain, String key) {
        for (JsonObject model : chain) {
            if (model.has(key) && model.get(key).isJsonArray()) {
                return model.getAsJsonArray(key);
            }
        }
        return null;
    }

    private static String firstString(List<JsonObject> chain, String key) {
        for (JsonObject model : chain) {
            String value = asString(model.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Texture variables, child winning over parent. */
    private static Map<String, String> mergedTextures(List<JsonObject> chain) {
        Map<String, String> textures = new LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            JsonObject model = chain.get(i);
            if (!model.has("textures") || !model.get("textures").isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> entry
                    : model.getAsJsonObject("textures").entrySet()) {
                String value = asString(entry.getValue());
                if (value != null) {
                    textures.put(entry.getKey(), value);
                }
            }
        }
        return textures;
    }

    /**
     * The {@code display.gui} entry from the nearest ancestor that declares one.
     *
     * <p>Not a constant: a lectern uses scale 0.6 and a plain cube 0.625, so assuming one value
     * draws a whole family of blocks at the wrong size.</p>
     */
    private static Transform guiTransform(List<JsonObject> chain) {
        for (JsonObject model : chain) {
            if (!model.has("display") || !model.get("display").isJsonObject()) {
                continue;
            }
            JsonObject display = model.getAsJsonObject("display");
            if (!display.has("gui") || !display.get("gui").isJsonObject()) {
                continue;
            }
            JsonObject gui = display.getAsJsonObject("gui");
            return new Transform(
                readVector(gui.get("rotation"), 0, 0, 0),
                readVector(gui.get("translation"), 0, 0, 0),
                readVector(gui.get("scale"), 1, 1, 1));
        }
        return Transform.blockGui();
    }

    // ------------------------------------------------------------------
    // Elements
    // ------------------------------------------------------------------

    private Element readElement(JsonObject raw, Map<String, String> textures) throws IOException {
        double[] from = readVector(raw.get("from"), 0, 0, 0);
        double[] to = readVector(raw.get("to"), 16, 16, 16);
        boolean shade = !raw.has("shade") || raw.get("shade").getAsBoolean();
        Rotation rotation = readRotation(raw.get("rotation"));

        Map<Direction, Face> faces = new EnumMap<>(Direction.class);
        if (raw.has("faces") && raw.get("faces").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : raw.getAsJsonObject("faces").entrySet()) {
                Direction direction = directionOf(entry.getKey());
                if (direction == null || !entry.getValue().isJsonObject()) {
                    continue;
                }
                Face face = readFace(entry.getValue().getAsJsonObject(), direction, from, to,
                    textures);
                if (face != null) {
                    faces.put(direction, face);
                }
            }
        }
        return new Element(from, to, rotation, shade, faces);
    }

    private Face readFace(JsonObject raw, Direction direction, double[] from, double[] to,
                          Map<String, String> textures) throws IOException {
        BufferedImage texture = texture(resolveTexture(asString(raw.get("texture")), textures));
        if (texture == null) {
            return null;
        }
        double[] uv = raw.has("uv")
            ? readUv(raw.getAsJsonArray("uv"))
            : defaultUv(direction, from, to);
        int rotation = raw.has("rotation") ? raw.get("rotation").getAsInt() : 0;
        boolean tinted = raw.has("tintindex") && raw.get("tintindex").getAsInt() >= 0;
        return new Face(texture, uv, rotation, tinted);
    }

    private static Rotation readRotation(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) {
            return null;
        }
        JsonObject object = raw.getAsJsonObject();
        String axis = asString(object.get("axis"));
        if (axis == null || !object.has("angle")) {
            return null;
        }
        return new Rotation(object.get("angle").getAsDouble(),
            axis.toLowerCase(Locale.ROOT).charAt(0),
            readVector(object.get("origin"), 8, 8, 8),
            object.has("rescale") && object.get("rescale").getAsBoolean());
    }

    /**
     * The UV a face gets when it declares none: its own extent on the two axes it spans.
     *
     * <p>Reproduced from Minecraft's own defaults, flips included — getting a flip wrong mirrors
     * the texture on that face, which on an asymmetric block (a furnace front, a lectern) is
     * immediately visible and on a symmetric one is invisible until it is not.</p>
     */
    private static double[] defaultUv(Direction direction, double[] from, double[] to) {
        return switch (direction) {
            case DOWN -> new double[] {from[0], 16 - to[2], to[0], 16 - from[2]};
            case UP -> new double[] {from[0], from[2], to[0], to[2]};
            case NORTH -> new double[] {16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]};
            case SOUTH -> new double[] {from[0], 16 - to[1], to[0], 16 - from[1]};
            case WEST -> new double[] {from[2], 16 - to[1], to[2], 16 - from[1]};
            case EAST -> new double[] {16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]};
        };
    }

    /** Follows {@code #name} indirection to a real texture path. */
    private static String resolveTexture(String reference, Map<String, String> textures) {
        String value = reference;
        for (int depth = 0; value != null && value.startsWith("#") && depth < 16; depth++) {
            value = textures.get(value.substring(1));
        }
        return value;
    }

    private static Direction directionOf(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "down" -> Direction.DOWN;
            case "up" -> Direction.UP;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            case "east" -> Direction.EAST;
            default -> null;
        };
    }

    private static double[] readUv(JsonArray raw) {
        return new double[] {
            raw.get(0).getAsDouble(), raw.get(1).getAsDouble(),
            raw.get(2).getAsDouble(), raw.get(3).getAsDouble()};
    }

    private static double[] readVector(JsonElement raw, double x, double y, double z) {
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() < 3) {
            return new double[] {x, y, z};
        }
        JsonArray array = raw.getAsJsonArray();
        return new double[] {
            array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble()};
    }

    // ------------------------------------------------------------------
    // Fetching
    // ------------------------------------------------------------------

    private JsonObject json(String relativePath) throws IOException {
        if (jsonCache.containsKey(relativePath)) {
            return jsonCache.get(relativePath);
        }
        byte[] bytes = fetch("assets/minecraft/" + relativePath);
        JsonObject parsed = null;
        if (bytes != null) {
            parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
        jsonCache.put(relativePath, parsed);
        return parsed;
    }

    private BufferedImage texture(String texturePath) throws IOException {
        if (texturePath == null) {
            return null;
        }
        String path = bare(texturePath);
        if (textureCache.containsKey(path)) {
            return textureCache.get(path);
        }
        byte[] bytes = fetch("assets/minecraft/textures/" + path + ".png");
        BufferedImage image = null;
        if (bytes != null) {
            image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        }
        textureCache.put(path, image);
        return image;
    }

    /**
     * Downloads once and caches on disk. {@code null} only for a 404 — anything else throws, so a
     * build without network fails loudly instead of quietly baking a jar with no icons in it.
     */
    private byte[] fetch(String remotePath) throws IOException {
        Path cached = cacheDir.resolve(remotePath.replaceAll("[/\\\\]", "_"));
        Path missing = cacheDir.resolve(cached.getFileName() + ".404");
        if (Files.isRegularFile(missing)) {
            return null;
        }
        if (Files.isRegularFile(cached)) {
            return Files.readAllBytes(cached);
        }
        HttpURLConnection connection =
            (HttpURLConnection) URI.create(baseUrl + "/" + remotePath).toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                Files.write(missing, new byte[0]);
                return null;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status + " for " + remotePath);
            }
            try (InputStream in = connection.getInputStream()) {
                byte[] bytes = in.readAllBytes();
                Files.write(cached, bytes);
                return bytes;
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String bare(String namespaced) {
        if (namespaced == null) {
            return null;
        }
        int colon = namespaced.indexOf(':');
        return colon < 0 ? namespaced : namespaced.substring(colon + 1);
    }

    private static String asString(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
