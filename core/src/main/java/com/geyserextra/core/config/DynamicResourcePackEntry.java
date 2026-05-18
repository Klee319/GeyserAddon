package com.geyserextra.core.config;

import java.util.Objects;

/**
 * Immutable entry describing a Java Edition resource pack to fetch from a URL.
 *
 * Used by {@code customItems.dynamicResourcePackUrls} in config to declare
 * remote packs that should be downloaded, extracted, and merged into the
 * Bedrock auto-pack alongside locally-resolved packs.
 *
 * @param url   The HTTP/HTTPS URL of the Java resource pack ZIP. Must be
 *              non-null and non-blank.
 * @param sha1  Optional SHA-1 hash for cache validation. May be {@code null}
 *              or blank, in which case a TTL-based cache strategy is used.
 */
public record DynamicResourcePackEntry(String url, String sha1) {

    /**
     * Compact constructor with validation.
     * Ensures the URL is present; SHA-1 remains optional.
     */
    public DynamicResourcePackEntry {
        Objects.requireNonNull(url, "url must not be null");
        if (url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
    }

    /**
     * Returns true when a SHA-1 hash is present and non-blank.
     * Callers should fall back to TTL-based cache validation otherwise.
     */
    public boolean hasSha1() {
        return sha1 != null && !sha1.isBlank();
    }
}
