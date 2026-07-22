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
