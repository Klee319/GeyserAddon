package com.geyserextra.paper.pack;

import org.bukkit.Server;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves the operator's Java edition resource pack into an on-disk
 * unzipped directory that {@link JavaPackReader} can scan.
 *
 * <p>Supports three input shapes, tried in priority order:
 * <ol>
 *   <li><b>Explicit directory</b>: {@code javaResourcePackPath} config
 *       points at an unzipped pack folder. Used verbatim — fastest path,
 *       no extraction.</li>
 *   <li><b>Explicit ZIP</b>: {@code javaResourcePackPath} ends in
 *       {@code .zip}. The ZIP is extracted into a managed cache directory
 *       and that directory is returned. Re-extraction happens on every
 *       resolve to reflect any operator edits to the ZIP.</li>
 *   <li><b>server.properties auto-fetch</b>: when the explicit config is
 *       blank, falls back to {@code Server#getResourcePack()}. The pack
 *       is downloaded into a managed cache (keyed by the
 *       {@code resource-pack-sha1} hash so unchanged packs reuse the
 *       cached copy), then extracted as above.</li>
 * </ol>
 * Explicit config beats the URL because operators set the config field
 * specifically to override the server.properties URL (e.g. to point at a
 * local Builder's-edition pack while still serving Java players a
 * different pack via URL).</p>
 *
 * <p>Returns {@link Optional#empty()} when none of the three paths
 * resolves to a usable directory. Errors during download or extraction
 * are logged as warnings and translated to {@code empty()} so the
 * pack-build pipeline can continue with the vanilla-only fallback.</p>
 */
public final class JavaPackResolver {

    /** Cache subdirectory under the plugin data folder. */
    private static final String CACHE_DIR_NAME = "cache";

    /** File name for the downloaded server resource pack ZIP. */
    private static final String SERVER_PACK_FILE = "server-resource-pack.zip";

    /** Sidecar file storing the SHA-1 of {@link #SERVER_PACK_FILE} for re-download decisions. */
    private static final String SERVER_PACK_HASH_FILE = "server-resource-pack.sha1";

    /** Base directory name for per-source extract folders. One subdir per configured pack. */
    private static final String EXTRACT_DIR_NAME = "java-pack-extracted";

    /** Network timeouts kept short so a slow / down URL host never blocks server startup. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final List<String> configuredPaths;
    private final Server server;
    private final Path pluginDataFolder;
    private final Logger logger;

    /**
     * Convenience constructor for callers with a single configured path.
     * Equivalent to passing {@code List.of(configuredPath)} (or an empty
     * list when {@code configuredPath} is {@code null} / blank).
     */
    public JavaPackResolver(
        String configuredPath,
        Server server,
        Path pluginDataFolder,
        Logger logger
    ) {
        this(toSingletonList(configuredPath), server, pluginDataFolder, logger);
    }

    /**
     * @param configuredPaths  raw values of
     *                          {@code customItems.javaResourcePackPath} +
     *                          {@code customItems.javaResourcePackPaths},
     *                          already concatenated and blank-filtered by the
     *                          caller (see
     *                          {@link com.geyserextra.core.config.GeyserExtraConfig.CustomItemsConfig#effectiveJavaResourcePackPaths()})
     * @param server            Bukkit server instance for reading
     *                          {@code server.properties} values
     * @param pluginDataFolder  plugin's data folder; cache lives under
     *                          {@code <dataFolder>/cache}
     * @param logger            plugin logger for warnings / info
     */
    public JavaPackResolver(
        List<String> configuredPaths,
        Server server,
        Path pluginDataFolder,
        Logger logger
    ) {
        this.configuredPaths = configuredPaths != null
            ? List.copyOf(configuredPaths)
            : Collections.emptyList();
        this.server = server;
        this.pluginDataFolder = pluginDataFolder;
        this.logger = logger;
    }

    private static List<String> toSingletonList(String single) {
        return (single != null && !single.isBlank())
            ? List.of(single)
            : Collections.emptyList();
    }

    /**
     * Returns the first explicit-config path that resolves, or the
     * server.properties auto-fetch when no explicit path is configured /
     * usable.
     *
     * <p>Kept for backwards compatibility with callers that don't need
     * multi-pack merging. Prefer {@link #resolveAll()} for the merged
     * scan pipeline introduced for multi-plugin servers.</p>
     */
    public Optional<Path> resolve() {
        List<Path> all = resolveAll();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Resolves <i>every</i> configured pack, returning the absolute path of
     * each unzipped pack root in declaration order. Used by the multi-pack
     * scan pipeline so plugins that each ship their own resource pack
     * (ValhallaMMO + ItemsAdder + MMOItems + …) can be merged into a single
     * Bedrock auto-pack.
     *
     * <p>Resolution priority:
     * <ol>
     *   <li>Each entry of the configured-paths list, in declaration order.
     *       Unresolvable entries (missing file, neither dir nor .zip,
     *       extract failure) are logged and skipped.</li>
     *   <li>When the list is empty <b>or</b> nothing in the list resolved,
     *       the {@code server.properties} {@code resource-pack} URL is
     *       auto-fetched as the single fallback source.</li>
     * </ol>
     * The "auto-fetch only when nothing explicit resolves" rule matches the
     * pre-multi-pack behaviour: operators who set the explicit field meant
     * to override the URL, and shouldn't suddenly get the URL pack merged
     * on top.</p>
     *
     * <p>Returns an empty list when neither path produces a usable pack.</p>
     */
    public List<Path> resolveAll() {
        List<Path> out = new ArrayList<>();
        if (!configuredPaths.isEmpty()) {
            int idx = 0;
            for (String raw : configuredPaths) {
                Path resolved = resolveExplicitConfig(raw, idx);
                if (resolved != null) {
                    out.add(resolved);
                }
                idx++;
            }
        }
        if (out.isEmpty()) {
            // Only fall back to server.properties when no explicit pack was
            // resolved. Mixing the URL pack into an explicit-list scan would
            // surprise operators who specifically configured the list.
            Path autoFetched = resolveFromServerProperties();
            if (autoFetched != null) {
                out.add(autoFetched);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Honours a single {@code javaResourcePackPath(s)} config entry. Returns
     * {@code null} when the field is blank, the resolved path is missing,
     * the path escapes the plugin data folder (for relative inputs), or
     * the file is neither a directory nor a {@code .zip}.
     *
     * @param raw   the raw config value (relative or absolute path, possibly .zip)
     * @param index zero-based position in the configured list — used to give
     *              each pack its own extract subdirectory so multiple ZIPs
     *              don't clobber each other on disk during multi-pack scans
     */
    private Path resolveExplicitConfig(String raw, int index) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path rawCandidate = Path.of(raw);
        boolean wasRelative = !rawCandidate.isAbsolute();
        Path candidate = wasRelative
            ? pluginDataFolder.resolve(rawCandidate)
            : rawCandidate;
        Path normalized = candidate.toAbsolutePath().normalize();

        if (wasRelative) {
            // Path traversal guard: a relative input must stay inside the
            // plugin data folder once resolved + normalized. Absolute paths
            // are accepted verbatim because the operator typed them
            // explicitly and is trusted to know where they pointed.
            Path pluginRoot = pluginDataFolder.toAbsolutePath().normalize();
            if (!normalized.startsWith(pluginRoot)) {
                logger.warning("[JavaPack] relative javaResourcePackPath escapes "
                    + "plugin data folder: " + raw
                    + " (resolved to " + normalized + ") — ignoring.");
                return null;
            }
        }

        if (Files.isDirectory(normalized)) {
            return normalized;
        }

        if (Files.isRegularFile(normalized)
            && raw.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            // Per-pack extract subdir keyed by list index so multi-pack
            // scans don't share an extract root; collisions would silently
            // erase the previous pack's files mid-scan.
            return extractZip(normalized, "config:" + raw,
                EXTRACT_DIR_NAME + "-config-" + index);
        }

        logger.warning("[JavaPack] configured javaResourcePackPath is neither a "
            + "directory nor a .zip file: " + normalized + " — skipping this entry.");
        return null;
    }

    /**
     * Reads {@code server.properties} via the Bukkit Server API, downloads
     * the pack (re-using the cache when the SHA-1 matches), and extracts
     * it into the managed cache directory. Returns {@code null} on any
     * failure (no URL, download failed, extraction failed).
     */
    private Path resolveFromServerProperties() {
        String url = safeGet(server::getResourcePack);
        if (url == null || url.isBlank()) {
            return null;
        }
        String expectedHash = safeGet(server::getResourcePackHash);
        Path cacheZip = cacheFile(SERVER_PACK_FILE);
        Path hashSidecar = cacheFile(SERVER_PACK_HASH_FILE);

        try {
            Files.createDirectories(cacheZip.getParent());
            if (needsDownload(cacheZip, hashSidecar, expectedHash)) {
                logger.info("[JavaPack] downloading server resource pack from " + url);
                downloadTo(url, cacheZip);
                String actualHash = computeSha1(cacheZip);
                Files.writeString(hashSidecar, actualHash, StandardCharsets.US_ASCII);
                logger.info("[JavaPack] cached server resource pack ("
                    + Files.size(cacheZip) + " bytes, sha1=" + actualHash + ")");
            } else {
                logger.info("[JavaPack] reusing cached server resource pack (sha1 unchanged)");
            }
        } catch (IOException ex) {
            logger.warning("[JavaPack] server resource pack auto-fetch failed: "
                + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return null;
        }

        return extractZip(cacheZip, "server.properties:" + url,
            EXTRACT_DIR_NAME + "-server");
    }

    /**
     * Whether the cached ZIP needs a fresh download. Re-downloads on any
     * of: missing cache file, no expected hash (server.properties has no
     * SHA-1, so we can't verify staleness — re-download to be safe),
     * missing hash sidecar, or hash mismatch.
     */
    private boolean needsDownload(Path cacheZip, Path hashSidecar, String expectedHash) {
        if (!Files.isRegularFile(cacheZip)) return true;
        if (expectedHash == null || expectedHash.isBlank()) return true;
        if (!Files.isRegularFile(hashSidecar)) return true;
        try {
            String cached = Files.readString(hashSidecar, StandardCharsets.US_ASCII).trim();
            return !cached.equalsIgnoreCase(expectedHash);
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Downloads {@code url} into {@code target} using the JDK HTTP client.
     * Throws on HTTP error or transport failure; callers translate to
     * "fall back to vanilla-only mode".
     */
    private void downloadTo(String url, Path target) throws IOException {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
        try {
            HttpResponse<Path> res = client.send(req,
                HttpResponse.BodyHandlers.ofFile(target));
            if (res.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + res.statusCode() + " from " + url);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", e);
        }
    }

    /**
     * Extracts {@code zipFile} into the named extract subdirectory and
     * returns the extract root. Re-creates the directory from scratch on
     * every call so stale files from a previous pack don't linger.
     *
     * <p>Each call uses its own {@code subdirName} so multi-pack scans
     * don't share an extract root (and accidentally erase each other's
     * files mid-scan).</p>
     *
     * <p>Path-traversal entries ({@code ../} or absolute paths) are
     * silently skipped per OWASP Zip Slip guidance.</p>
     */
    private Path extractZip(Path zipFile, String sourceLabel, String subdirName) {
        Path extractDir = cacheFile(subdirName);
        try {
            deleteRecursive(extractDir);
            Files.createDirectories(extractDir);

            int extracted = 0;
            int skipped = 0;
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name == null || name.isBlank()) {
                        skipped++;
                        continue;
                    }
                    Path target = extractDir.resolve(name).normalize();
                    if (!target.startsWith(extractDir)) {
                        // Zip-slip protection: refuse entries that resolve
                        // outside the extract root (e.g. "../etc/passwd").
                        skipped++;
                        continue;
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Path parent = target.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                        extracted++;
                    }
                    zis.closeEntry();
                }
            }
            logger.info("[JavaPack] extracted " + extracted + " files from "
                + sourceLabel + (skipped > 0 ? " (skipped " + skipped + " unsafe entries)" : ""));
            return extractDir;
        } catch (IOException ex) {
            logger.warning("[JavaPack] failed to extract " + zipFile + ": "
                + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * Computes the SHA-1 of {@code file} as a lowercase hex string,
     * matching the format Minecraft uses in {@code server.properties}'s
     * {@code resource-pack-sha1} field.
     */
    private static String computeSha1(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 algorithm unavailable on this JDK", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        byte[] digest = md.digest();
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b & 0xFF));
        }
        return hex.toString();
    }

    /**
     * Recursively deletes {@code dir} if it exists. Best-effort: individual
     * {@code Files.delete} failures are swallowed so a single read-only
     * file doesn't abort the entire extract.
     */
    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    private Path cacheFile(String name) {
        return pluginDataFolder.resolve(CACHE_DIR_NAME).resolve(name);
    }

    /**
     * Invokes a Bukkit Server getter inside a Throwable catch so a Paper
     * API surface change doesn't crash the resolve pipeline. Returns
     * {@code null} on any failure.
     */
    private String safeGet(java.util.function.Supplier<String> getter) {
        try {
            return getter.get();
        } catch (Throwable t) {
            if (logger != null) {
                logger.log(Level.FINE, "[JavaPack] server.properties accessor failed", t);
            }
            return null;
        }
    }
}
