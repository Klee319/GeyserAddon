package com.geyserextra.paper.pack.generate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves a Java {@code minecraft:&lt;id&gt;} name for Bedrock custom-item
 * inventory display.
 *
 * <p>When a flat inventory PNG exists ({@code item_texture.json} /
 * {@code *_carried}), that path is returned. When the id is a block with no
 * flat PNG, {@link Result#useBlockIcon()} is true — Bedrock vanilla draws a
 * 3D block icon; Geyser exposes this via {@code GeyserBlockPlacer.useBlockIcon}.</p>
 */
final class VanillaTextureResolver {

    private static final String ITEMS = "textures/items/";
    private static final String BLOCKS = "textures/blocks/";

    private final BedrockTextureCatalog catalog;
    private final BedrockInventoryIconIndex iconIndex;

    VanillaTextureResolver(BedrockTextureCatalog catalog, BedrockInventoryIconIndex iconIndex) {
        this.catalog = catalog;
        this.iconIndex = iconIndex;
    }

    Result resolveResult(String javaName, boolean isBlock) {
        if (javaName == null || javaName.isBlank()) {
            return new Result(null, false);
        }
        String name = javaName.toLowerCase(Locale.ROOT);

        String indexed = iconIndex.resolve(name);
        if (indexed != null) {
            return new Result(indexed, false);
        }

        String itemPath = catalog.firstExisting(itemCandidates(name));
        if (itemPath != null) {
            return new Result(itemPath, false);
        }

        if (isBlock) {
            return new Result(null, true);
        }
        return new Result(null, false);
    }

    /**
     * @param path         flat texture path for {@code item_texture.json}, or null
     * @param useBlockIcon true when Bedrock should render the 3D block icon
     */
    record Result(String path, boolean useBlockIcon) {}

    private static List<String> itemCandidates(String name) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        addAll(out, directItemPaths(name));
        addAll(out, legacyItemRenames(name));
        addAll(out, spearPaths(name));
        addAll(out, boatPaths(name));
        addAll(out, nautilusArmorPaths(name));
        addAll(out, bookPaths(name));
        addAll(out, dyePaths(name));
        addAll(out, suffixFamilyPaths(name));
        addAll(out, musicDiscPaths(name));
        out.add(BLOCKS + name + "_carried");
        return new ArrayList<>(out);
    }

    private static void addAll(Set<String> target, List<String> paths) {
        target.addAll(paths);
    }

    private static List<String> directItemPaths(String name) {
        List<String> out = new ArrayList<>();
        out.add(ITEMS + name);
        out.add(ITEMS + swapGoldenWood(name));
        return out;
    }

    private static List<String> legacyItemRenames(String name) {
        String legacy = switch (name) {
            case "golden_apple" -> "apple_golden";
            case "golden_carrot" -> "carrot_golden";
            case "beef" -> "beef_raw";
            case "cooked_beef" -> "beef_cooked";
            case "porkchop" -> "porkchop_raw";
            case "cooked_porkchop" -> "porkchop_cooked";
            case "chicken" -> "chicken_raw";
            case "cooked_chicken" -> "chicken_cooked";
            case "mutton" -> "mutton_raw";
            case "cooked_mutton" -> "mutton_cooked";
            case "rabbit" -> "rabbit_raw";
            case "cooked_rabbit" -> "rabbit_cooked";
            case "cod" -> "fish_raw";
            case "cooked_cod" -> "fish_cooked";
            case "salmon" -> "fish_salmon_raw";
            case "cooked_salmon" -> "fish_salmon_cooked";
            case "tropical_fish" -> "fish_clownfish_raw";
            case "pufferfish" -> "fish_pufferfish_raw";
            case "bow" -> "bow_standby";
            // Bedrock keys the resting crossbow the same way it keys the bow,
            // but only the bow case existed here. Every crossbow-based custom
            // item on the server therefore resolved to nothing and had its
            // item_texture entry skipped entirely.
            case "crossbow" -> "crossbow_standby";
            case "compass" -> "compass_item";
            case "clock" -> "clock_item";
            case "slime_ball" -> "slimeball";
            case "nether_brick" -> "netherbrick";
            case "baked_potato" -> "potato_baked";
            case "poisonous_potato" -> "potato_poisonous";
            case "fermented_spider_eye" -> "spider_eye_fermented";
            case "turtle_scute" -> "turtle_shell_piece";
            case "wheat_seeds" -> "seeds_wheat";
            case "pumpkin_seeds" -> "seeds_pumpkin";
            case "melon_seeds" -> "seeds_melon";
            case "beetroot_seeds" -> "seeds_beetroot";
            // Bedrock ships no separate enchanted apple art; it draws the
            // ordinary golden apple with a glint on top.
            case "enchanted_golden_apple" -> "apple_golden";
            case "glass_bottle" -> "potion_bottle_empty";
            case "potion" -> "potion_bottle_drinkable";
            case "splash_potion" -> "potion_bottle_splash";
            case "lingering_potion" -> "potion_bottle_lingering";
            case "bucket" -> "bucket_empty";
            case "water_bucket" -> "bucket_water";
            case "lava_bucket" -> "bucket_lava";
            case "milk_bucket" -> "bucket_milk";
            case "powder_snow_bucket" -> "bucket_powder_snow";
            case "axolotl_bucket" -> "bucket_axolotl";
            case "cod_bucket" -> "bucket_cod";
            case "salmon_bucket" -> "bucket_salmon";
            case "tropical_fish_bucket" -> "bucket_tropical";
            case "pufferfish_bucket" -> "bucket_pufferfish";
            case "tadpole_bucket" -> "bucket_tadpole";
            case "minecart" -> "minecart_normal";
            case "chest_minecart" -> "minecart_chest";
            case "furnace_minecart" -> "minecart_furnace";
            case "tnt_minecart" -> "minecart_tnt";
            case "hopper_minecart" -> "minecart_hopper";
            case "command_block_minecart" -> "minecart_command_block";
            case "redstone" -> "redstone_dust";
            case "fire_charge" -> "fireball";
            case "firework_rocket" -> "fireworks";
            case "firework_star" -> "fireworks_charge";
            case "sugar_cane" -> "reeds";
            case "map" -> "map_empty";
            case "filled_map" -> "map_filled";
            case "melon_slice" -> "melon";
            case "glistering_melon_slice" -> "melon_speckled";
            case "popped_chorus_fruit" -> "chorus_fruit_popped";
            case "totem_of_undying" -> "totem";
            default -> null;
        };
        if (legacy == null) {
            return List.of();
        }
        return List.of(ITEMS + legacy);
    }

    private static List<String> spearPaths(String name) {
        if (!name.endsWith("_spear")) {
            return List.of();
        }
        String material = name.substring(0, name.length() - "_spear".length());
        if ("golden".equals(material)) {
            material = "gold";
        } else if ("wooden".equals(material)) {
            material = "wood";
        }
        return List.of(ITEMS + "spear/" + material + "_spear");
    }

    private static List<String> boatPaths(String name) {
        List<String> out = new ArrayList<>();
        if (name.endsWith("_boat")) {
            out.add(ITEMS + name);
            String wood = name.substring(0, name.length() - "_boat".length());
            if ("dark_oak".equals(wood)) {
                out.add(ITEMS + "boat_darkoak");
            } else {
                out.add(ITEMS + "boat_" + wood);
            }
        }
        if (name.endsWith("_raft")) {
            out.add(ITEMS + name);
        }
        if (name.endsWith("_chest_boat")) {
            out.add(ITEMS + name);
        }
        return out;
    }

    private static List<String> nautilusArmorPaths(String name) {
        if (!name.endsWith("_nautilus_armor")) {
            return List.of();
        }
        String tier = name.substring(0, name.length() - "_nautilus_armor".length());
        if ("golden".equals(tier)) {
            tier = "gold";
        }
        return List.of(ITEMS + "nautilus_armor/" + tier + "_nautilus_armor");
    }

    private static List<String> bookPaths(String name) {
        return switch (name) {
            case "book" -> List.of(ITEMS + "book_normal");
            case "enchanted_book" -> List.of(ITEMS + "book_enchanted");
            case "writable_book" -> List.of(ITEMS + "book_writable");
            case "written_book" -> List.of(ITEMS + "book_written");
            case "knowledge_book" -> List.of(ITEMS + "book_portfolio");
            default -> List.of();
        };
    }

    /**
     * Bedrock still stores every dye as {@code dye_powder_*}, from the days
     * when dyes were damage values on one item.
     *
     * <p>The {@code _new} suffix is the trap here: {@code dye_powder_black}
     * is the ink sac and {@code dye_powder_black_new} is black dye. Four
     * colours carry that split because those four dyes were once the mob or
     * plant drop itself.</p>
     */
    private static List<String> dyePaths(String name) {
        String legacy = switch (name) {
            case "ink_sac" -> "black";
            case "glow_ink_sac" -> "glow";
            case "lapis_lazuli" -> "blue";
            case "cocoa_beans" -> "brown";
            case "bone_meal" -> "white";
            default -> null;
        };
        if (legacy != null) {
            return List.of(ITEMS + "dye_powder_" + legacy);
        }
        if (!name.endsWith("_dye")) {
            return List.of();
        }
        String colour = name.substring(0, name.length() - "_dye".length());
        if ("light_gray".equals(colour)) {
            return List.of(ITEMS + "dye_powder_silver");
        }
        List<String> out = new ArrayList<>();
        // The _new variant first: for the four split colours it is the dye,
        // and for the rest it simply does not exist and falls through.
        out.add(ITEMS + "dye_powder_" + colour + "_new");
        out.add(ITEMS + "dye_powder_" + colour);
        return out;
    }

    /**
     * Families Bedrock names {@code <family>_<variant>} where Java names them
     * {@code <variant>_<family>}.
     */
    private static List<String> suffixFamilyPaths(String name) {
        if (name.endsWith("_bundle")) {
            String colour = name.substring(0, name.length() - "_bundle".length());
            return List.of(ITEMS + "bundle_" + colour);
        }
        if (name.endsWith("_harness")) {
            String colour = name.substring(0, name.length() - "_harness".length());
            return List.of(ITEMS + "harness/harness_" + colour);
        }
        return List.of();
    }

    private static List<String> musicDiscPaths(String name) {
        if (!name.startsWith("music_disc_")) {
            return List.of();
        }
        return List.of(ITEMS + "record_" + name.substring("music_disc_".length()));
    }

    private static String swapGoldenWood(String name) {
        if (name.startsWith("golden_")) {
            return "gold_" + name.substring("golden_".length());
        }
        if (name.startsWith("wooden_")) {
            return "wood_" + name.substring("wooden_".length());
        }
        return name;
    }
}
