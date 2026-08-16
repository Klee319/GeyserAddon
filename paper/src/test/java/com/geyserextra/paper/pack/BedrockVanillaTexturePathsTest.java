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

    @Test
    @DisplayName("crossbow resolves to its standby art like the bow does")
    void crossbowResolvesLikeBow() {
        // The bow rename was here from the start and the crossbow one was not,
        // so every crossbow-based custom item on a live server logged
        // "no Bedrock vanilla fallback" and shipped no item_texture entry.
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:bow"))
            .isEqualTo("textures/items/bow_standby");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:crossbow"))
            .isEqualTo("textures/items/crossbow_standby");
    }

    @Test
    @DisplayName("items Bedrock still suffixes with _item")
    void suffixedItemNames() {
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:compass"))
            .isEqualTo("textures/items/compass_item");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:clock"))
            .isEqualTo("textures/items/clock_item");
    }

    @Test
    @DisplayName("dyes take the _new art; the drops they descend from keep the old art")
    void dyesSplitFromTheirSourceDrops() {
        // Bedrock kept one texture family from the damage-value era. For the
        // four colours that were once a drop, the plain name is the drop and
        // the _new name is the dye — swapping them silently draws the wrong
        // icon rather than failing, so both halves are pinned here.
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:black_dye"))
            .isEqualTo("textures/items/dye_powder_black_new");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:ink_sac"))
            .isEqualTo("textures/items/dye_powder_black");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:blue_dye"))
            .isEqualTo("textures/items/dye_powder_blue_new");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:lapis_lazuli"))
            .isEqualTo("textures/items/dye_powder_blue");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:white_dye"))
            .isEqualTo("textures/items/dye_powder_white_new");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:bone_meal"))
            .isEqualTo("textures/items/dye_powder_white");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:cocoa_beans"))
            .isEqualTo("textures/items/dye_powder_brown");
        // Colours with no drop ancestor have no _new variant at all.
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:cyan_dye"))
            .isEqualTo("textures/items/dye_powder_cyan");
        // Java's "light gray" is Bedrock's "silver".
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:light_gray_dye"))
            .isEqualTo("textures/items/dye_powder_silver");
    }

    @Test
    @DisplayName("families Bedrock names prefix-first rather than suffix-first")
    void prefixNamedFamilies() {
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:red_bundle"))
            .isEqualTo("textures/items/bundle_red");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:blue_harness"))
            .isEqualTo("textures/items/harness/harness_blue");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:music_disc_pigstep"))
            .isEqualTo("textures/items/record_pigstep");
    }

    @Test
    @DisplayName("legacy singular names Bedrock never renamed")
    void legacySingularNames() {
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:slime_ball"))
            .isEqualTo("textures/items/slimeball");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:nether_brick"))
            .isEqualTo("textures/items/netherbrick");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:baked_potato"))
            .isEqualTo("textures/items/potato_baked");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:fermented_spider_eye"))
            .isEqualTo("textures/items/spider_eye_fermented");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:wheat_seeds"))
            .isEqualTo("textures/items/seeds_wheat");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:turtle_scute"))
            .isEqualTo("textures/items/turtle_shell_piece");
    }

    @Test
    @DisplayName("potion bottles map to their potion_bottle_* variants")
    void potionBottles() {
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:glass_bottle"))
            .isEqualTo("textures/items/potion_bottle_empty");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:potion"))
            .isEqualTo("textures/items/potion_bottle_drinkable");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:splash_potion"))
            .isEqualTo("textures/items/potion_bottle_splash");
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:lingering_potion"))
            .isEqualTo("textures/items/potion_bottle_lingering");
    }

    @Test
    @DisplayName("enchanted golden apple reuses the plain golden apple art")
    void enchantedGoldenAppleReusesGoldenApple() {
        // Bedrock ships no separate enchanted apple PNG; the glint is drawn
        // over the ordinary one.
        assertThat(BedrockVanillaTexturePaths.resolve("minecraft:enchanted_golden_apple"))
            .isEqualTo("textures/items/apple_golden");
    }
}
