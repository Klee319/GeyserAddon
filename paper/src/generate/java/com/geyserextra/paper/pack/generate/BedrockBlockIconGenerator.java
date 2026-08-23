package com.geyserextra.paper.pack.generate;

import com.geyserextra.core.util.IsometricBlockRenderer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Bakes an inventory icon for every vanilla block that has no flat item texture.
 *
 * <p>Why this exists: a Bedrock custom item can only name a flat PNG for its icon, and Geyser
 * refuses {@code geysermc:block_placer} (the component that would hand the client the block model)
 * on definitions that extend a vanilla Java item — every component in that namespace reports
 * {@code vanilla() == false}. So a custom item based on a block had no icon it could point at, and
 * {@code CustomItemsHandler} deliberately left those items unregistered rather than show a broken
 * one. An unregistered item has no Bedrock identifier, and {@code BedrockRecipeInjector} can only
 * name registered items — which is how 203 of 360 corrected recipes came to be dropped and why
 * block-based crafts were impossible on Bedrock.</p>
 *
 * <p>Rendering the cube ourselves gives those items an icon that matches what Java draws, so they
 * register like any other item and recipes can name them.</p>
 *
 * <p>Textures come from Mojang's own {@code bedrock-samples} pack, cached on disk: the first build
 * downloads, later builds reuse. A block whose texture cannot be resolved or downloaded is skipped
 * with a note rather than failing the build — it simply keeps the old vanilla-fallback behaviour.</p>
 */
public final class BedrockBlockIconGenerator {

    /**
     * Blocks whose model is not a full cube. These get their texture flat instead of projected: a
     * cube built from a decorated pot's side would misrepresent the block.
     *
     * <p>They are still baked. Skipping them entirely is what left {@code source_jar} and friends
     * unregistered, and an unregistered item cannot be named by an injected recipe — so "no icon"
     * costs the craft, while "flat icon" costs only some depth.</p>
     */
    private static final Set<String> NON_CUBE_BLOCKS = Set.of(
        "decorated_pot", "lectern", "beacon", "enchanting_table", "wither_rose", "iron_chain",
        "chain", "flower_pot", "cactus", "brewing_stand", "cauldron", "hopper", "anvil",
        "grindstone", "stonecutter", "bell", "campfire", "soul_campfire", "conduit", "end_rod",
        "lightning_rod", "candle", "sea_pickle", "turtle_egg", "amethyst_cluster"
    );

    /**
     * Bedrock ids that {@code blocks.json} still files under an older name.
     *
     * <p>The id we must key the output on is whatever {@code useBlockIcon} says, because that is
     * what the pack builder looks up. When Mojang renames a block, their own sample pack can lag
     * the name Geyser already uses, and the block silently gets no icon — which silently leaves its
     * custom items unregistered. Mapping the lookup (never the output name) keeps both sides
     * happy.</p>
     */
    private static final Map<String, String> BLOCKS_JSON_ALIASES = Map.of(
        "iron_chain", "chain",
        "grass_block", "grass",
        // readBlockFaces lowercases every key, so the alias must be written lowercase even though
        // blocks.json spells this one "seaLantern".
        "sea_lantern", "sealantern"
    );

    public static void main(String[] args) throws IOException {
        if (args.length < 6) {
            throw new IllegalArgumentException("usage: <terrain_texture.json> <blocks.json> "
                + "<vanilla_texture_paths.json> <textureCacheDir> <outputDir> <bedrockSamplesBase>");
        }
        Path terrainTextureFile = Path.of(args[0]);
        Path blocksFile = Path.of(args[1]);
        Path vanillaTexturePathsFile = Path.of(args[2]);
        Path textureCacheDir = Path.of(args[3]);
        Path outputDir = Path.of(args[4]);
        String bedrockSamplesBase = args[5];

        Map<String, String> terrain = readTerrainTexturePaths(readJson(terrainTextureFile));
        Map<String, BlockFaces> blocks = readBlockFaces(readJson(blocksFile));
        Set<String> wanted = readUseBlockIconTargets(readJson(vanillaTexturePathsFile));

        Files.createDirectories(outputDir);
        Files.createDirectories(textureCacheDir);

        List<String> generated = new ArrayList<>();
        List<String> flat = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String block : new LinkedHashSet<>(wanted)) {
            BlockFaces faces = blocks.get(block);
            if (faces == null) {
                faces = blocks.get(BLOCKS_JSON_ALIASES.get(block));
            }
            if (faces == null) {
                skipped.add(block + " (absent from blocks.json)");
                continue;
            }
            BufferedImage up = loadTexture(faces.up(), terrain, textureCacheDir, bedrockSamplesBase);
            if (up == null) {
                skipped.add(block + " (up texture unresolved: " + faces.up() + ")");
                continue;
            }
            BufferedImage side = faces.side() == null || faces.side().equals(faces.up())
                ? null
                : loadTexture(faces.side(), terrain, textureCacheDir, bedrockSamplesBase);

            BufferedImage icon;
            if (NON_CUBE_BLOCKS.contains(block)) {
                // Its own texture, undistorted. Recognisable, and — unlike no icon at all — it
                // lets the item register and its recipes reach the client.
                icon = IsometricBlockRenderer.flat(side != null ? side : up);
                flat.add(block);
            } else {
                icon = IsometricBlockRenderer.render(up, side);
            }
            Path target = outputDir.resolve(block + ".png");
            try (var out = Files.newOutputStream(target)) {
                ImageIO.write(icon, "PNG", out);
            }
            generated.add(block);
        }

        if (generated.size() < wanted.size() / 2) {
            // Far too few to be a data change. Something structural broke — a moved sample pack, a
            // renamed json — and shipping the jar anyway would un-register hundreds of items and
            // drop their recipes without a single failing test.
            throw new IOException("only " + generated.size() + " of " + wanted.size()
                + " block icons could be baked; refusing to ship a jar that would leave"
                + " block-based items unregistered");
        }

