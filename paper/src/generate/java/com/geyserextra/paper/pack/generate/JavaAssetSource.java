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
 * decorated pots, conduits, chests, banners and heads from code, and those models carry only a
 * particle texture. For those the lookup falls through to {@code models/entity/<id>.json}, which
 * Mojang does not ship but this module bundles — see the README next to those resources. Anything
 * still without geometry leaves the caller to fall back to the cube approximation.</p>
 */
final class JavaAssetSource {

    /**
     * A code-rendered block mapped onto a bundled entity model.
     *
     * @param displayId Java item whose {@code display} governs the slot — usually the block
     *     itself, but Bedrock's {@code undyed_shulker_box} is Java's {@code shulker_box}
     * @param model bundled model supplying the geometry
     * @param textures texture-variable overrides, for families sharing one model
     * @param gui synthetic chain head overriding the item's {@code display.gui}, for the one case
     *     where Java's display presumes a geometry ours does not match
     */
    private record CodeRendered(String displayId, String model, Map<String, String> textures,
                                JsonObject gui) {
        CodeRendered(String displayId, String model, Map<String, String> textures) {
            this(displayId, model, textures, null);
        }
    }

    private static final List<String> DYE_COLORS = List.of(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    /**
     * Blocks whose bundled entity model is not simply {@code entity/<block id>}.
     *
     * <p>The chests are one model with a texture set per variant — the four copper sheets reuse
     * the normal chest's geometry outright. The sixteen shulker colours reuse the plain box with
     * their own sheet rather than {@code dyed_shulker_box}, whose faces expect a dye tint this
     * renderer would paint foliage-green. The golem statues are filed by pose; a slot always shows
     * the standing one, and waxing changes neither shape nor sheet. Banners and beds stay
     * unmapped: the banner's cloth is tinted per dye, so it has the same problem as the dyed
     * shulker template, and there is no bundled bed at all.</p>
     */
    private static final Map<String, CodeRendered> CODE_RENDERED = buildCodeRendered();

    private static Map<String, CodeRendered> buildCodeRendered() {
        Map<String, CodeRendered> map = new HashMap<>();
        map.put("chest", new CodeRendered("chest", "entity/chest/normal", Map.of()));
        map.put("trapped_chest",
            new CodeRendered("trapped_chest", "entity/chest/trapped", Map.of()));
        map.put("ender_chest", new CodeRendered("ender_chest", "entity/chest/ender", Map.of()));
        map.put("undyed_shulker_box",
            new CodeRendered("shulker_box", "entity/shulker_box", Map.of()));
        for (String color : DYE_COLORS) {
            map.put(color + "_shulker_box", new CodeRendered(color + "_shulker_box",
                "entity/shulker_box", Map.of("0", "entity/shulker/shulker_" + color)));
        }
        Map<String, String> copperSheets = Map.of(
            "copper_chest", "copper",
            "exposed_copper_chest", "copper_exposed",
            "weathered_copper_chest", "copper_weathered",
            "oxidized_copper_chest", "copper_oxidized");
        for (Map.Entry<String, String> entry : copperSheets.entrySet()) {
            Map<String, String> sheet = Map.of("chest", "entity/chest/" + entry.getValue());
            map.put(entry.getKey(),
                new CodeRendered(entry.getKey(), "entity/chest/normal", sheet));
            map.put("waxed_" + entry.getKey(),
                new CodeRendered("waxed_" + entry.getKey(), "entity/chest/normal", sheet));
        }
        // Mojang's statue template cannot be used as-is: item/template_copper_golem_statue rolls
        // the slot 180° around Z (gui rotation [30,45,180]) because the client's internal statue
        // geometry is authored upside down, and its translation is stated for that geometry too.
        // The bundled model stands upright spanning y 0..24, so the roll goes, and the translation
        // recentres that span: -16 * 0.55 * cos(30°) * (12-8)/16 ≈ -1.9.
        JsonObject statueGui = guiDisplay(
            new double[] {30, 45, 0}, new double[] {0, -1.9, 0}, new double[] {0.55, 0.55, 0.55});
        for (String oxidation : List.of("", "exposed_", "weathered_", "oxidized_")) {
            String statue = oxidation + "copper_golem_statue";
            String model = "entity/copper_golem/" + statue + "_standing";
            map.put(statue, new CodeRendered(statue, model, Map.of(), statueGui));
            map.put("waxed_" + statue,
                new CodeRendered("waxed_" + statue, model, Map.of(), statueGui));
        }
        return Map.copyOf(map);
    }

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
        JavaBlockModel model = build(resolveChain(modelName));
        if (model != null) {
            return model;
        }
        // Nothing to draw means Minecraft renders this one from code. The bundled entity models
        // supply the geometry — reached through the ordinary chain so a #entity texture variable
        // and a parent (wither_skeleton_skull -> skull_32) still resolve. The vanilla item model
        // stays at the head of the chain because it is where Java declares how these items sit in
        // a slot: display.gui is [30,45,0] (front-on; the block default shows the back corner),
        // the pot adds gui_light front, the conduit scale 1.0, the skulls translation [0,3,0].
        CodeRendered mapped = CODE_RENDERED.get(blockId);
        List<JsonObject> chain = new ArrayList<>(
            resolveChain(displayModelName(mapped != null ? mapped.displayId() : blockId)));
        if (mapped != null && mapped.gui() != null) {
            // Ahead of the item chain: guiTransform takes the first display it finds.
            chain.add(0, mapped.gui());
        }
        if (mapped != null && !mapped.textures().isEmpty()) {
            // Ahead of the entity chain: mergedTextures lets earlier entries win, which is how a
            // colour's own sheet replaces the model's default without touching the file.
            chain.add(textureOverrides(mapped.textures()));
        }
        chain.addAll(resolveChain(mapped != null ? mapped.model() : "entity/" + blockId));
        return build(chain);
    }

