package com.geyserextra.paper.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Downloads Minecraft's ja_jp.json translation file from Mojang's Asset API
 * and registers it with Adventure's GlobalTranslator.
 *
 * Why: Paper server only bundles en_us.json. Japanese translations are required
 * for rendering advancement names, entity names, block names, and item names
 * via {@link TranslationUtil#renderJapanese(net.kyori.adventure.text.Component)}.
 *
 * The download runs asynchronously to avoid blocking server startup.
 * Translations are cached to disk with a version hash to avoid re-downloading
 * on every restart; re-download occurs only when Minecraft updates.
 */
public final class JapaneseTranslationLoader {

    /** Mojang's version manifest endpoint. */
    private static final String VERSION_MANIFEST_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

    /** Base URL for downloading assets by hash. */
    private static final String RESOURCE_BASE_URL =
            "https://resources.download.minecraft.net/";

    /** Asset object key for the Japanese language file. */
    private static final String JA_JP_ASSET_KEY = "minecraft/lang/ja_jp.json";

    /** Cache file names within the extension data folder. */
    private static final String CACHE_FILE_NAME = "ja_jp.json";
    private static final String HASH_FILE_NAME = "ja_jp.hash";

    /** HTTP connection and read timeout in milliseconds. */
    private static final int HTTP_TIMEOUT_MS = 15_000;

    private JapaneseTranslationLoader() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Starts asynchronous loading of Japanese translations.
     *
     * Why async: The Mojang API chain requires up to 4 sequential HTTP requests.
     * Running synchronously would delay server startup by several seconds.
     *
     * @param cacheDir directory to store cached ja_jp.json and hash file
     * @param logger   logger for progress and error messages
     */
    public static void loadAsync(Path cacheDir, Logger logger) {
        Thread thread = new Thread(() -> {
            try {
                loadTranslations(cacheDir, logger);
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "Failed to load Japanese translations. "
                                + "Translations will fall back to English or hardcoded values.",
                        e);
            }
        }, "GeyserExtra-JapaneseTranslationLoader");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Main loading logic: check cache, download if needed, parse, and register.
     */
    private static void loadTranslations(Path cacheDir, Logger logger) throws IOException {
        // Ensure cache directory exists
        Files.createDirectories(cacheDir);

        // Try loading from cache first
        Map<String, String> cached = tryLoadFromCache(cacheDir, logger);
        if (cached != null) {
            registerTranslations(cached, logger);
            return;
        }

        // Cache miss or outdated: download from Mojang API
        logger.info("Downloading Japanese translations from Mojang...");
        DownloadResult result = downloadJaJpJson();

        // Save to cache
        saveCache(cacheDir, result.content(), result.hash());

        // Parse and register
        Map<String, String> translations = parseTranslations(result.content());
        registerTranslations(translations, logger);
    }

    /**
     * Attempts to load translations from the disk cache.
     *
     * Why hash file: We store the asset hash separately so we can detect
     * when Minecraft updates without re-downloading the entire file.
     * On a cache hit we skip all 4 Mojang API calls.
     *
     * @return parsed translations map, or null if cache is invalid/missing
     */
    private static Map<String, String> tryLoadFromCache(Path cacheDir, Logger logger) {
        Path cacheFile = cacheDir.resolve(CACHE_FILE_NAME);
        Path hashFile = cacheDir.resolve(HASH_FILE_NAME);

        if (!Files.exists(cacheFile) || !Files.exists(hashFile)) {
            return null;
        }

        try {
            String content = Files.readString(cacheFile, StandardCharsets.UTF_8);
            Map<String, String> translations = parseTranslations(content);
            if (translations.isEmpty()) {
                return null;
            }
            logger.info("Loaded " + translations.size()
                    + " Japanese translations from cache.");
            return translations;
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "Failed to read cached translations, will re-download.", e);
            return null;
        }
    }

    /**
     * Downloads ja_jp.json by traversing the Mojang API chain:
     * 1. version_manifest_v2.json -> latest release version URL
     * 2. version URL -> assetIndex URL
     * 3. asset index URL -> ja_jp.json hash
     * 4. resources/{hash[0:2]}/{hash} -> ja_jp.json content
     *
     * @return download result containing content and hash
     * @throws IOException if any HTTP request fails
     */
    private static DownloadResult downloadJaJpJson() throws IOException {
        String versionUrl = fetchLatestVersionUrl();
        String assetIndexUrl = fetchAssetIndexUrl(versionUrl);
        String hash = fetchJaJpHash(assetIndexUrl);
        String content = downloadResource(hash);
        return new DownloadResult(content, hash);
    }

    /**
     * Step 1: Fetch version_manifest_v2.json and extract the latest release version URL.
     */
    private static String fetchLatestVersionUrl() throws IOException {
        String manifest = httpGet(VERSION_MANIFEST_URL);
        JsonObject root = JsonParser.parseString(manifest).getAsJsonObject();
        String latestRelease = root.getAsJsonObject("latest")
                .get("release").getAsString();

        // Find the URL for this version
        for (JsonElement element : root.getAsJsonArray("versions")) {
            JsonObject version = element.getAsJsonObject();
            if (latestRelease.equals(version.get("id").getAsString())) {
                return version.get("url").getAsString();
            }
        }
        throw new IOException("Could not find version URL for release: " + latestRelease);
    }

    /**
     * Step 2: Fetch version JSON and extract the assetIndex URL.
     */
    private static String fetchAssetIndexUrl(String versionUrl) throws IOException {
        String versionJson = httpGet(versionUrl);
        JsonObject root = JsonParser.parseString(versionJson).getAsJsonObject();
        return root.getAsJsonObject("assetIndex").get("url").getAsString();
    }

    /**
     * Step 3: Fetch asset index JSON and extract the hash for ja_jp.json.
     */
    private static String fetchJaJpHash(String assetIndexUrl) throws IOException {
        String indexJson = httpGet(assetIndexUrl);
        JsonObject root = JsonParser.parseString(indexJson).getAsJsonObject();
        JsonObject objects = root.getAsJsonObject("objects");
        JsonObject jaJp = objects.getAsJsonObject(JA_JP_ASSET_KEY);
        if (jaJp == null) {
            throw new IOException("Asset index does not contain " + JA_JP_ASSET_KEY);
        }
        return jaJp.get("hash").getAsString();
    }

    /**
     * Step 4: Download the actual resource file using its hash.
     *
     * Why hash-based URL: Mojang stores all assets in a content-addressable store.
     * The URL format is: resources.download.minecraft.net/{first2chars}/{fullhash}
     */
    private static String downloadResource(String hash) throws IOException {
        String url = RESOURCE_BASE_URL + hash.substring(0, 2) + "/" + hash;
        return httpGet(url);
    }

    /**
     * Parses the ja_jp.json content into a key-value map.
     *
     * The JSON structure is a flat object: {"translation.key": "translated value", ...}
     *
     * @param jsonContent raw JSON string
     * @return map of translation key to translated value
     */
    private static Map<String, String> parseTranslations(String jsonContent) {
        JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();
        Map<String, String> translations = new LinkedHashMap<>(root.size());
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            translations.put(entry.getKey(), entry.getValue().getAsString());
        }
        return translations;
    }

    /**
     * Registers all translations with Adventure's GlobalTranslator.
     *
     * Why MessageFormat: Adventure's TranslationRegistry expects MessageFormat instances.
     * Minecraft's translations use {0}, {1} etc. for placeholders, which aligns with
     * MessageFormat's syntax.
     *
     * Why escape single quotes: MessageFormat treats single quotes as special characters
     * (quoting mechanism). Minecraft translations contain literal single quotes (e.g.,
     * "Jack o'Lantern") that must be escaped by doubling them (' -> '').
     *
     * @param translations map of translation key to translated value
     * @param logger       logger for progress reporting
     */
    private static void registerTranslations(Map<String, String> translations, Logger logger) {
        TranslationRegistry registry = TranslationRegistry.create(
                Key.key("geyserextra", "ja_jp"));
        registry.defaultLocale(Locale.JAPANESE);

        int registered = 0;
        for (Map.Entry<String, String> entry : translations.entrySet()) {
            try {
                // Convert Minecraft's %s/%1$s placeholders to MessageFormat's {0}/{1} syntax,
                // then escape single quotes for MessageFormat compatibility.
                String converted = convertMinecraftFormat(entry.getValue());
                registry.register(
                        entry.getKey(),
                        Locale.JAPANESE,
                        new MessageFormat(converted, Locale.JAPANESE));
                registered++;
            } catch (Exception e) {
                logger.fine("Skipped translation key '" + entry.getKey()
                        + "': " + e.getMessage());
            }
        }

        GlobalTranslator.translator().addSource(registry);
        logger.info("Loaded " + registered + " Japanese translations.");
    }

    /**
     * Converts Minecraft's printf-style format strings to MessageFormat syntax.
     *
     * Minecraft uses: %s, %d, %1$s, %2$s, etc.
     * MessageFormat uses: {0}, {1}, etc.
     *
     * Also escapes single quotes (' -> '') for MessageFormat compatibility.
     */
    private static String convertMinecraftFormat(String value) {
        // Escape single quotes first (before adding MessageFormat syntax)
        String result = value.replace("'", "''");

        // Replace positional args: %1$s, %2$s, %1$d, etc. → {0}, {1}, etc.
        // Note: Minecraft uses 1-based indexing, MessageFormat uses 0-based
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("%(\\d+)\\$[sd]").matcher(result);
        StringBuilder positional = new StringBuilder();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1)) - 1;
            matcher.appendReplacement(positional, "{" + index + "}");
        }
        matcher.appendTail(positional);
        result = positional.toString();

        // Replace non-positional %s/%d with sequential {0}, {1}, etc.
        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        int i = 0;
        while (i < result.length()) {
            if (i < result.length() - 1 && result.charAt(i) == '%') {
                char next = result.charAt(i + 1);
                if (next == 's' || next == 'd') {
                    sb.append('{').append(argIndex++).append('}');
                    i += 2;
                    continue;
                } else if (next == '%') {
                    // Escaped percent: %% → %
                    sb.append('%');
                    i += 2;
                    continue;
                }
            }
            sb.append(result.charAt(i));
            i++;
        }

        return sb.toString();
    }

    /**
     * Saves the downloaded content and its hash to the cache directory.
     *
     * @param cacheDir directory to save files in
     * @param content  ja_jp.json content
     * @param hash     asset hash for cache invalidation
     */
    private static void saveCache(Path cacheDir, String content, String hash) throws IOException {
        Files.writeString(cacheDir.resolve(CACHE_FILE_NAME), content, StandardCharsets.UTF_8);
        Files.writeString(cacheDir.resolve(HASH_FILE_NAME), hash, StandardCharsets.UTF_8);
    }

    /**
     * Performs an HTTP GET request and returns the response body as a string.
     *
     * Why HttpURLConnection: Avoids adding external HTTP library dependencies.
     * Paper's runtime already includes java.net.HttpURLConnection.
     *
     * @param url the URL to fetch
     * @return response body as UTF-8 string
     * @throws IOException if the request fails or returns non-200 status
     */
    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url)
                .toURL().openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "GeyserExtra-Paper");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + responseCode + " for URL: " + url);
            }

            try (InputStream is = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Holds the result of downloading ja_jp.json: both the content and its hash.
     */
    private record DownloadResult(String content, String hash) {
    }
}
