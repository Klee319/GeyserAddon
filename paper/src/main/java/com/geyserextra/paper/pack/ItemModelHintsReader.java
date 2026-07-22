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
import java.util.Set;
import java.util.logging.Logger;

/**
 * Reads operator- or plugin-supplied <b>item model hints</b> from
 * {@code <pluginDataFolder>/item_model_hints/*.json} and pre-populates the
 * {@link ItemMappingRegistry} with the declared
 * {@code (baseItem, item_model)} pairs.
 *
 * <p>Why hints are needed at all: a 1.21.4+ Java pack's direct item-model
 * definition ({@code assets/&lt;ns&gt;/items/&lt;name&gt;.json}) carries no
 * information about which vanilla base item it will be applied to — the
 * {@code minecraft:item_model} component can sit on any material. Geyser's
 * custom item registration, however, is keyed by the Java base item, so a
 * pack alone can never be auto-registered. Without hints, the only path to
 * registration is the runtime scanner observing a live ItemStack carrying the
 * component (see {@code CustomItemScanner.scanItemModelItem}), which requires
 * a player to actually touch or view the item once <i>and</i> a server
 * restart before the texture reaches Bedrock. Hints close that gap: declare
 * the pair up front and the very first pack build includes the texture.</p>
 *
 * <p>File format (any number of {@code *.json} files in the directory; other
 * plugins may drop their own file alongside operator-authored ones):</p>
 * <pre>{@code
 * {
 *   "entries": [
 *     {
 *       "item_model": "myplugin:gui/icon_save",
 *       "base_item": "minecraft:paper",
 *       "display_name": "Save"          // optional
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>Validation mirrors the runtime scanner's rules: {@code item_model} must
 * be namespaced and must not use the {@code minecraft} namespace (vanilla
 * item models are Geyser's own concern); {@code base_item} accepts a bare
 * material name and is normalised to {@code minecraft:<lowercase>}.
 * Malformed files or entries are logged and skipped so one bad file cannot
 * disable every other hint.</p>
 */
public final class ItemModelHintsReader {

    /** Directory name under the plugin data folder that hint files live in. */
    public static final String HINTS_DIR_NAME = "item_model_hints";

    private ItemModelHintsReader() {}

    /**
     * One validated hint entry.
     *
     * @param itemModelId  namespaced {@code minecraft:item_model} component
     *                     value (e.g. {@code "myplugin:gui/icon_save"})
     * @param baseItem     namespaced Java base item the model rides on
     *                     (e.g. {@code "minecraft:paper"})
     * @param displayName  optional Bedrock display name; {@code null} when the
     *                     hint file omitted it
     * @param sourceFile   file name the entry came from; diagnostics only
     */
    public record ItemModelHint(
        String itemModelId,
        String baseItem,
        String displayName,
        String sourceFile
    ) {}