    /**
     * Whether Java defines an item for this id.
     *
     * <p>The item definition is the authority on what an inventory slot shows. Several Java
     * blocks share one Bedrock id, and the itemless ones (lava_cauldron, oak_wall_hanging_sign)
     * must not out-draw a sibling that has a real item — plain cauldron's item is a flat sprite,
     * and the lava variant's block model would otherwise win by iteration order.</p>
     */
    boolean hasItemDefinition(String blockId) throws IOException {
        return json("items/" + blockId + ".json") != null
            || json("models/item/" + blockId + ".json") != null;
    }

    /**
     * The flat sprite Java shows for this block's item, or {@code null} when the item is not one.
     *
     * <p>Saplings, signs, rails, torches, flowers, doors and glass panes are 3D as blocks but
     * their <em>items</em> resolve to a layer0 sprite ({@code item/generated}), and Java draws
     * exactly that texture in the slot. Projecting them onto a cube instead invents a solid block
     * the player never sees — which is what 160-odd of the cube approximations were doing.</p>
     */
    BufferedImage flatSpriteFor(String blockId) throws IOException {
        String modelName = itemModelName(blockId);
        if (modelName == null) {
            modelName = json("models/item/" + blockId + ".json") != null
                ? "item/" + blockId
                : null;
        }
        if (modelName == null) {
            return null;
        }
        List<JsonObject> chain = resolveChain(modelName);
        JsonArray elements = firstArray(chain, "elements");
        if (elements != null && !elements.isEmpty()) {
            return null; // has geometry, so the model renderer owns it
        }
        return texture(resolveTexture("#layer0", mergedTextures(chain)));
    }

