package com.geyserextra.paper.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BedrockVanillaTexturePaths")
class BedrockVanillaTexturePathsTest {

    @Test
    @DisplayName("book family maps to Bedrock book_* filenames")
    void books() {
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:book"))
            .isEqualTo("textures/items/book_normal");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:enchanted_book"))
            .isEqualTo("textures/items/book_enchanted");
        assertThat(BedrockVanillaTexturePaths.resolve("writable_book"))
            .isEqualTo("textures/items/book_writable");
    }

    @Test
    @DisplayName("golden_/wooden_ tools map to gold_/wood_ Bedrock names")
    void goldenAndWoodenTools() {
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:golden_sword"))
            .isEqualTo("textures/items/gold_sword");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:wooden_sword"))
            .isEqualTo("textures/items/wood_sword");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:diamond_sword"))
            .isEqualTo("textures/items/diamond_sword");
    }

    @Test
    @DisplayName("spears live under textures/items/spear/ with gold/wood spelling")
    void spears() {
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:golden_spear"))
            .isEqualTo("textures/items/spear/gold_spear");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:wooden_spear"))
            .isEqualTo("textures/items/spear/wood_spear");
    }

    @Test
    @DisplayName("blocks without flat PNGs use useBlockIcon (3D block icon), not face paths")
    void blockFaces() {
        assertThat(BedrockVanillaTexturePaths.usesBlockIcon("minecraft:barrel")).isTrue();
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:barrel")).isNull();
        assertThat(BedrockVanillaTexturePaths.usesBlockIcon("minecraft:jukebox")).isTrue();
        assertThat(BedrockVanillaTexturePaths.usesBlockIcon("minecraft:lectern")).isTrue();
        assertThat(BedrockVanillaTexturePaths.usesBlockIcon("minecraft:enchanting_table")).isTrue();
        assertThat(BedrockVanillaTexturePaths.usesBlockIcon("minecraft:decorated_pot")).isTrue();
        assertThat(BedrockVanillaTexturePaths.usesBlockIcon("minecraft:stone")).isTrue();
        // Item-atlas icons still resolve as flat paths
        assertThat(BedrockVanillaTexturePaths.usesBlockIcon("minecraft:bell")).isFalse();
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:bell"))
            .isEqualTo("textures/items/villagebell");
    }

    @Test
    @DisplayName("useBlockIcon carries the BEDROCK block id for renamed blocks")
    void blockIconBedrockRenames() {
        // Geyser writes the value verbatim into minecraft:block_placer NBT,
        // so renamed blocks must map to the Bedrock name — the Java name
        // would break both the 3D icon and placement prediction.
        assertThat(BedrockVanillaTexturePaths.blockIconBases().get("stone"))
            .isEqualTo("stone");
        java.util.Map<String, String> bases = BedrockVanillaTexturePaths.blockIconBases();
        // Only assert renames for ids actually flagged useBlockIcon — some
        // renamed blocks (e.g. cobweb) may resolve to a flat item PNG instead.
        if (bases.containsKey("cobweb")) {
            assertThat(bases.get("cobweb")).isEqualTo("web");
        }
        if (bases.containsKey("bricks")) {
            assertThat(bases.get("bricks")).isEqualTo("brick_block");
        }
        if (bases.containsKey("dirt_path")) {
            assertThat(bases.get("dirt_path")).isEqualTo("grass_path");
        }
    }

    @Test
    @DisplayName("brewing_stand uses item icon path despite Material.isBlock()")
    void brewingStandPrefersItemIcon() {
        assertThat(BedrockVanillaTexturePaths.usesBlockIcon("minecraft:brewing_stand")).isFalse();
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:brewing_stand"))
            .isEqualTo("textures/items/brewing_stand");
    }

    @Test
    @DisplayName("unchanged item names that already match Bedrock stay as-is")
    void unchangedItems() {
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:glow_berries"))
            .isEqualTo("textures/items/glow_berries");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:blaze_rod"))
            .isEqualTo("textures/items/blaze_rod");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:leather_helmet"))
            .isEqualTo("textures/items/leather_helmet");
    }

    @Test
    @DisplayName("modded namespaces return null (no Bedrock vanilla counterpart)")
    void moddedNamespace() {
        assertThat(BedrockVanillaTexturePaths.resolve("mymod:sword")).isNull();
        assertThat(BedrockVanillaTexturePaths.usesBlockIcon("mymod:sword")).isFalse();
        assertThat(BedrockVanillaTexturePaths.resolve(null)).isNull();
        assertThat(BedrockVanillaTexturePaths.resolve("")).isNull();
    }

    @Test
    @DisplayName("1.21+ boats/rafts keep modern Bedrock filenames")
    void modernBoatsAndRafts() {
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:bamboo_raft"))
            .isEqualTo("textures/items/bamboo_raft");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:mangrove_boat"))
            .isEqualTo("textures/items/mangrove_boat");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:cherry_boat"))
            .isEqualTo("textures/items/cherry_boat");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:pale_oak_boat"))
            .isEqualTo("textures/items/pale_oak_boat");
    }

    @Test
    @DisplayName("resin_clump uses item atlas (not block clump texture)")
    void resinClumpUsesItemAtlas() {
        assertThat(BedrockVanillaTexturePaths.usesBlockIcon("minecraft:resin_clump")).isFalse();
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:resin_clump"))
            .isEqualTo("textures/items/resin_clump");
    }

    @Test
    @DisplayName("nautilus armor uses nautilus_armor/ subdirectory with gold spelling")
    void nautilusArmor() {
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:golden_nautilus_armor"))
            .isEqualTo("textures/items/nautilus_armor/gold_nautilus_armor");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:copper_nautilus_armor"))
            .isEqualTo("textures/items/nautilus_armor/copper_nautilus_armor");
    }
}