    /**
     * Reads every {@code *.json} file in {@code hintsDir} (sorted by file
     * name for deterministic precedence) and returns the validated entries.
     * A missing directory is the normal "feature unused" case and yields an
     * empty list without logging.
     *
     * <p>Duplicate {@code (baseItem, item_model)} pairs keep the first
     * occurrence — consistent with the registry's own first-registration-wins
     * policy — and log a warning so the operator can clean up the overlap.</p>
     */
    public static List<ItemModelHint> readAll(Path hintsDir, Logger logger) {
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
            logger.warning("[ItemModelHints] failed to list " + hintsDir + ": "
                + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return List.of();
        }
        files.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));

        // Keyed by (baseItem NUL item_model) so the same model on two
        // different base items stays two distinct hints while true
        // duplicates collapse first-wins.
        Map<String, ItemModelHint> deduped = new LinkedHashMap<>();
        for (Path file : files) {
            for (ItemModelHint hint : readFile(file, logger)) {
                String key = hint.baseItem() + "\0" + hint.itemModelId();
                ItemModelHint previous = deduped.putIfAbsent(key, hint);
                if (previous != null) {
                    logger.warning("[ItemModelHints] duplicate hint for "
                        + hint.baseItem() + " + " + hint.itemModelId()
                        + " in " + hint.sourceFile()
                        + " — keeping the first occurrence (from "
                        + previous.sourceFile() + ")");
                }
            }
        }
        return List.copyOf(deduped.values());
    }

    /**
     * Registers every hint whose item model actually exists in a configured
     * Java pack. Hints for models no pack defines are skipped with a warning
     * instead of registered, because a registration without a texture would
     * surface on Bedrock as a magenta/missing icon — worse than the vanilla
     * fallback the player gets today.
     *
     * @param registry             live mapping registry to populate
     * @param hints                validated hints from {@link #readAll}
     * @param availableItemModels  item model ids resolved by
     *                             {@code JavaPackReader.scanDirectItemModels()}
     *                             across every configured pack
     * @param logger               plugin logger
     * @return number of newly registered mappings
     */
    public static int prepopulate(
        ItemMappingRegistry registry,
        List<ItemModelHint> hints,
        Set<String> availableItemModels,
        Logger logger
    ) {
        int added = 0;
        int skippedExisting = 0;
        int skippedNoModel = 0;
        for (ItemModelHint hint : hints) {
            if (!availableItemModels.contains(hint.itemModelId())) {
                skippedNoModel++;
                logger.warning("[ItemModelHints] " + hint.sourceFile()
                    + " declares " + hint.itemModelId()
                    + " but no configured Java pack defines that item model — "
                    + "skipping (check javaResourcePackPath and the pack's "
                    + "assets/<ns>/items/ directory).");
                continue;
            }
            if (registry.getByItemModel(hint.baseItem(), hint.itemModelId()).isPresent()) {
                skippedExisting++;
                continue;
            }
            String name = mappingNameFor(registry, hint.baseItem(), hint.itemModelId());
            CustomItemMapping mapping = new CustomItemMapping(
                name,
                hint.baseItem(),
                0,       // item-model mappings carry no CMD
                false,   // unbreakable unknown from a hint alone
                hint.displayName(),
                null,    // icon falls back to the mapping name
                CustomItemMapping.CREATIVE_CATEGORY_ITEMS,
                null,    // creative group unset
                true,    // register with Geyser
                null,    // no PDC identifier
                null,    // no armor data
                hint.itemModelId()
            );
            registry.register(mapping);
            added++;
        }
        if (added > 0 || skippedNoModel > 0) {
            logger.info("[ItemModelHints] pre-registered " + added
                + " item-model mapping(s) from hints ("
                + skippedExisting + " already present, "
                + skippedNoModel + " without a pack-defined model)");
        }
        return added;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Parses one hint file; malformed content yields an empty list. */
    private static List<ItemModelHint> readFile(Path file, Logger logger) {
        String fileName = file.getFileName().toString();
        Object parsed;
        try {
            parsed = JsonUtil.fromJson(Files.readString(file), Object.class);
        } catch (Exception ex) {
            logger.warning("[ItemModelHints] failed to parse " + fileName + ": "
                + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                + " — skipping this file.");
            return List.of();
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            logger.warning("[ItemModelHints] " + fileName
                + " is not a JSON object — skipping.");
            return List.of();
        }
        Object entriesObj = root.get("entries");
        if (!(entriesObj instanceof List<?> entries)) {
            logger.warning("[ItemModelHints] " + fileName
                + " has no \"entries\" array — skipping.");
            return List.of();
        }

        List<ItemModelHint> out = new ArrayList<>();
        int index = 0;
        for (Object entryObj : entries) {
            index++;
            if (!(entryObj instanceof Map<?, ?> entry)) {
                logger.warning("[ItemModelHints] " + fileName + " entry #" + index
                    + " is not an object — skipping.");
                continue;
            }
            String itemModel = stringField(entry, "item_model");
            String baseItem = stringField(entry, "base_item");
            String displayName = stringField(entry, "display_name");
            if (itemModel == null || baseItem == null) {
                logger.warning("[ItemModelHints] " + fileName + " entry #" + index
                    + " is missing item_model and/or base_item — skipping.");
                continue;
            }
            if (!itemModel.contains(":")) {
                logger.warning("[ItemModelHints] " + fileName + " entry #" + index
                    + " item_model \"" + itemModel
                    + "\" has no namespace — skipping.");
                continue;
            }
            if (itemModel.startsWith("minecraft:")) {
                // Mirrors CustomItemScanner.scanItem: minecraft-namespace item
                // models are vanilla's own definitions and are handled by
                // Geyser itself; registering them as custom items would
                // shadow vanilla behaviour.
                logger.warning("[ItemModelHints] " + fileName + " entry #" + index
                    + " uses the minecraft namespace (" + itemModel
                    + ") which is reserved for vanilla item models — skipping.");
                continue;
            }
            out.add(new ItemModelHint(
                itemModel, normalizeBaseItem(baseItem), displayName, fileName));
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
     * {@code minecraft:<lowercase>} form. Bare material names (as they
     * appear in Bukkit's {@code Material} enum, e.g. {@code DIAMOND_PICKAXE})
     * are accepted for operator convenience.
     */
    private static String normalizeBaseItem(String baseItem) {
        String lower = baseItem.toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }

    /**
     * Builds a registry-unique mapping name. Mirrors the
     * {@code itemmodel_<sanitized>} convention of
     * {@code CustomItemScanner.scanItemModelItem} (kept as an independent
     * copy: the scanner class is Bukkit-bound, and exact name equality is
     * not required for correctness — dedup is keyed on the
     * {@code (baseItem, item_model)} pair, not the name).
     */
    private static String mappingNameFor(
        ItemMappingRegistry registry, String baseItem, String itemModelId
    ) {
        String name = "itemmodel_" + itemModelId.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_\\-./]", "_")
            .replace('/', '_')
            .replace('.', '_');
        if (registry.contains(name)) {
            name += "_" + Integer.toHexString(
                (baseItem + "\0" + itemModelId).hashCode() & 0xfffff);
        }
        return name;
    }
}
