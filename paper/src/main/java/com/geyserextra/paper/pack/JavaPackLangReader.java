package com.geyserextra.paper.pack;

import com.geyserextra.core.util.JsonUtil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads {@code assets/<ns>/lang/<locale>.json} files from an unzipped Java
 * resource pack and exposes their entries as a flat {@code Map<translationKey,
 * resolvedText>}. Used to resolve {@code TranslatableComponent}-style display
 * names that Bedrock cannot resolve on its own — so a CMD item whose
 * {@code ItemMeta.displayName} is a translation key (rather than literal
 * text) still surfaces as a readable name on Bedrock instead of leaking the
 * raw {@code item.mymod.fire_sword} identifier.
 *
 * <p>Resolution order on {@link #resolve(String)}:
 * <ol>
 *   <li>The configured primary locale (e.g. {@code ja_jp}).</li>
 *   <li>{@code en_us} as a universal fallback when the primary locale lacks
 *       a key. Java's own client behaves the same way.</li>
 * </ol>
 * </p>
 *
 * <p>Java lang files come in two shapes:
 * <ul>
 *   <li>Flat: {@code {"item.mymod.fire_sword": "Fire Sword"}}</li>
 *   <li>Nested: {@code {"item": {"mymod": {"fire_sword": "Fire Sword"}}}}</li>
 * </ul>
 * Both are flattened into the same dot-joined key format.</p>
 *
 * <p>The reader is read-only and side-effect free; build it once at startup
 * and share the instance.</p>
 */
public final class JavaPackLangReader {

    /** Final-fallback locale; matches Mojang's own resolution chain. */
    public static final String FALLBACK_LOCALE = "en_us";

    /** Composite map: locale -> (key -> resolved text). */
    private final Map<String, Map<String, String>> entriesByLocale;
    private final String primaryLocale;
    private final Logger logger;

    private JavaPackLangReader(
        Map<String, Map<String, String>> entriesByLocale,
        String primaryLocale,
        Logger logger
    ) {
        this.entriesByLocale = entriesByLocale;
        this.primaryLocale = primaryLocale.toLowerCase();
        this.logger = logger;
    }

    /**
     * Returns an empty reader that resolves nothing — used as a no-op when
     * no Java pack is configured. Lookup callers can stay loop-free; an
     * empty reader simply returns null for every key.
     */
    public static JavaPackLangReader empty() {
        return new JavaPackLangReader(Map.of(), FALLBACK_LOCALE, null);
    }

    /**
     * Walks the pack and loads every {@code assets/<ns>/lang/*.json} file
     * found. Errors on individual files are logged and skipped so a single
     * malformed translation file cannot abort the entire scan.
     *
     * @param packRoot      unzipped Java pack root (must contain {@code assets/})
     * @param primaryLocale primary locale to prefer at resolve time, e.g.
     *                      {@code ja_jp}; case-insensitive
     * @param logger        plugin logger
     * @param debug         whether to emit verbose per-file logs
     */
    public static JavaPackLangReader load(
        Path packRoot,
        String primaryLocale,
        Logger logger,
        boolean debug
    ) {
        Objects.requireNonNull(packRoot, "packRoot");
        Objects.requireNonNull(logger, "logger");
        if (primaryLocale == null || primaryLocale.isBlank()) {
            primaryLocale = FALLBACK_LOCALE;
        }
        primaryLocale = primaryLocale.toLowerCase();

        if (!Files.isDirectory(packRoot)) {
            return new JavaPackLangReader(Map.of(), primaryLocale, logger);
        }
        Path assets = packRoot.resolve("assets");
        if (!Files.isDirectory(assets)) {
            return new JavaPackLangReader(Map.of(), primaryLocale, logger);
        }

        Map<String, Map<String, String>> byLocale = new HashMap<>();
        int totalKeys = 0;

        for (Path namespaceDir : listSubdirectories(assets, logger)) {
            Path langDir = namespaceDir.resolve("lang");
            if (!Files.isDirectory(langDir)) {
                continue;
            }
            for (Path langFile : listJsonFiles(langDir, logger)) {
                String fileName = langFile.getFileName().toString();
                String locale = fileName.substring(0, fileName.length() - ".json".length()).toLowerCase();
                Map<String, String> bucket = byLocale.computeIfAbsent(locale, k -> new HashMap<>());
                try {
                    int added = loadFlattened(langFile, bucket);
                    totalKeys += added;
                    if (debug && added > 0) {
                        logger.fine("[JavaPackLang] loaded " + added + " keys from "
                            + langFile.getFileName());
                    }
                } catch (Exception ex) {
                    logger.log(Level.WARNING,
                        "[JavaPackLang] failed to parse " + langFile + ": " + ex.getMessage());
                }
            }
        }

        logger.fine("[JavaPackLang] loaded " + totalKeys + " translation keys across "
            + byLocale.size() + " locale file(s) (primary locale: " + primaryLocale + ")");
        return new JavaPackLangReader(byLocale, primaryLocale, logger);
    }

    /**
     * Resolves a translation key to its localized string.
     *
     * <p>Returns {@code null} when the key is unknown in both the primary
     * locale and {@code en_us}. Callers should treat {@code null} as "no
     * resolution available, use upstream fallback" rather than "translation
     * is empty"; an empty string in the lang file is preserved as-is.</p>
     *
     * @param translationKey e.g. {@code "item.mymod.fire_sword"}; null or blank
     *                       returns null immediately
     * @return the resolved string, or {@code null} when not found
     */
    public String resolve(String translationKey) {
        if (translationKey == null || translationKey.isBlank()) {
            return null;
        }
        Map<String, String> primary = entriesByLocale.get(primaryLocale);
        if (primary != null) {
            String hit = primary.get(translationKey);
            if (hit != null) {
                return hit;
            }
        }
        if (!FALLBACK_LOCALE.equals(primaryLocale)) {
            Map<String, String> fallback = entriesByLocale.get(FALLBACK_LOCALE);
            if (fallback != null) {
                return fallback.get(translationKey);
            }
        }
        return null;
    }

    /**
     * Returns true when the reader has no entries at all — used to short-
     * circuit resolution paths without paying for two map lookups.
     */
    public boolean isEmpty() {
        return entriesByLocale.isEmpty();
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    /**
     * Reads a lang JSON and flattens it into {@code out}. Returns the count
     * of newly added keys.
     */
    @SuppressWarnings("unchecked")
    private static int loadFlattened(Path langFile, Map<String, String> out) throws IOException {
        String content = Files.readString(langFile);
        Object parsed = JsonUtil.fromJson(content, Object.class);
        if (!(parsed instanceof Map<?, ?> map)) {
            return 0;
        }
        int before = out.size();
        flatten("", (Map<String, Object>) map, out);
        return out.size() - before;
    }

    /**
     * Recursively flattens nested JSON objects into dot-joined keys.
     * String, number, and boolean leaf values are stored as their
     * {@code toString()} representation; arrays and unsupported types are
     * skipped.
     */
    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> node, Map<String, String> out) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(key, (Map<String, Object>) nested, out);
            } else if (value instanceof String s) {
                out.put(key, s);
            } else if (value instanceof Number n) {
                out.put(key, n.toString());
            } else if (value instanceof Boolean b) {
                out.put(key, b.toString());
            }
            // null, arrays, and other types: ignored
        }
    }

    private static List<Path> listSubdirectories(Path parent, Logger logger) {
        java.util.List<Path> out = new java.util.ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, Files::isDirectory)) {
            for (Path p : stream) {
                out.add(p);
            }
        } catch (IOException ex) {
            logger.log(Level.WARNING, "[JavaPackLang] failed to list " + parent, ex);
        }
        return out;
    }

    private static List<Path> listJsonFiles(Path dir, Logger logger) {
        java.util.List<Path> out = new java.util.ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    out.add(p);
                }
            }
        } catch (IOException ex) {
            logger.log(Level.WARNING, "[JavaPackLang] failed to list " + dir, ex);
        }
        return out;
    }
}
