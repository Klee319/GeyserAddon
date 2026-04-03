package com.geyserextra.core.api;

import java.util.Objects;

/**
 * Immutable record representing skull texture data.
 *
 * This record holds the information needed to render custom skull
 * textures for Bedrock Edition players connecting via Geyser.
 *
 * @param textureHash The hash identifier for the skull texture
 * @param textureUrl  The URL where the skull texture can be fetched
 * @param type        The type of skull texture source
 */
public record SkullData(
    String textureHash,
    String textureUrl,
    SkullTextureType type
) {
    /**
     * Compact constructor with validation.
     * Ensures textureHash and type are not null.
     */
    public SkullData {
        Objects.requireNonNull(textureHash, "textureHash must not be null");
        Objects.requireNonNull(type, "type must not be null");

        if (textureHash.isBlank()) {
            throw new IllegalArgumentException("textureHash must not be blank");
        }
    }

    /**
     * Creates a SkullData with skin hash type.
     *
     * @param textureHash The skin texture hash
     * @return A new SkullData instance
     */
    public static SkullData fromSkinHash(String textureHash) {
        String textureUrl = "http://textures.minecraft.net/texture/" + textureHash;
        return new SkullData(textureHash, textureUrl, SkullTextureType.SKIN_HASH);
    }

    /**
     * Creates a SkullData from a player username.
     *
     * @param textureHash The texture hash
     * @param username    The player username (used for reference)
     * @return A new SkullData instance
     */
    public static SkullData fromUsername(String textureHash, String username) {
        String textureUrl = "http://textures.minecraft.net/texture/" + textureHash;
        return new SkullData(textureHash, textureUrl, SkullTextureType.USERNAME);
    }

    /**
     * Creates a SkullData from a player UUID.
     *
     * @param textureHash The texture hash
     * @param uuid        The player UUID (used for reference)
     * @return A new SkullData instance
     */
    public static SkullData fromUuid(String textureHash, String uuid) {
        String textureUrl = "http://textures.minecraft.net/texture/" + textureHash;
        return new SkullData(textureHash, textureUrl, SkullTextureType.UUID);
    }

    /**
     * Creates a SkullData from a full profile texture.
     *
     * @param textureHash The texture hash
     * @param textureUrl  The full texture URL from profile
     * @return A new SkullData instance
     */
    public static SkullData fromProfile(String textureHash, String textureUrl) {
        return new SkullData(textureHash, textureUrl, SkullTextureType.PROFILE);
    }

    /**
     * Checks if the texture URL is valid.
     *
     * @return true if textureUrl is not null and not blank
     */
    public boolean hasValidUrl() {
        return textureUrl != null && !textureUrl.isBlank();
    }

    /**
     * Enumeration of skull texture source types.
     *
     * This determines how the skull texture was originally obtained
     * and may affect how it should be processed.
     */
    public enum SkullTextureType {
        /**
         * Texture was obtained using a player username lookup.
         */
        USERNAME,

        /**
         * Texture was obtained using a player UUID lookup.
         */
        UUID,

        /**
         * Texture was obtained from a full Minecraft profile.
         */
        PROFILE,

        /**
         * Texture was obtained directly from a skin hash.
         */
        SKIN_HASH
    }
}