    private JavaBlockModel build(List<JsonObject> chain) throws IOException {
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

    /**
     * The model whose {@code display} governs a code-rendered item in an inventory slot.
     *
     * <p>Skulls have no {@code models/item/skeleton_skull.json}; their display lives in
     * {@code item/template_skull}, which only the item definition's {@code special} node names via
     * {@code base}. Everything else does have an item model of its own name, so that is the
     * fallback.</p>
     */
    private String displayModelName(String blockId) throws IOException {
        JsonObject item = json("items/" + blockId + ".json");
        String base = item == null ? null : specialBase(item.get("model"));
        return base != null ? base : "item/" + blockId;
    }

    /** A synthetic chain entry carrying only a {@code display.gui}. */
    private static JsonObject guiDisplay(double[] rotation, double[] translation, double[] scale) {
        JsonObject gui = new JsonObject();
        gui.add("rotation", jsonArray(rotation));
        gui.add("translation", jsonArray(translation));
        gui.add("scale", jsonArray(scale));
        JsonObject display = new JsonObject();
        display.add("gui", gui);
        JsonObject model = new JsonObject();
        model.add("display", display);
        return model;
    }

    private static JsonArray jsonArray(double[] values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            array.add(value);
        }
        return array;
    }

    /** A synthetic chain entry carrying only texture-variable overrides. */
    private static JsonObject textureOverrides(Map<String, String> overrides) {
        JsonObject textures = new JsonObject();
        overrides.forEach(textures::addProperty);
        JsonObject model = new JsonObject();
        model.add("textures", textures);
        return model;
    }

    /** The {@code base} of the first {@code special} node, or {@code null} if there is none. */
    private static String specialBase(JsonElement node) {
        if (node == null || !node.isJsonObject()) {
            return null;
        }
        JsonObject object = node.getAsJsonObject();
        if ("special".equals(bare(asString(object.get("type"))))) {
            return asString(object.get("base"));
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                String base = specialBase(value);
                if (base != null) {
                    return base;
                }
            } else if (value.isJsonArray()) {
                for (JsonElement child : value.getAsJsonArray()) {
                    String base = specialBase(child);
                    if (base != null) {
                        return base;
                    }
                }
            }
        }
        return null;
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
        // UVs are 0..16 whatever the texture's resolution — for the bundled Blockbench models
        // too. Their texture_size is metadata only: the skull's [6,4,8,8] lands exactly on the
        // head's back face once multiplied by the real texture size, and scaling it by
        // texture_size instead lands on a near-uniform patch, which is how the skulls once baked
        // as plain white and black cubes without anything obviously failing.
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

    /**
     * Follows {@code #name} indirection to a real texture path.
     *
     * <p>A bare name that matches a texture variable is followed too: Mojang's own
     * {@code block/heavy_core} writes {@code "texture": "all"} without the {@code #}, and the
     * client resolves it anyway. Real texture paths always carry a directory, so the ambiguity is
     * theoretical.</p>
     */
    private static String resolveTexture(String reference, Map<String, String> textures) {
        String value = reference;
        for (int depth = 0; value != null && depth < 16; depth++) {
            if (value.startsWith("#")) {
                value = textures.get(value.substring(1));
            } else if (textures.containsKey(value)) {
                value = textures.get(value);
            } else {
                break;
            }
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
        String assetPath = "assets/minecraft/" + relativePath;
        byte[] bytes = fetch(assetPath);
        if (bytes == null) {
            // Remote first, bundled second: if Mojang ever ships a real model for one of the
            // code-rendered blocks, theirs wins and the bundled copy goes unused.
            bytes = bundled(assetPath);
        }
        JsonObject parsed = null;
        if (bytes != null) {
            parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
        jsonCache.put(relativePath, parsed);
        return parsed;
    }

    /** A model shipped with this module under the same path Minecraft would have used. */
    private static byte[] bundled(String assetPath) throws IOException {
        try (InputStream in = JavaAssetSource.class.getClassLoader()
                .getResourceAsStream(assetPath)) {
            return in == null ? null : in.readAllBytes();
        }
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
