package com.geyserextra.extension.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the gate that keeps ordinary vanilla gear out of Geyser's custom item
 * registry.
 *
 * <p>The failure this guards against is invisible rather than ugly. A PDC-only
 * mapping registers on {@code hasComponent("minecraft:custom_data")}, which
 * matches <em>any</em> item carrying <em>any</em> persistent data — including a
 * plain diamond sword that some plugin merely tagged. When the pack ships no
 * artwork for that mapping, the resulting Bedrock item is drawn with the stock
 * texture, so it looks perfectly normal while no longer being
 * {@code minecraft:diamond_sword}. Bedrock's smithing table then refuses it and
 * the player simply cannot upgrade to netherite.</p>
 */
@DisplayName("CustomItemsHandler registration gate")
class CustomItemsHandlerTest {

    @Nested
    @DisplayName("vanilla look-alike detection")
    class VanillaLookAlike {

        @Test
        @DisplayName("a PDC-only mapping with no pack artwork is left as the vanilla item")
        void pdcOnlyWithoutArtworkIsLeftAlone() {
            // trinityforge:tradeable_diamond_sword — a flag the server stamps
            // on ordinary gear. Registering it is what broke the smithing table.
            assertThat(CustomItemsHandler.isVanillaLookAlike(
                false, true, null, false, false)).isTrue();
        }

        @Test
        @DisplayName("an authored icon is reason enough to register")
        void ownIconIsRegistered() {
            assertThat(CustomItemsHandler.isVanillaLookAlike(
                false, true, null, true, false)).isFalse();
        }

        @Test
        @DisplayName("an attachable is reason enough to register, even with no icon")
        void ownAttachableIsRegistered() {
            // The in-hand 3D model is the whole point of these mappings; the
            // inventory icon falling back to vanilla does not make them useless.
            assertThat(CustomItemsHandler.isVanillaLookAlike(
                false, true, null, false, true)).isFalse();
        }

        @Test
        @DisplayName("an operator-named icon wins even when the pack cannot be read")
        void explicitIconIsHonoured() {
            assertThat(CustomItemsHandler.isVanillaLookAlike(
                false, true, "some_icon", false, false)).isFalse();
            // Blank is not a choice, it is an absent one.
            assertThat(CustomItemsHandler.isVanillaLookAlike(
                false, true, "   ", false, false)).isTrue();
        }

        @Test
        @DisplayName("custom_model_data and item_model mappings are never touched")
        void preciselySelectedMappingsAreNeverSkipped() {
            // Their predicates only fire on items that really carry the marker,
            // so they cannot capture a plain vanilla item no matter what the
            // pack does or does not ship.
            assertThat(CustomItemsHandler.isVanillaLookAlike(
                true, true, null, false, false)).isFalse();
            assertThat(CustomItemsHandler.isVanillaLookAlike(
                true, false, null, false, false)).isFalse();
        }

        @Test
        @DisplayName("a mapping with no PDC identifier is out of scope")
        void nonPdcMappingsAreOutOfScope() {
            // Those are handled by the separate no-predicate guard; claiming
            // them here would silently widen this gate.
            assertThat(CustomItemsHandler.isVanillaLookAlike(
                false, false, null, false, false)).isFalse();
        }
    }

    @Nested
    @DisplayName("pack-authored texture detection")
    class PackTextureDetection {

        private static final Set<String> PACK = Set.of(
            "textures/items/trinityforge_abyss_sword",
            "textures/items/trinityforge_abyss_sword_gui");

        private static JsonElement json(String raw) {
            return JsonParser.parseString(raw);
        }

        @Test
        @DisplayName("a path the pack ships counts as authored")
        void packResidentPathIsAuthored() {
            assertThat(CustomItemsHandler.referencesPackTexture(
                json("{\"textures\":\"textures/items/trinityforge_abyss_sword\"}"), PACK))
                .isTrue();
        }

        @Test
        @DisplayName("a stock Bedrock path does not")
        void stockBedrockPathIsNotAuthored() {
            // The exact shape that made 488 definitions masquerade as authored:
            // item_texture.json lists them, but the artwork is Bedrock's own.
            assertThat(CustomItemsHandler.referencesPackTexture(
                json("{\"textures\":\"textures/items/diamond_sword\"}"), PACK))
                .isFalse();
        }

        @Test
        @DisplayName("the array form counts if any element is pack-resident")
        void arrayFormChecksEveryElement() {
            assertThat(CustomItemsHandler.referencesPackTexture(
                json("{\"textures\":[\"textures/items/diamond_sword\","
                    + "\"textures/items/trinityforge_abyss_sword\"]}"), PACK))
                .isTrue();
            assertThat(CustomItemsHandler.referencesPackTexture(
                json("{\"textures\":[\"textures/items/diamond_sword\"]}"), PACK))
                .isFalse();
        }

        @Test
        @DisplayName("path matching ignores case, as the key sets are lowercased")
        void matchingIsCaseInsensitive() {
            assertThat(CustomItemsHandler.referencesPackTexture(
                json("{\"textures\":\"Textures/Items/TrinityForge_Abyss_Sword\"}"), PACK))
                .isTrue();
        }

        @Test
        @DisplayName("malformed or empty entries are treated as unauthored, never as a crash")
        void malformedEntriesAreUnauthored() {
            // A pack build that emitted something unexpected must degrade to
            // "no artwork" rather than take down item registration entirely.
            assertThat(CustomItemsHandler.referencesPackTexture(null, PACK)).isFalse();
            assertThat(CustomItemsHandler.referencesPackTexture(json("\"bare string\""), PACK))
                .isFalse();
            assertThat(CustomItemsHandler.referencesPackTexture(json("{}"), PACK)).isFalse();
            assertThat(CustomItemsHandler.referencesPackTexture(
                json("{\"textures\":[]}"), PACK)).isFalse();
            assertThat(CustomItemsHandler.referencesPackTexture(
                json("{\"textures\":{\"path\":\"x\"}}"), PACK)).isFalse();
            assertThat(CustomItemsHandler.referencesPackTexture(
                json("{\"textures\":[123]}"), PACK)).isFalse();
        }
    }
}