        writeIndex(outputDir.resolve("index.json"), generated);

        System.out.println("[block-icons] generated " + generated.size()
            + " block icon(s) (" + flat.size() + " flat, not a full cube), skipped "
            + skipped.size());
        for (String note : skipped) {
            System.out.println("[block-icons]   skipped " + note);
        }
    }

    /**
     * The block ids we need an icon for: exactly the {@code useBlockIcon} targets, which is the set
     * of bases {@code CustomItemsHandler} currently refuses to register for lack of a flat icon.
     */
    private static Set<String> readUseBlockIconTargets(JsonObject root) {
        Set<String> out = new LinkedHashSet<>();
        JsonObject useBlockIcon = root.getAsJsonObject("useBlockIcon");
        if (useBlockIcon == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> entry : useBlockIcon.entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonPrimitive()) {
                out.add(value.getAsString().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** {@code terrain_texture.json}: texture key to the first {@code textures/blocks/...} path. */
    private static Map<String, String> readTerrainTexturePaths(JsonObject root) {
        Map<String, String> out = new TreeMap<>();
        JsonObject data = root.getAsJsonObject("texture_data");
        if (data == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            JsonElement textures = entry.getValue().isJsonObject()
                ? entry.getValue().getAsJsonObject().get("textures")
                : null;
            String path = firstTexturePath(textures);
            if (path != null) {
                out.put(entry.getKey(), path);
            }
        }
        return out;
    }

    /**
     * Unwraps the three shapes {@code textures} takes: a bare path, a list of variants (take the
     * first — the icon must be deterministic), or an object carrying {@code path} plus tint data.
     */
    private static String firstTexturePath(JsonElement textures) {
        if (textures == null) {
            return null;
        }
        if (textures.isJsonPrimitive()) {
            return textures.getAsString();
        }
        if (textures.isJsonArray()) {
            JsonArray array = textures.getAsJsonArray();
            return array.isEmpty() ? null : firstTexturePath(array.get(0));
        }
        if (textures.isJsonObject()) {
            JsonElement path = textures.getAsJsonObject().get("path");
            return path != null && path.isJsonPrimitive() ? path.getAsString() : null;
        }
        return null;
    }

    /** {@code blocks.json}: block id to the texture keys of its up and side faces. */
    private static Map<String, BlockFaces> readBlockFaces(JsonObject root) {
        Map<String, BlockFaces> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonElement textures = entry.getValue().getAsJsonObject().get("textures");
            if (textures == null) {
                continue;
            }
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (textures.isJsonPrimitive()) {
                out.put(name, new BlockFaces(textures.getAsString(), null));
                continue;
            }
            if (!textures.isJsonObject()) {
                continue;
            }
            JsonObject faces = textures.getAsJsonObject();
            String up = asString(faces.get("up"));
            String side = asString(faces.get("side"));
            if (up == null) {
                // A block that names only "side" (or per-direction faces) still reads as a cube
                // when the side texture is used all over, which is what Java's cube_all does.
                up = side != null ? side : asString(faces.get("north"));
                side = up;
            }
            if (up != null) {
                out.put(name, new BlockFaces(up, side));
            }
        }
        return out;
    }

    private static String asString(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /**
     * Resolves a texture key through {@code terrain_texture.json} and reads the PNG, downloading it
     * into the cache on first use.
     *
     * <p>Returns {@code null} only when the texture genuinely is not there — an unresolvable key,
     * or a 404 — so the caller can skip that one block. Anything else (no network, a proxy error, a
     * corrupt cache entry) is thrown, because a silent skip here reproduces the exact bug this
     * whole generator exists to fix: no icon means the item does not register, which means its
     * Bedrock recipes are dropped. A failed build is loud; a jar quietly missing 900 icons is
     * not.</p>
     */
    private static BufferedImage loadTexture(String textureKey, Map<String, String> terrain,
                                             Path cacheDir, String base) throws IOException {
        String path = terrain.get(textureKey);
        if (path == null) {
            // Some blocks name a terrain path directly rather than a key. Guessing
            // "textures/blocks/<key>" for the rest was tried and resolved nothing, so an
            // unresolved key is reported instead of paid for with a build-time 404.
            path = textureKey.startsWith("textures/") ? textureKey : null;
        }
        if (path == null) {
            return null;
        }
        Path cached = cacheDir.resolve(path.replace('/', '_') + ".png");
        if (!Files.isRegularFile(cached)) {
            byte[] bytes = download(base + "/" + path + ".png");
            if (bytes == null) {
                return null;
            }
            Files.createDirectories(cached.getParent());
            Files.write(cached, bytes);
        }
        BufferedImage image = ImageIO.read(cached.toFile());
        if (image == null) {
            throw new IOException("cached texture is not a readable image, delete it and rebuild: "
                + cached);
        }
        return image;
    }

    /** Fetches one texture. {@code null} for a 404; throws for every other failure. */
    private static byte[] download(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                return null;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status + " for " + url);
            }
            try (InputStream in = connection.getInputStream()) {
                return in.readAllBytes();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void writeIndex(Path target, List<String> generated) throws IOException {
        StringBuilder sb = new StringBuilder("{\n  \"blocks\": [\n");
        for (int i = 0; i < generated.size(); i++) {
            sb.append("    \"").append(generated.get(i)).append('"');
            if (i < generated.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("  ]\n}\n");
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }

    private static JsonObject readJson(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /** The two texture keys a cube icon needs. */
    private record BlockFaces(String up, String side) {
    }

    private BedrockBlockIconGenerator() {
    }
}
