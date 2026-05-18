package com.geyserextra.paper.pack;

import com.geyserextra.core.config.DynamicResourcePackEntry;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Time-to-live for cache entries that have no SHA-1 to validate against
     * (typically dynamic URLs declared without a hash). After this many
     * milliseconds the resolver re-downloads the URL even if a cached copy
     * exists. 24 hours strikes a balance between operator-visible freshness
     * and not hammering remote hosts on every server restart.
     */
    private static final long DYNAMIC_URL_TTL_MILLIS = Duration.ofHours(24).toMillis();

    private final List<String> configuredPaths;
    private final List<DynamicResourcePackEntry> dynamicUrlEntries;
    private final Server server;
    private final Path pluginDataFolder;
    private final Logger logger;
    /**
     * When {@code false}, dynamic URL entries and the server.properties
     * fallback are restricted to existing cache hits: no HTTP request is
     * issued and missing-cache entries are skipped silently. When {@code true},
     * full download/refresh behaviour is permitted. Use {@code false} from
     * the primary thread to avoid stalling startup/shutdown on a slow remote;
     * the periodic async save task uses {@code true} so cached entries stay
     * fresh.
     */
    private final boolean allowNetwork;

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
        this(toSingletonList(configuredPath), Collections.emptyList(),
             server, pluginDataFolder, logger, true);
    }

    /**
     * Backward-compatible 4-arg constructor (pre-Phase-2 shape). Delegates to
     * the canonical constructor with an empty dynamic-URL list and
     * {@code allowNetwork=true}, so call sites that haven't been updated
     * continue to work and resolve only configured paths + the server.properties
     * fallback (with network permitted).
     */
    public JavaPackResolver(
        List<String> configuredPaths,
        Server server,
        Path pluginDataFolder,
        Logger logger
    ) {
        this(configuredPaths, Collections.emptyList(),
             server, pluginDataFolder, logger, true);
    }

    /**
     * Backward-compatible 5-arg constructor (pre-V3 shape). Delegates to the
     * canonical 6-arg constructor with {@code allowNetwork=true} so call sites
     * that don't yet route through the primary-thread check keep working with
     * full network behaviour.
     */
    public JavaPackResolver(
        List<String> configuredPaths,
        List<DynamicResourcePackEntry> dynamicUrlEntries,
        Server server,
        Path pluginDataFolder,
        Logger logger
    ) {
        this(configuredPaths, dynamicUrlEntries,
             server, pluginDataFolder, logger, true);
    }

    /**
     * Canonical constructor including the Phase 2 dynamic URL list and the
     * Phase-V3 {@code allowNetwork} flag.
     *
     * @param configuredPaths     raw values of
     *                            {@code customItems.javaResourcePackPath} +
     *                            {@code customItems.javaResourcePackPaths},
     *                            already concatenated and blank-filtered by the
     *                            caller (see
     *                            {@link com.geyserextra.core.config.GeyserExtraConfig.CustomItemsConfig#effectiveJavaResourcePackPaths()})
     * @param dynamicUrlEntries   Phase 2: remote pack URLs from
     *                            {@code customItems.dynamicResourcePackUrls}.
     *                            Each entry is downloaded, cached, and extracted
     *                            into the managed cache directory. Empty list
     *                            preserves pre-Phase-2 behaviour bit-for-bit.
     * @param server              Bukkit server instance for reading
     *                            {@code server.properties} values
     * @param pluginDataFolder    plugin's data folder; cache lives under
     *                            {@code <dataFolder>/cache}
     * @param logger              plugin logger for warnings / info
     * @param allowNetwork        when {@code false}, no HTTP request is issued;
     *                            URL/server.properties entries reuse cache or
     *                            skip. Used to keep primary-thread invocations
     *                            non-blocking.
     */
    public JavaPackResolver(
        List<String> configuredPaths,
        List<DynamicResourcePackEntry> dynamicUrlEntries,
        Server server,
        Path pluginDataFolder,
        Logger logger,
        boolean allowNetwork
    ) {
        this.configuredPaths = configuredPaths != null
            ? List.copyOf(configuredPaths)
            : Collections.emptyList();
        this.dynamicUrlEntries = dynamicUrlEntries != null
            ? List.copyOf(dynamicUrlEntries)
            : Collections.emptyList();
        this.server = server;
        this.pluginDataFolder = pluginDataFolder;
        this.logger = logger;
        this.allowNetwork = allowNetwork;
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

        // Step 1: explicit local paths (unzipped dirs or .zip files).
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

        // Step 2: dynamic URL packs (Phase 2). Placed AFTER configuredPaths so
        // the auto-pack merge in saveRegistriesToSharedFolder (which uses
        // putAll with later-wins semantics) treats URL-hosted packs as the
        // authoritative copy. Operators who set both should expect the remote
        // pack to override their local copy.
        if (!dynamicUrlEntries.isEmpty()) {
            Set<String> seenUrls = new HashSet<>();
            int dynIdx = 0;
            for (DynamicResourcePackEntry entry : dynamicUrlEntries) {
                if (entry == null) {
                    dynIdx++;
                    continue;
                }
                String url = entry.url();
                if (url == null || url.isBlank()) {
                    dynIdx++;
                    continue;
                }
                if (!seenUrls.add(url)) {
                    logger.warning("[JavaPack] dynamic URL listed twice; skipping duplicate: " + url);
                    dynIdx++;
                    continue;
                }
                Path resolved = resolveFromDynamicEntry(entry, dynIdx);
                if (resolved != null) {
                    out.add(resolved);
                }
                dynIdx++;
            }
        }

        // Step 3: server.properties fallback — only when neither configured
        // paths nor dynamic URLs produced a usable pack. The fallback rule
        // mirrors the pre-Phase-2 behaviour: operators who set the explicit
        // sources meant to override the server.properties URL, not augment it.
        if (out.isEmpty()) {
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
     * Reads {@code server.properties} via the Bukkit Server API and routes
     * the URL through {@link #resolveFromUrl(String, String, String, String, String, long, String)}.
     * Cache file names are kept identical to the pre-Phase-2 build so existing
     * cache directories continue to work after upgrade.
     */
    private Path resolveFromServerProperties() {
        String url = safeGet(server::getResourcePack);
        if (url == null || url.isBlank()) {
            return null;
        }
        String expectedHash = safeGet(server::getResourcePackHash);
        return resolveFromUrl(
            url,
            expectedHash,
            "server.properties:" + url,
            SERVER_PACK_FILE,
            SERVER_PACK_HASH_FILE,
            0L,                              // server.properties has no TTL fallback: re-download whenever the hash is unset
            EXTRACT_DIR_NAME + "-server");
    }

    /**
     * Routes a single {@link DynamicResourcePackEntry} (Phase 2: config-driven
     * remote pack) through the same download / cache / extract pipeline as
     * the server.properties fallback. Each URL gets its own cache filename
     * derived from a short SHA-256 hash so multiple URLs don't clobber each
     * other on disk.
     *
     * @param entry the URL + optional SHA-1 declaration from config
     * @param index zero-based position in {@code dynamicUrlEntries} (currently
     *              only used in log lines; cache keys are URL-hash based)
     */
    private Path resolveFromDynamicEntry(DynamicResourcePackEntry entry, int index) {
        String url = entry.url();
        String sha1 = entry.hasSha1() ? entry.sha1() : null;
        String urlHashShort = shortHashForUrl(url);
        return resolveFromUrl(
            url,
            sha1,
            "config.dynamicResourcePackUrls[" + index + "]:" + url,
            "url-" + urlHashShort + ".zip",
            "url-" + urlHashShort + ".sha1",
            DYNAMIC_URL_TTL_MILLIS,           // re-download after 24h when no SHA-1 is provided
            EXTRACT_DIR_NAME + "-url-" + urlHashShort);
    }

    /**
     * Shared download → cache → extract pipeline used by both
     * {@link #resolveFromServerProperties()} and
     * {@link #resolveFromDynamicEntry(DynamicResourcePackEntry, int)}.
     *
     * <p>Returns {@code null} on any failure (HTTP error, SHA-1 mismatch,
     * ZIP parsing error, I/O write failure) so the rest of the resolve
     * pipeline can continue without the offending pack.</p>
     *
     * @param url             the HTTP/HTTPS URL to download
     * @param expectedSha1    optional SHA-1 hash for validation (lowercase
     *                        hex). Null/blank means no hash validation;
     *                        {@code ttlMillis} drives cache freshness instead.
     * @param sourceLabel     human-readable label written into the extract log
     * @param cacheZipName    file name inside {@code cache/} where the ZIP is
     *                        stored (must be unique across all URLs scanned in
     *                        a single resolve cycle so packs don't clobber)
     * @param cacheHashName   file name inside {@code cache/} for the SHA-1
     *                        sidecar (must be paired with {@code cacheZipName})
     * @param ttlMillis       cache-freshness window in milliseconds, used only
     *                        when {@code expectedSha1} is null/blank. Pass 0 to
     *                        force a re-download every resolve cycle.
     * @param extractSubdir   subdirectory name inside {@code cache/} for the
     *                        extracted contents (must be unique per URL).
     */
    private Path resolveFromUrl(
        String url,
        String expectedSha1,
        String sourceLabel,
        String cacheZipName,
        String cacheHashName,
        long ttlMillis,
        String extractSubdir
    ) {
        Path cacheZip = cacheFile(cacheZipName);
        Path hashSidecar = cacheFile(cacheHashName);
        Path partial = cacheZip.resolveSibling(cacheZip.getFileName().toString() + ".partial");

        try {
            Files.createDirectories(cacheZip.getParent());
            if (needsDownload(cacheZip, hashSidecar, expectedSha1, ttlMillis)) {
                // V3 primary-thread guard: when network is disallowed (because
                // we're on the main server thread), do not issue an HTTP
                // request. Serve any existing cache via the stale path below;
                // otherwise return null so the periodic async task can refresh
                // the cache later without blocking startup/shutdown here.
                if (!allowNetwork) {
                    if (Files.isRegularFile(cacheZip)) {
                        logger.fine("[JavaPack] network disabled on primary thread; "
                            + "serving previous cache for " + sourceLabel);
                        return extractZip(cacheZip, sourceLabel + " (no-network)", extractSubdir);
                    }
                    logger.info("[JavaPack] network disabled on primary thread; "
                        + "skipping " + sourceLabel + " until next async refresh");
                    return null;
                }
                logger.info("[JavaPack] downloading resource pack from " + url);

                // Phase: download into a .partial sibling first so a half-written
                // body never replaces the previous good cache file. Without this,
                // HttpResponse.BodyHandlers.ofFile streams the response body to
                // the target *before* the status code is checked, which means a
                // non-2xx error page would have already overwritten cacheZip and
                // poisoned the 24h TTL cache for dynamic URLs.
                try {
                    downloadTo(url, partial);
                } catch (IOException downloadEx) {
                    // Clean up the half-written .partial; keep cacheZip untouched
                    // so the resolver can still serve the previous good copy on
                    // the next cycle (stale-if-error semantics).
                    deleteQuietly(partial);
                    throw downloadEx;
                }

                String actualHash;
                try {
                    actualHash = computeSha1(partial);
                } catch (IOException hashEx) {
                    deleteQuietly(partial);
                    throw hashEx;
                }

                // Hash mismatch: refuse to promote the .partial. The previous
                // good cacheZip is preserved; next resolve cycle will retry.
                if (expectedSha1 != null && !expectedSha1.isBlank()
                    && !actualHash.equalsIgnoreCase(expectedSha1)) {
                    logger.warning("[JavaPack] SHA-1 mismatch for " + url
                        + " (expected=" + expectedSha1 + ", actual=" + actualHash
                        + ") — discarding download; previous cache (if any) kept.");
                    deleteQuietly(partial);
                    return Files.isRegularFile(cacheZip)
                        ? extractZip(cacheZip, sourceLabel + " (mismatch fallback)", extractSubdir)
                        : null;
                }

                // Atomic promote: move .partial to cacheZip, write the sidecar
                // last. If the move fails, cacheZip is unchanged.
                try {
                    Files.move(partial, cacheZip,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicEx) {
                    // Some filesystems (cross-mount, certain Windows network shares)
                    // refuse ATOMIC_MOVE. Fall back to REPLACE_EXISTING — at this
                    // point the .partial has already been validated, so a non-atomic
                    // replacement is still safe.
                    Files.move(partial, cacheZip, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(hashSidecar, actualHash, StandardCharsets.US_ASCII);
                logger.info("[JavaPack] cached " + sourceLabel + " ("
                    + Files.size(cacheZip) + " bytes, sha1=" + actualHash + ")");
            } else {
                logger.info("[JavaPack] reusing cached pack " + sourceLabel
                    + " (no fresh download needed)");
            }
        } catch (IOException ex) {
            // .partial was deleted in the inner catches; ensure no leftover.
            deleteQuietly(partial);
            logger.warning("[JavaPack] resource pack fetch failed for " + url + ": "
                + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            // Stale-if-error: if a previous cacheZip still exists, serve it so
            // a transient network failure does not blank-out the pack.
            if (Files.isRegularFile(cacheZip)) {
                logger.info("[JavaPack] serving previous cached pack " + sourceLabel
                    + " due to refresh error");
                return extractZip(cacheZip, sourceLabel + " (stale-if-error)", extractSubdir);
            }
            return null;
        }

        return extractZip(cacheZip, sourceLabel, extractSubdir);
    }

    /**
     * Deletes {@code path} if it exists, swallowing only {@link IOException}.
     * Used to clean up half-written {@code .partial} files without masking
     * the underlying refresh error that triggered the cleanup.
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup; the real error is already being reported
        }
    }

    /**
     * Whether the cached ZIP needs a fresh download. Decision rules, in order:
     * <ol>
     *   <li>Cache file absent → download.</li>
     *   <li>An {@code expectedHash} is provided and the sidecar doesn't match → download.</li>
     *   <li>An {@code expectedHash} is provided and matches → reuse cache.</li>
     *   <li>No {@code expectedHash} and {@code ttlMillis > 0} → reuse cache only
     *       when the cache file is younger than {@code ttlMillis}.</li>
     *   <li>No {@code expectedHash} and {@code ttlMillis == 0} → always download
     *       (the conservative fallback path used for {@code server.properties}).</li>
     * </ol>
     *
     * <p>Overload added in Phase 2 to support TTL-based caching for dynamic
     * URLs that arrive without a SHA-1. The original two-arg semantics are
     * preserved via the {@code ttlMillis == 0} branch.</p>
     */
    private boolean needsDownload(Path cacheZip, Path hashSidecar,
                                  String expectedHash, long ttlMillis) {
        if (!Files.isRegularFile(cacheZip)) return true;

        boolean hasExpected = expectedHash != null && !expectedHash.isBlank();
        if (hasExpected) {
            if (!Files.isRegularFile(hashSidecar)) return true;
            try {
                String cached = Files.readString(hashSidecar, StandardCharsets.US_ASCII).trim();
                return !cached.equalsIgnoreCase(expectedHash);
            } catch (IOException e) {
                return true;
            }
        }

        // No expected hash. Fall back to TTL-based freshness, or force-download
        // when TTL is zero (the pre-Phase-2 server.properties behaviour).
        if (ttlMillis <= 0) {
            return true;
        }
        try {
            long age = System.currentTimeMillis()
                - Files.getLastModifiedTime(cacheZip).toMillis();
            return age >= ttlMillis;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Computes a short, filename-safe identifier for a URL by SHA-256 hashing
     * the UTF-8 bytes of the URL and taking the first 16 hex chars. Stable
     * across JVM restarts so the same URL maps to the same cache file every
     * resolve cycle.
     *
     * <p>Falls back to a {@code String.hashCode()}-based identifier if the
     * JDK is missing SHA-256 for any reason — pure defensive guard, never
     * expected to trigger on a real JVM.</p>
     */
    private static String shortHashForUrl(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xFF));
                if (hex.length() >= 16) break;
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(url.hashCode() & 0xfffffff);
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
