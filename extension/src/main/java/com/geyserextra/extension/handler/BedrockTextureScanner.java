/*
 * GeyserExtra Extension - Bedrock Texture Scanner
 * Scans BE resource packs to collect available texture keys from item_texture.json.
 */
package com.geyserextra.extension.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.geyser.api.extension.ExtensionLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Scans Bedrock Edition resource packs to extract texture key names
 * from item_texture.json files.
 *
 * Why: Geyser assigns model IDs to all registered custom items. Items without
 * corresponding BE textures will override vanilla textures with blank/missing
 * visuals. This scanner enables filtering out such items before registration.
 */
public final class BedrockTextureScanner {

    /** Path within a resource pack to the item texture definition file */
    private static final String ITEM_TEXTURE_JSON_PATH = "textures/item_texture.json";

    /** JSON key containing the texture name-to-path mappings */
    private static final String TEXTURE_DATA_KEY = "texture_data";

    /** Supported archive file extensions for resource packs */
    private static final List<String> SUPPORTED_PACK_EXTENSIONS = List.of(".zip", ".mcpack");

    // Why: Utility class - no instantiation needed
    private BedrockTextureScanner() {}

    /**
     * Scans all resource packs under the given directory and collects texture keys.
     *
     * Supports three pack formats:
     * - Directory packs (containing textures/item_texture.json directly)
     * - ZIP archives (.zip)
     * - Bedrock pack archives (.mcpack)
     *
     * @param packsPath the directory containing resource packs
     * @param logger    the extension logger for warnings/info
     * @return an unmodifiable set of all discovered texture keys, empty if none found
     */
    public static Set<String> scanTextureKeys(Path packsPath, ExtensionLogger logger) {
        if (packsPath == null) {
            return Collections.emptySet();
        }

        if (!Files.exists(packsPath)) {
            logger.warning("Bedrock packs path does not exist: " + packsPath);
            return Collections.emptySet();
        }

        Set<String> allKeys = new HashSet<>();

        // Why: The path itself might be a single pack directory
        if (isDirectoryPack(packsPath)) {
            allKeys.addAll(scanDirectoryPack(packsPath, logger));
            return Collections.unmodifiableSet(allKeys);
        }

        // Why: The path might be a directory containing multiple packs
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packsPath)) {
            for (Path entry : stream) {
                Set<String> keys = scanSingleEntry(entry, logger);
                allKeys.addAll(keys);
            }
        } catch (IOException e) {
            logger.warning("Failed to scan packs directory: " + packsPath
                + " - " + e.getMessage());
        }

        logger.info("Bedrock texture scan complete: " + allKeys.size()
            + " texture keys found from " + packsPath);
        return Collections.unmodifiableSet(allKeys);
    }

    /**
     * Determines the pack type of a single entry and dispatches to the
     * appropriate scanner method.
     */
    private static Set<String> scanSingleEntry(Path entry, ExtensionLogger logger) {
        if (Files.isDirectory(entry) && isDirectoryPack(entry)) {
            return scanDirectoryPack(entry, logger);
        }

        if (Files.isRegularFile(entry) && isArchivePack(entry)) {
            return scanArchivePack(entry, logger);
        }

        return Collections.emptySet();
    }

    /**
     * Checks whether a directory contains item_texture.json at the expected path.
     */
    private static boolean isDirectoryPack(Path dir) {
        return Files.exists(dir.resolve(ITEM_TEXTURE_JSON_PATH));
    }

    /**
     * Checks whether a file has a supported archive extension.
     */
    private static boolean isArchivePack(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        return SUPPORTED_PACK_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    /**
     * Scans a directory-format resource pack for texture keys.
     *
     * @param packDir the root directory of the pack
     * @param logger  the extension logger
     * @return set of texture keys found
     */
    private static Set<String> scanDirectoryPack(Path packDir, ExtensionLogger logger) {
        Path textureFile = packDir.resolve(ITEM_TEXTURE_JSON_PATH);

        if (!Files.exists(textureFile)) {
            return Collections.emptySet();
        }

        try {
            String content = Files.readString(textureFile, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            Set<String> keys = extractTextureKeys(root);
            logger.info("Scanned directory pack '" + packDir.getFileName()
                + "': " + keys.size() + " texture keys");
            return keys;
        } catch (IOException e) {
            logger.warning("Failed to read item_texture.json from directory pack '"
                + packDir.getFileName() + "': " + e.getMessage());
            return Collections.emptySet();
        } catch (Exception e) {
            logger.warning("Failed to parse item_texture.json from directory pack '"
                + packDir.getFileName() + "': " + e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Scans a ZIP/mcpack archive resource pack for texture keys.
     *
     * @param archiveFile the archive file path
     * @param logger      the extension logger
     * @return set of texture keys found
     */
    private static Set<String> scanArchivePack(Path archiveFile, ExtensionLogger logger) {
        try (ZipFile zipFile = new ZipFile(archiveFile.toFile())) {
            ZipEntry entry = findItemTextureEntry(zipFile);

            if (entry == null) {
                return Collections.emptySet();
            }

            String content = readZipEntryContent(zipFile, entry);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            Set<String> keys = extractTextureKeys(root);
            logger.info("Scanned archive pack '" + archiveFile.getFileName()
                + "': " + keys.size() + " texture keys");
            return keys;
        } catch (IOException e) {
            logger.warning("Failed to read archive pack '"
                + archiveFile.getFileName() + "': " + e.getMessage());
            return Collections.emptySet();
        } catch (Exception e) {
            logger.warning("Failed to parse archive pack '"
                + archiveFile.getFileName() + "': " + e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Finds the item_texture.json entry within a ZIP file.
     * Handles both root-level and nested directory structures.
     *
     * Why: Some packs have item_texture.json at root level of ZIP,
     * others nest it inside a subdirectory.
     */
    private static ZipEntry findItemTextureEntry(ZipFile zipFile) {
        // Why: Try exact path first (most common case)
        ZipEntry direct = zipFile.getEntry(ITEM_TEXTURE_JSON_PATH);
        if (direct != null) {
            return direct;
        }

        // Why: Some packs nest contents inside a subdirectory
        return zipFile.stream()
            .filter(e -> e.getName().endsWith(ITEM_TEXTURE_JSON_PATH))
            .findFirst()
            .orElse(null);
    }

    /**
     * Reads the full content of a ZIP entry as a UTF-8 string.
     */
    private static String readZipEntryContent(ZipFile zipFile, ZipEntry entry)
            throws IOException {
        try (InputStream is = zipFile.getInputStream(entry);
             BufferedReader reader = new BufferedReader(
                 new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    /**
     * Extracts texture key names from a parsed item_texture.json root object.
     *
     * Expected structure:
     * {
     *   "texture_data": {
     *     "geyser_custom_my_item": { "textures": "..." },
     *     ...
     *   }
     * }
     *
     * @param root the parsed JSON root
     * @return set of texture key names (e.g., "geyser_custom_my_item")
     */
    private static Set<String> extractTextureKeys(JsonObject root) {
        if (!root.has(TEXTURE_DATA_KEY) || !root.get(TEXTURE_DATA_KEY).isJsonObject()) {
            return Collections.emptySet();
        }

        JsonObject textureData = root.getAsJsonObject(TEXTURE_DATA_KEY);
        Set<String> keys = new HashSet<>();

        for (Map.Entry<String, JsonElement> entry : textureData.entrySet()) {
            keys.add(entry.getKey());
        }

        return keys;
    }
}
