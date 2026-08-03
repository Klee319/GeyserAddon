package com.geyserextra.paper.pack;

import com.geyserextra.core.api.CustomItemMapping;
import com.geyserextra.core.registry.ItemMappingRegistry;
import com.geyserextra.core.util.JsonUtil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Pre-registers PDC-identified custom items from declaration files, so they
 * exist for Bedrock before anyone has held one.
 *
 * <p>This closes the last "observe first" hole in registration. CMD items can
 * be pre-registered from the Java pack (the pack states the base item), and
 * item-model items from {@link ItemModelHintsReader}. A PDC item has no way to
 * declare its base item anywhere in a resource pack, so the only paths that
 * ever registered one were the recipe scanner and a live {@code ItemStack}
 * passing through a listener. Anything obtained by a route with no recipe —
 * mob drops, dungeon rewards, merchant stock — therefore stayed unregistered
 * indefinitely, and an unregistered item on Bedrock loses both its artwork and
 * its off-hand permission, because Bedrock only allows the off-hand for a short
 * vanilla whitelist plus registered custom items.</p>
 *
 * <p>File format — {@code <dataFolder>/pdc_hints/*.json}:</p>
 * <pre>{@code
 * {
 *   "entries": [
 *     {
 *       "pdc_identifier": "trinityforge:flame_sword",
 *       "base_item": "minecraft:diamond_sword",
 *       "display_name": "炎の剣",          // optional
 *       "unbreakable": false              // optional
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p><strong>Known limit.</strong> Geyser v2 has no predicate that inspects a
 * PDC <em>value</em>, so several PDC items sharing one base material collapse
 * to whichever definition registers first — {@code CustomItemsHandler} already
 * warns about this at startup. Pre-registration therefore fixes names and
 * off-hand permission for every declared item, but distinct icons for
 * same-base items remain out of reach until Geyser gains such a predicate.</p>
 */
public final class PdcHintsReader {

    /** Directory name under the plugin data folder that hint files live in. */
    public static final String HINTS_DIR_NAME = "pdc_hints";

    private PdcHintsReader() {}

    /**
     * One validated hint entry.
     *
     * @param pdcIdentifier stable PDC identity value (e.g.
     *                      {@code "trinityforge:flame_sword"})
     * @param baseItem      namespaced Java base item the marker rides on
     * @param displayName   optional Bedrock display name, or {@code null}
     * @param unbreakable   whether the item is unbreakable
     * @param sourceFile    file name the entry came from; diagnostics only
     */
    public record PdcHint(
        String pdcIdentifier,
        String baseItem,
        String displayName,
        boolean unbreakable,
        String sourceFile
    ) {}

    /**
     * Reads every {@code *.json} file in {@code hintsDir} (sorted by file name
     * for deterministic precedence) and returns the validated entries. A
     * missing directory is the normal "feature unused" case and yields an empty
     * list without logging.
     */
    public static List<PdcHint> readAll(Path hintsDir, Logger logger) {
        if (hintsDir == null || !Files.isDirectory(hintsDir)) {
            return List.of();
        }

        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(hintsDir, "*.json")) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        } catch (IOException ex) {
            logger.warning("[PdcHints] failed to list " + hintsDir + ": "
                + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return List.of();
        }
        files.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));

        Map<String, PdcHint> deduped = new LinkedHashMap<>();
        for (Path file : files) {
            for (PdcHint hint : readFile(file, logger)) {
                String key = hint.baseItem() + "\0" + hint.pdcIdentifier();
                PdcHint previous = deduped.putIfAbsent(key, hint);
                if (previous != null) {
                    logger.warning("[PdcHints] duplicate hint for " + hint.baseItem()
                        + " + " + hint.pdcIdentifier() + " in " + hint.sourceFile()
                        + " — keeping the first occurrence (from "
                        + previous.sourceFile() + ")");
                }
            }
        }
        return List.copyOf(deduped.values());
    }

    /**
     * Registers every hint that is not already in the registry.
     *
     * <p>Unlike {@link ItemModelHintsReader#prepopulate} there is no
     * "does a pack define this?" gate. A PDC item has no pack-side model to
     * check, and registering without one is safe here: the auto pack emits an
     * {@code item_texture.json} entry pointing at the base item's vanilla
     * texture for every mapping, so the worst case is the artwork the player
     * already had — while the name and off-hand permission become correct.</p>
     *
     * @return number of newly registered mappings
     */
    public static int prepopulate(
        ItemMappingRegistry registry,
        List<PdcHint> hints,
        Logger logger
    ) {
        int added = 0;
        int skippedExisting = 0;
        for (PdcHint hint : hints) {
            if (registry.getByPdc(hint.baseItem(), hint.pdcIdentifier()).isPresent()) {
                skippedExisting++;
                continue;
            }
            registry.register(new CustomItemMapping(
                mappingNameFor(registry, hint.pdcIdentifier(), hint.baseItem()),
                hint.baseItem(),
                0,       // PDC mappings carry no CMD
                hint.unbreakable(),
                hint.displayName(),
                null,    // icon falls back to the mapping name
                CustomItemMapping.CREATIVE_CATEGORY_ITEMS,
                null,    // creative group unset
                true,    // register with Geyser
                hint.pdcIdentifier(),
                null,    // no armor data
                null     // no item model
            ));
            added++;
        }
        if (added > 0) {
            logger.info("[PdcHints] pre-registered " + added
                + " PDC mapping(s) from hints (" + skippedExisting
                + " already present)");
        }
        return added;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Parses one hint file; malformed content yields an empty list. */
    private static List<PdcHint> readFile(Path file, Logger logger) {
        String fileName = file.getFileName().toString();
        Object parsed;
        try {
            parsed = JsonUtil.fromJson(Files.readString(file), Object.class);
        } catch (Exception ex) {
            logger.warning("[PdcHints] failed to parse " + fileName + ": "
                + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                + " — skipping this file.");
            return List.of();
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            logger.warning("[PdcHints] " + fileName + " is not a JSON object — skipping.");
            return List.of();
        }
        Object entriesObj = root.get("entries");
        if (!(entriesObj instanceof List<?> entries)) {
            logger.warning("[PdcHints] " + fileName
                + " has no \"entries\" array — skipping.");
            return List.of();
        }

        List<PdcHint> out = new ArrayList<>();
        int index = 0;
        for (Object entryObj : entries) {
            index++;
            if (!(entryObj instanceof Map<?, ?> entry)) {
                logger.warning("[PdcHints] " + fileName + " entry #" + index
                    + " is not an object — skipping.");
                continue;
            }
            String pdcIdentifier = stringField(entry, "pdc_identifier");
            String baseItem = stringField(entry, "base_item");
            if (pdcIdentifier == null || baseItem == null) {
                logger.warning("[PdcHints] " + fileName + " entry #" + index
                    + " is missing pdc_identifier and/or base_item — skipping.");
                continue;
            }
            out.add(new PdcHint(
                pdcIdentifier,
                normalizeBaseItem(baseItem),
                stringField(entry, "display_name"),
                Boolean.TRUE.equals(entry.get("unbreakable")),
                fileName));
        }
        return out;
    }

    /** Returns the trimmed string field, or {@code null} when absent/blank. */
    private static String stringField(Map<?, ?> entry, String key) {
        Object value = entry.get(key);
        if (value instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    /**
     * Normalises {@code base_item} to the registry's canonical
     * {@code minecraft:<lowercase>} form. Bare material names are accepted for
     * operator convenience.
     */
    private static String normalizeBaseItem(String baseItem) {
        String lower = baseItem.toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }

    /**
     * Mapping name for a PDC hint, using the same sanitisation
     * {@code CustomItemScanner.scanPdcItem} applies to an observed identifier.
     *
     * <p>Matching it is cosmetic rather than load-bearing — dedup is keyed on
     * the {@code (baseItem, pdc_identifier)} pair, so a live observation of an
     * already-hinted item finds this mapping regardless of its name — but a
     * pre-registered entry and an observed one should not look like two
     * different items in the ledger.</p>
     */
    static String mappingNameFor(ItemMappingRegistry registry, String pdcIdentifier, String baseItem) {
        String name = sanitize(pdcIdentifier);
        if (!registry.contains(name)) {
            return name;
        }
        String suffix = baseItem.startsWith("minecraft:")
            ? baseItem.substring("minecraft:".length())
            : baseItem;
        String withBase = name + "_" + sanitize(suffix);
        if (!registry.contains(withBase)) {
            return withBase;
        }
        return withBase + "_"
            + Integer.toHexString((baseItem + "::" + pdcIdentifier).hashCode() & 0xfffff);
    }

    private static String sanitize(String input) {
        return input.toLowerCase(Locale.ROOT)
            .replace(" ", "_")
            .replace("-", "_")
            .replaceAll("[^a-z0-9_:]", "");
    }
}
