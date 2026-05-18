package com.geyserextra.core.config;

/**
 * Immutable entry describing a Java Edition resource pack to fetch from a URL.
 *
 * Used by {@code customItems.dynamicResourcePackUrls} in config to declare
 * remote packs that should be downloaded, extracted, and merged into the
 * Bedrock auto-pack alongside locally-resolved packs.
 *
 * <p><b>Validation policy (intentionally lenient):</b> the compact constructor
 * accepts any value for {@code url} and {@code sha1} — including {@code null}
 * and blank strings. This is deliberate: when Gson deserialises a record from
 * a malformed JSON entry (e.g. {@code {"url": null}}), throwing from the
 * canonical constructor surfaces as a {@code JsonParseException} that escapes
 * {@code GeyserExtraConfig.loadOrCreate}'s {@code IOException} catch and
 * crashes the entire plugin on enable. Instead, validity is checked
 * lazily via {@link #hasValidUrl()} and invalid entries are filtered out
 * inside {@code GeyserExtraConfig.CustomItemsConfig} before they reach
 * {@code JavaPackResolver}.</p>
 *
 * @param url   The HTTP/HTTPS URL of the Java resource pack ZIP. May be
 *              {@code null} or blank for malformed config entries; consumers
 *              must check {@link #hasValidUrl()} before using.
 * @param sha1  Optional SHA-1 hash for cache validation. May be {@code null}
 *              or blank, in which case a TTL-based cache strategy is used.
 */
public record DynamicResourcePackEntry(String url, String sha1) {

    /**
     * Returns true when this entry carries a non-null, non-blank URL and is
     * therefore safe to feed to the resolver. Invalid entries should be
     * dropped at config-load time.
     */
    public boolean hasValidUrl() {
        return url != null && !url.isBlank();
    }

    /**
     * Returns true when a SHA-1 hash is present and non-blank.
     * Callers should fall back to TTL-based cache validation otherwise.
     */
    public boolean hasSha1() {
        return sha1 != null && !sha1.isBlank();
    }
}
