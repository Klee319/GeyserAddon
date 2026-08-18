package com.geyserextra.paper.skin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two pure steps between a Floodgate UUID and a usable skin: deriving
 * the XUID the GeyserMC API is keyed by, and reading the response it returns.
 *
 * <p>Both are silent failure modes. A wrong XUID produces a 404 that looks
 * exactly like "this player has no skin", and a parser that accepts a partial
 * response would hand the applier a half-built texture property — which Java
 * clients reject wholesale, leaving the player as Steve with nothing in the log
 * to say why.</p>
 */
@DisplayName("BedrockSkinService")
class BedrockSkinServiceTest {

    /** A real Floodgate UUID from this network, and the XUID it encodes. */
    private static final UUID FLOODGATE_UUID =
        UUID.fromString("00000000-0000-0000-0009-01f64c666d7d");
    private static final long EXPECTED_XUID = 2535432145759613L;

    private static String base64(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** The signed profile blob the API embeds in {@code value}. */
    private static String signedProfile(String textureHash) {
        return base64("{\"timestamp\":1751822897978,"
            + "\"profileId\":\"1218acb42bc04368b21958e6bae64320\","
            + "\"profileName\":\"PatatjeMC\",\"signatureRequired\":true,"
            + "\"textures\":{\"SKIN\":{\"url\":"
            + "\"http://textures.minecraft.net/texture/" + textureHash + "\","
            + "\"metadata\":{\"model\":\"slim\"}}}}");
    }

    @Nested
    @DisplayName("XUID extraction")
    class XuidExtraction {

        @Test
        @DisplayName("reads the XUID out of a Floodgate UUID's low bits")
        void readsXuidFromFloodgateUuid() {
            assertThat(BedrockSkinService.xuidOf(FLOODGATE_UUID)).isEqualTo(EXPECTED_XUID);
        }

        @Test
        @DisplayName("a Java account's UUID has no XUID")
        void javaUuidHasNoXuid() {
            // Non-zero high bits. Returning the low bits here would send a
            // meaningless lookup for every Java player who owns a head.
            assertThat(BedrockSkinService.xuidOf(
                UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"))).isZero();
        }

        @Test
        @DisplayName("null and the nil UUID yield no XUID")
        void nullAndNilYieldNothing() {
            assertThat(BedrockSkinService.xuidOf(null)).isZero();
            assertThat(BedrockSkinService.xuidOf(new UUID(0L, 0L))).isZero();
        }
    }

    @Nested
    @DisplayName("response parsing")
    class ResponseParsing {

        @Test
        @DisplayName("keeps value, signature and texture id from a full response")
        void parsesFullResponse() {
            String hash = "b3790050bb8e729f9521039c3c1343822a4ce9d4a30974fdb80c5bb0387d5251";
            String json = "{\"hash\":\"71d28a2c\",\"is_steve\":false,"
                + "\"last_update\":1786787325641,\"signature\":\"VVLLwV306cEJ\","
                + "\"texture_id\":\"" + hash + "\","
                + "\"value\":\"" + signedProfile(hash) + "\"}";

            var skin = BedrockSkinService.parse(json);

            assertThat(skin).isNotNull();
            assertThat(skin.signature()).isEqualTo("VVLLwV306cEJ");
            assertThat(skin.textureId()).isEqualTo(hash);
            assertThat(skin.value()).isEqualTo(signedProfile(hash));
        }

        @Test
        @DisplayName("recovers the texture id from the signed value when the field is missing")
        void fallsBackToTheSignedValue() {
            // texture_id is derived data. If the API ever drops or renames it,
            // skull registration must not silently lose its hash while the
            // profile repair carries on working.
            String hash = "aabbccddeeff00112233445566778899aabbccddeeff001122334455667788";
            String json = "{\"signature\":\"sig\",\"value\":\"" + signedProfile(hash) + "\"}";

            assertThat(BedrockSkinService.parse(json).textureId()).isEqualTo(hash);
        }

        @Test
        @DisplayName("a response missing the signature is unusable")
        void unsignedResponseIsRejected() {
            // A textures property without Mojang's signature is worse than no
            // property: the client discards it and the player stays Steve.
            String json = "{\"value\":\"" + signedProfile("abc") + "\",\"texture_id\":\"abc\"}";
            assertThat(BedrockSkinService.parse(json)).isNull();
        }

        @Test
        @DisplayName("a response missing the value is unusable")
        void valuelessResponseIsRejected() {
            assertThat(BedrockSkinService.parse(
                "{\"signature\":\"sig\",\"texture_id\":\"abc\"}")).isNull();
        }

        @Test
        @DisplayName("empty strings count as absent, not as data")
        void emptyFieldsAreAbsent() {
            assertThat(BedrockSkinService.parse(
                "{\"value\":\"\",\"signature\":\"sig\",\"texture_id\":\"abc\"}")).isNull();
            assertThat(BedrockSkinService.parse(
                "{\"value\":\"v\",\"signature\":\"\",\"texture_id\":\"abc\"}")).isNull();
        }

        @Test
        @DisplayName("a value that carries no skin url yields nothing")
        void valueWithoutSkinUrlYieldsNothing() {
            // The API answers 200 with an empty profile for an account it has
            // never seen. That must read as "no skin", not as a crash.
            String json = "{\"signature\":\"sig\",\"value\":\""
                + base64("{\"textures\":{}}") + "\"}";
            assertThat(BedrockSkinService.parse(json)).isNull();
        }

        @Test
        @DisplayName("malformed input never throws on the fetch thread")
        void malformedInputIsNull() {
            assertThat(BedrockSkinService.parse(null)).isNull();
            assertThat(BedrockSkinService.parse("")).isNull();
            assertThat(BedrockSkinService.parse("   ")).isNull();
            assertThat(BedrockSkinService.parse("<html>502 Bad Gateway</html>")).isNull();
            assertThat(BedrockSkinService.parse("[1,2,3]")).isNull();
            assertThat(BedrockSkinService.parse("{\"value\":{\"nested\":1},"
                + "\"signature\":\"sig\"}")).isNull();
            // Well-formed JSON, base64 that is not base64.
            assertThat(BedrockSkinService.parse(
                "{\"value\":\"!!!not base64!!!\",\"signature\":\"sig\"}")).isNull();
        }
    }
}
