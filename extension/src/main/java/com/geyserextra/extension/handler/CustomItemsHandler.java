/*
 * GeyserExtra Extension - Custom Items Handler
 * Loads and registers custom items from shared configuration file.
 */
package com.geyserextra.extension.handler;

import com.geyserextra.core.util.CustomItemCooldownGroups;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.item.custom.NonVanillaCustomItemData;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaItemDataComponents;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaUseCooldown;
import org.geysermc.geyser.api.predicate.MinecraftPredicate;
import org.geysermc.geyser.api.predicate.context.item.ItemPredicateContext;
import org.geysermc.geyser.api.predicate.item.ItemConditionPredicate;
import org.geysermc.geyser.api.predicate.item.ItemRangeDispatchPredicate;
import org.geysermc.geyser.api.util.CreativeCategory;
import org.geysermc.geyser.api.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

/**
 * Handler for loading and registering custom items from shared configuration.
 *
 * <p>Reads item definitions from a shared JSON file ({@code custom_items.json})
 * and registers them with Geyser to enable custom items for Bedrock players.
 * The shared file approach allows synchronization with the Paper plugin.</p>
 *
 * <p><b>API usage:</b> vanilla extensions are registered via the v2 API
 * ({@link CustomItemDefinition} + {@link ItemRangeDispatchPredicate#legacyCustomModelData(int)}).
 * Non-vanilla items still use the deprecated v1 {@link NonVanillaCustomItemData}
 * pending a follow-up migration once the v2 non-vanilla path is exercised in this codebase.</p>
 *
 * <p><b>Duplicate handling:</b> before registering each vanilla item, this handler
 * inspects {@link GeyserDefineCustomItemsEvent#customItemDefinitions()} and skips
 * any mapping whose ({@code base item}, {@code custom_model_data}) pair is already
 * registered by another source. Common sources include
 * {@code plugins/Geyser-Spigot/mappings/*.json} files shipped by sibling plugins
 * (their items typically surface with author-chosen identifiers such as
 * {@code gmdl_<hash>} written into the JSON's {@code name} field), and any other
 * extension that registers via this same event. The earliest registration wins;
 * ours is skipped so the operator-installed mapping keeps authority. The previous
 * v1 path threw {@code CustomItemDefinitionRegisterException} per duplicate,
 * producing one stack trace per conflicting item.</p>
 *
 * <p><b>Texture resolution:</b> items are registered with their generated name as
 * both the Geyser item name and the icon key. The companion
 * {@code AutoBedrockPackBuilder} (Paper module) aliases custom icon keys only
 * when a safe flat vanilla item texture exists. Block items and unresolved
 * vanilla atlas entries stay unregistered unless an authored custom texture
 * exists, allowing Geyser's base-item mapping to retain the exact vanilla
 * inventory render.</p>
 */
public class CustomItemsHandler {

    private static final String ITEMS_FILE_NAME = "custom_items.json";
    private static final String BLOCK_ICON_BASES_FILE = "block_icon_bases.json";

    /** Bedrock-side namespace for items registered by this extension. */
    private static final String BEDROCK_NAMESPACE = "geyserextra";

    /**
     * Vanilla Bedrock item tag that decides what the smithing table accepts in its <b>base</b>
     * slot.
     *
     * <p>Bedrock validates that slot client-side against this tag — not against the recipes the
     * server sends — so a custom item without it simply cannot be placed, and every
     * netherite-upgrade recipe pointing at it is unreachable no matter what
     * {@code BedrockRecipeInjector} ships. The two vanilla items on the other slots
     * (netherite upgrade template, netherite ingot) already carry {@code transform_templates} and
     * {@code transform_materials}, so the base slot is the only gap.
     *
     * <p><b>Applied to every registered custom item, deliberately.</b> Narrowing it to the items
     * that are actually a smithing base would need the shipped recipe table, and that table
     * arrives from the backends <em>after</em> {@code GeyserDefineCustomItemsEvent} has already
     * fired — on a cold start the tag would be missing and the feature would only start working
     * after a second restart, which is exactly the kind of silent, intermittent gap that costs
     * days to diagnose.
     *
     * <p><b>The cost is not purely cosmetic</b> (an earlier wording here said it was). On Java the
     * base slot is restricted by {@code RecipePropertySet(SMITHING_BASE)}, so an item that no
     * smithing recipe accepts <b>cannot be placed there at all</b>. Tagging every custom item lets
     * the Bedrock client accept the placement, Geyser forwards the click, the Java server rejects
     * it and the container re-syncs — the player sees the item flick into the slot and bounce
     * back. That is the same "it looks duplicated for a moment, then returns" symptom recorded for
     * the smithing table before any of this existed, now reachable with any custom item instead of
     * only the upgradable ones. It is still the better trade than a feature that only works from
     * the second boot. What the player receives is decided entirely by the Java server either way.
     */
    private static final String BEDROCK_SMITHING_BASE_TAG = "minecraft:transformable_items";

    private final Extension extension;
    private final Path sharedFolder;
    private final List<ItemMapping> itemMappings;
    /**
     * Bare Java ids whose Bedrock inventory icon is a block texture fallback,
     * mapped to the Bedrock block id ({@code cobweb → web}). Populated from
     * the Paper-written sidecar for parity with pack generation; icons are
     * wired through {@code item_texture.json}, not Geyser {@code useBlockIcon}.
     */
    private final Map<String, String> blockIconBases;
    /** Bare Java ids with a safe flat vanilla item texture alias. */
    private final java.util.Set<String> vanillaTextureBases;
    /** Generated icon keys backed by authored PNGs in the current auto pack. */
    private final java.util.Set<String> customIconKeys;

    /**
     * Attachable basenames present in the active auto pack — the mappings that
     * genuinely carry a custom 3D model. See {@link #collectCustomModelKeys}.
     */
    private final java.util.Set<String> customModelKeys;
    /**
     * Icon keys this process actually handed to Geyser.
     *
     * <p>Definitions are registered once at Geyser start; packs are swapped per
     * session. This is the set a replacement pack must still satisfy — see
     * {@link #packSatisfiesRegisteredIcons(java.util.Set)}.</p>
     */
    private final java.util.Set<String> registeredIconKeys = new java.util.HashSet<>();

    /**
     * {@code <java base item>#<CMD>} to the Bedrock identifier actually registered for it.
     *
     * <p>Only definitions this handler really registered land here. An item that was skipped —
     * a look-alike with no icon and no attachable, a duplicate another plugin already owns —
     * has <b>no distinct Bedrock item</b>, so nothing on the client can address it. The recipe
     * injector needs that distinction: writing a recipe against an unregistered item would
     * silently produce one that can never match.</p>
     */
    private final Map<String, String> registeredBedrockIdentifiers = new ConcurrentHashMap<>();

    /**
     * Read-only view of {@link #registeredBedrockIdentifiers}, built once.
     *
     * <p>This used to be a fresh {@code Map.copyOf} per call, and the recipe injector calls the
     * accessor <b>once per ingredient, per combination, per recipe</b> on the Netty event loop.
     * With ~1200 registered items that is a five-figure number of full map copies for a single
     * session's recipe packet, repeated for every player each time a backend calls
     * {@code Bukkit.addRecipe} (Paper re-sends recipes unconditionally). A concurrent map plus a
     * fixed unmodifiable view keeps the same "callers cannot mutate it" contract at O(1).
     */
    private final Map<String, String> registeredBedrockIdentifiersView =
        java.util.Collections.unmodifiableMap(registeredBedrockIdentifiers);

    /**
     * Creates a new CustomItemsHandler.
     *
     * @param extension    the parent extension instance
     * @param sharedFolder the path to the shared data folder
     */
    public CustomItemsHandler(Extension extension, Path sharedFolder) {
        this.extension = extension;
        this.sharedFolder = sharedFolder;
        this.itemMappings = new ArrayList<>();
        this.blockIconBases = new HashMap<>();
        this.vanillaTextureBases = new java.util.HashSet<>();
        this.customIconKeys = new java.util.HashSet<>();
        this.customModelKeys = new java.util.HashSet<>();
        loadItemMappings();
        loadBlockIconBases();
    }

    /**
     * Loads item mappings from the custom_items.json file. Always clears
     * {@link #itemMappings} first so the method is safe to invoke multiple
     * times (re-read paths, future hot-reload) without producing duplicate
     * entries — the caller no longer has to remember to clear the list.
     *
     * <p>Logs are kept minimal: only a single one-line summary on success
     * and warning / error lines on failure. The previous "every-step INFO"
     * trace was useful during initial debugging but added 5-10 boot lines
     * per server start with no diagnostic value once the load path was
     * known to work.</p>
     */
    private void loadItemMappings() {
        // Idempotency: re-load always starts from a clean slate so callers
        // can invoke loadItemMappings() multiple times without worrying
        // about stacking duplicates from parseItemMappings's add() calls.
        itemMappings.clear();

        Path itemsFile = sharedFolder.resolve(ITEMS_FILE_NAME);

        if (!Files.exists(sharedFolder)) {
            try {
                Files.createDirectories(sharedFolder);
            } catch (IOException e) {
                extension.logger().error("Failed to create shared folder "
                    + sharedFolder + ": " + e.getMessage());
                return;
            }
        }

        if (!Files.exists(itemsFile)) {
            // Why: a previous revision auto-wrote a sample custom_items.json here so
            // operators saw "something" on first boot. That kept stale placeholders
            // pinned into the live data path and hid the real recovery action
            // ("ensure Paper plugin loaded first and regenerated the JSON"). The
            // extension now leaves the file absent and logs the recovery steps
            // explicitly; the schema is documented in the project README and the
            // file will be written by the Paper plugin once it finishes scanning.
            extension.logger().warning("No custom_items.json found at " + itemsFile);
            extension.logger().warning("Recovery steps:");
            extension.logger().warning("  1. Make sure the Paper-side GeyserExtra plugin loaded before Geyser.");
            extension.logger().warning("  2. Restart the server so Paper writes custom_items.json before the extension reads it.");
            extension.logger().warning("  3. See the README for the expected schema.");
            return;
        }

        try {
            String content = Files.readString(itemsFile, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            parseItemMappings(root);
            extension.logger().debug("Loaded " + itemMappings.size()
                + " custom item mapping(s) from " + itemsFile.getFileName());
        } catch (IOException e) {
            extension.logger().error("Failed to read custom_items.json ("
                + e.getClass().getSimpleName() + "): " + e.getMessage());
        } catch (Exception e) {
            extension.logger().error("Failed to parse custom_items.json ("
                + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
    }

    private void loadBlockIconBases() {
        blockIconBases.clear();
        vanillaTextureBases.clear();
        customIconKeys.clear();
        customModelKeys.clear();
        // Bundled generated data is the source of truth for safe flat vanilla
        // aliases. The runtime sidecar only adds this boot's authored icons.
        loadBlockIconBasesFromClasspath();

        Path file = sharedFolder.resolve(BLOCK_ICON_BASES_FILE);
        if (Files.exists(file)) {
            try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            JsonElement entries = root.get("useBlockIcon");
            if (entries != null && entries.isJsonObject()) {
                // Current form: { "javaName": "bedrockBlockId", ... }
                JsonObject obj = entries.getAsJsonObject();
                for (String key : obj.keySet()) {
                    blockIconBases.put(
                        key.toLowerCase(Locale.ROOT),
                        obj.get(key).getAsString().toLowerCase(Locale.ROOT));
                }
            } else if (entries != null && entries.isJsonArray()) {
                // Legacy form (pre Bedrock-id mapping): plain array of Java
                // names. A stale file from an older Paper jar may survive one
                // boot; assume the ids match across editions until Paper
                // rewrites the file.
                for (JsonElement el : entries.getAsJsonArray()) {
                    String name = el.getAsString().toLowerCase(Locale.ROOT);
                    blockIconBases.put(name, name);
                }
            }
            JsonElement customIcons = root.get("customIcons");
            if (customIcons != null && customIcons.isJsonArray()) {
                for (JsonElement el : customIcons.getAsJsonArray()) {
                    customIconKeys.add(el.getAsString().toLowerCase(Locale.ROOT));
                }
            }
            extension.logger().debug("Loaded " + blockIconBases.size()
                + " block base(s) and " + customIconKeys.size()
                + " authored icon(s) from " + file.getFileName());
            } catch (Exception e) {
                extension.logger().warning("Failed to read " + BLOCK_ICON_BASES_FILE + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        loadCustomIconsFromActivePack();
    }

    /**
     * Which of the icon keys registered with Geyser are missing from
     * {@code packIconKeys}.
     *
     * <p>Empty means the pack is safe to promote. A non-empty result means
     * promoting it would strand that many definitions on icons the pack no
     * longer contains, for the rest of the Geyser process's life.</p>
     */
    public java.util.List<String> missingRegisteredIcons(java.util.Set<String> packIconKeys) {
        java.util.List<String> missing = new ArrayList<>();
        for (String key : registeredIconKeys) {
            if (!packIconKeys.contains(key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    private void loadCustomIconsFromActivePack() {
        Path pack = sharedFolder.resolve("packs").resolve("geyserextra_auto.zip");
        if (!Files.isRegularFile(pack)) {
            return;
        }
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            Set<String> packTextures = collectPackTexturePaths(zip);
            var entry = zip.getEntry("textures/item_texture.json");
            if (entry == null) {
                return;
            }
            try (var reader = new java.io.InputStreamReader(
                zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject textureData = root.getAsJsonObject("texture_data");
                if (textureData != null) {
                    for (String key : textureData.keySet()) {
                        // item_texture.json names every definition the pack
                        // builder emitted, including the ones it pointed at a
                        // stock Bedrock path because the Java pack authored no
                        // artwork for them. Taking every key made this set
                        // claim authorship of textures the pack does not ship,
                        // which silently disabled the vanilla-fallback gate
                        // below for every such mapping.
                        if (referencesPackTexture(textureData.get(key), packTextures)) {
                            customIconKeys.add(key.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
            collectCustomModelKeys(zip);
        } catch (Exception e) {
            extension.logger().warning("Failed to inspect active auto pack icons: "
                + e.getMessage());
        }
    }

    /**
     * Records which mappings ship their own Bedrock attachable, i.e. which
     * ones actually have a custom 3D model rather than only a flat icon.
     *
     * <p>The distinction decides how loud a same-base collision deserves to
     * be. Two items that both carry a custom model really do render as one
     * another in the hand; two that only carry an icon share the vanilla base
     * model regardless, so the collision costs an inventory icon and nothing
     * in the world.</p>
     */
    /**
     * Every PNG the pack ships, keyed the way {@code item_texture.json}
     * references it — path without the {@code .png} extension.
     */
    private static Set<String> collectPackTexturePaths(ZipFile zip) {
        Set<String> paths = new HashSet<>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            String path = entries.nextElement().getName();
            if (path.startsWith("textures/") && path.endsWith(".png")) {
                paths.add(path.substring(0, path.length() - ".png".length())
                    .toLowerCase(Locale.ROOT));
            }
        }
        return paths;
    }

    /**
     * Whether an {@code item_texture.json} entry points at artwork this pack
     * actually ships, as opposed to a stock Bedrock texture path such as
     * {@code textures/items/diamond_sword}.
     *
     * <p>The distinction is the whole basis for deciding whether a definition
     * is worth registering: one pointed at a stock path renders exactly like
     * the vanilla item it shadows.</p>
     */
    static boolean referencesPackTexture(JsonElement value, Set<String> packTextures) {
        if (value == null || !value.isJsonObject()) {
            return false;
        }
        JsonElement textures = value.getAsJsonObject().get("textures");
        if (textures == null) {
            return false;
        }
        if (textures.isJsonArray()) {
            for (JsonElement element : textures.getAsJsonArray()) {
                if (element.isJsonPrimitive()
                    && packTextures.contains(element.getAsString().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
        return textures.isJsonPrimitive()
            && packTextures.contains(textures.getAsString().toLowerCase(Locale.ROOT));
    }

    private void collectCustomModelKeys(ZipFile zip) {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            String path = entries.nextElement().getName();
            if (!path.startsWith("attachables/") || !path.endsWith(".json")) {
                continue;
            }
            int slash = path.lastIndexOf('/');
            String base = path.substring(slash + 1, path.length() - ".json".length());
            customModelKeys.add(base.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * The pack sanitises {@code namespace:name} to {@code namespace_name} for
     * both icon keys and attachable filenames, so mapping names have to be
     * put through the same transform before they can be looked up.
     */
    private static String packKey(String mappingName) {
        if (mappingName == null) {
            return "";
        }
        return mappingName.toLowerCase(Locale.ROOT).replace(':', '_');
    }

    /**
     * Reads the {@code useBlockIcon} map from the build-time-generated
     * {@code bedrock/vanilla_texture_paths.json} baked into this jar.
     * Same data Paper uses, so first-boot behaviour matches later boots.
     */
    private void loadBlockIconBasesFromClasspath() {
        try (var in = CustomItemsHandler.class
                .getResourceAsStream("/bedrock/vanilla_texture_paths.json")) {
            if (in == null) {
                extension.logger().warning("No " + BLOCK_ICON_BASES_FILE
                    + " sidecar and no bundled vanilla_texture_paths.json — "
                    + "block-base custom items may lack 3D icons until Paper rebuilds the auto pack.");
                return;
            }
            JsonObject root = JsonParser.parseReader(
                new java.io.InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject obj = root.getAsJsonObject("useBlockIcon");
            if (obj != null) {
                for (String key : obj.keySet()) {
                    blockIconBases.put(
                        key.toLowerCase(Locale.ROOT),
                        obj.get(key).getAsString().toLowerCase(Locale.ROOT));
                }
            }
            JsonObject paths = root.getAsJsonObject("paths");
            if (paths != null) {
                for (String key : paths.keySet()) {
                    vanillaTextureBases.add(key.toLowerCase(Locale.ROOT));
                }
            }
            extension.logger().debug("Loaded " + blockIconBases.size()
                + " block base(s) and " + vanillaTextureBases.size()
                + " flat vanilla texture base(s) from bundled defaults");
        } catch (Exception e) {
            extension.logger().warning("Failed to read bundled vanilla_texture_paths.json: "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String bareBaseItemName(String baseItem) {
        if (baseItem == null) {
            return "";
        }
        int colon = baseItem.indexOf(':');
        String bare = colon >= 0 ? baseItem.substring(colon + 1) : baseItem;
        return bare.toLowerCase(Locale.ROOT);
    }

    /**
     * Parses item mappings from the JSON configuration.
     */
    private void parseItemMappings(JsonObject root) {
        // Support format: { "items": { "minecraft:base_item": [ { ... }, ... ] } }
        if (root.has("items")) {
            JsonObject items = root.getAsJsonObject("items");
            for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                String baseItem = entry.getKey();
                JsonArray itemArray = entry.getValue().getAsJsonArray();

                for (JsonElement element : itemArray) {
                    JsonObject itemDef = element.getAsJsonObject();
                    ItemMapping mapping = parseItemDefinition(baseItem, itemDef);
                    if (mapping != null) {
                        itemMappings.add(mapping);
                    }
                }
            }
        }

        // Support format: { "non_vanilla_items": [ { ... }, ... ] }
        if (root.has("non_vanilla_items")) {
            JsonArray nonVanillaItems = root.getAsJsonArray("non_vanilla_items");
            for (JsonElement element : nonVanillaItems) {
                JsonObject itemDef = element.getAsJsonObject();
                ItemMapping mapping = parseNonVanillaItemDefinition(itemDef);
                if (mapping != null) {
                    itemMappings.add(mapping);
                }
            }
        }
    }

    private ItemMapping parseItemDefinition(String baseItem, JsonObject itemDef) {
        try {
            String rawName = getStringOrDefault(itemDef, "name", null);
            if (rawName == null) {
                extension.logger().warning("Item definition missing 'name' field, skipping.");
                return null;
            }
            String name = sanitizeIdentifierValue(rawName, "name");

            boolean register = getBooleanOrDefault(itemDef, "register", true);
            if (!register) {
                extension.logger().debug("Skipping item '" + name + "': register=false");
                return null;
            }

            int customModelData = getIntOrDefault(itemDef, "custom_model_data", 0);
            boolean unbreakable = getBooleanOrDefault(itemDef, "unbreakable", false);
            int damagePredicate = getIntOrDefault(itemDef, "damage_predicate", -1);
            String displayName = getStringOrDefault(itemDef, "display_name", name);
            // Null means no authored icon was requested. Keeping that
            // distinction lets registration preserve the exact vanilla base
            // item render instead of manufacturing a broken custom icon key.
            String rawIcon = getStringOrDefault(itemDef, "icon", null);
            String icon = sanitizeIdentifierValue(rawIcon, "icon");
            boolean allowOffhand = getBooleanOrDefault(itemDef, "allow_offhand", true);
            int textureSize = getIntOrDefault(itemDef, "texture_size", 16);
            int creativeCategory = getIntOrDefault(itemDef, "creative_category", 0);
            String creativeGroup = getStringOrDefault(itemDef, "creative_group", null);
            // Optional PDC identifier — Phase 1 PDC-only craft result path.
            // Null in legacy JSON (or when the Paper-side scanner found no PDC)
            // means the entry is still CMD-only and registers via the legacy
            // legacyCustomModelData predicate path.
            String pdcIdentifier = getStringOrDefault(itemDef, "pdc_identifier", null);
            String itemModelId = getStringOrDefault(itemDef, "item_model", null);

            return new ItemMapping(
                baseItem, name, customModelData, unbreakable, damagePredicate,
                displayName, icon, allowOffhand, textureSize,
                false, null, 0, creativeCategory, creativeGroup,
                pdcIdentifier, itemModelId);
        } catch (Exception e) {
            extension.logger().warning("Failed to parse item definition: " + e.getMessage());
            return null;
        }
    }

    private ItemMapping parseNonVanillaItemDefinition(JsonObject itemDef) {
        try {
            String rawName = getStringOrDefault(itemDef, "name", null);
            String identifier = getStringOrDefault(itemDef, "identifier", null);
            int javaId = getIntOrDefault(itemDef, "java_id", -1);

            if (rawName == null || identifier == null || javaId < 0) {
                extension.logger().warning("Non-vanilla item definition missing required fields, skipping.");
                return null;
            }
            String name = sanitizeIdentifierValue(rawName, "name");

            String displayName = getStringOrDefault(itemDef, "display_name", name);
            String rawIcon = getStringOrDefault(itemDef, "icon", name);
            String icon = sanitizeIdentifierValue(rawIcon, "icon");
            boolean allowOffhand = getBooleanOrDefault(itemDef, "allow_offhand", true);
            int textureSize = getIntOrDefault(itemDef, "texture_size", 16);
            int creativeCategory = getIntOrDefault(itemDef, "creative_category", 0);
            String creativeGroup = getStringOrDefault(itemDef, "creative_group", null);

            return new ItemMapping(
                null, name, 0, false, -1,
                displayName, icon, allowOffhand, textureSize,
                true, identifier, javaId, creativeCategory, creativeGroup);
        } catch (Exception e) {
            extension.logger().warning("Failed to parse non-vanilla item definition: " + e.getMessage());
            return null;
        }
    }

    /**
     * Registers all loaded custom items with the Geyser event using the v2 API.
     *
     * <p>Vanilla items: pre-checks {@link GeyserDefineCustomItemsEvent#customItemDefinitions()}
     * to skip already-registered (base, CMD) pairs without provoking
     * {@code CustomItemDefinitionRegisterException}. The exception is still caught as a
     * safety net for sources that aren't visible in the snapshot (race or v1-only registrations).</p>
     *
     * <p><b>Load-order fail-safe:</b> the file is re-read from disk at
     * registration time. The constructor's earlier read may have hit an
     * absent file if the Paper plugin had not yet written
     * {@code custom_items.json} — Bukkit's {@code loadbefore} hint covers the
     * normal case, but server platforms / operator overrides occasionally
     * reverse the load order. Re-reading here is safe because
     * {@code GeyserDefineCustomItemsEvent} fires after every plugin's
     * {@code onEnable()}, so the Paper plugin has definitely finished writing
     * by this point. Without the re-read, a single boot with the wrong order
     * would silently produce zero registered items until the next server
     * restart.</p>
     */
    public void registerItems(GeyserDefineCustomItemsEvent event) {
        // Paper may have just rewritten block_icon_bases.json during this boot.
        loadBlockIconBases();

        extension.logger().debug("=== Custom Items Registration ===");
        // Fail-safe re-read with rollback.
        // Why snapshot + rollback: loadItemMappings catches I/O / parse errors
        // internally and returns silently with an empty itemMappings list.
        // Without the snapshot, a transient failure (e.g. the Paper plugin
        // is mid-write and the file is briefly locked) would clear every
        // mapping and Geyser would register zero items, leaving the server
        // in a broken state until the next restart. The snapshot preserves
        // the previous boot's mappings so a re-read miss only loses the
        // *deltas* the Paper plugin would have added this boot, not the
        // entire registry.
        List<ItemMapping> previousMappings = new ArrayList<>(itemMappings);
        int beforeCount = previousMappings.size();
        itemMappings.clear();
        try {
            loadItemMappings();
        } catch (RuntimeException ex) {
            // Defensive: loadItemMappings catches IOException / parse errors
            // internally, but a future refactor could leak a RuntimeException.
            // Restore the snapshot rather than register zero items.
            extension.logger().error("Re-read threw "
                + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                + " — restoring " + beforeCount + " prior mapping(s)");
            itemMappings.clear();
            itemMappings.addAll(previousMappings);
        }
        if (itemMappings.isEmpty() && beforeCount > 0) {
            extension.logger().warning("Re-read produced empty list while "
                + beforeCount + " mapping(s) were already loaded; restoring "
                + "previous mappings to avoid registering zero items "
                + "(likely cause: custom_items.json was unreadable at "
                + "registration time)");
            itemMappings.addAll(previousMappings);
        }
        int afterCount = itemMappings.size();
        if (afterCount != beforeCount) {
            extension.logger().debug("Mapping count changed after re-read: "
                + beforeCount + " -> " + afterCount
                + " (likely cause: Paper plugin completed its initial scan "
                + "after this extension was constructed)");
        }
        Map<Identifier, Collection<CustomItemDefinition>> existing = snapshotExistingDefinitions(event);

        // Phase 1 / V4: pre-flight detection of PDC same-base collisions. The
        // Geyser v2 API has no "specific PDC key value" predicate, so multiple
        // PDC items sharing the same base material all register with the same
        // hasComponent("minecraft:custom_data") predicate. Bedrock then uses
        // the first registered definition for every same-base PDC item; the
        // others render with the wrong icon/3D model (display name is still
        // correct because Geyser transfers it from the Java NBT).
        // Without this scan the silent collapse is invisible to operators;
        // logging it once per base material gives them a clear trail.
        Set<String> redundantPdc = redundantPdcKeys();
        if (!redundantPdc.isEmpty()) {
            extension.logger().info("[CustomItems] Skipping " + redundantPdc.size()
                + " PDC mapping(s) that restate an item already selected by"
                + " custom_model_data or item_model; the precise selector wins"
                + " and the PDC twin would only act as a catch-all on its base.");
        }

        warnAboutPdcSameBaseCollisions(redundantPdc);

        int registered = 0;
        int skippedDuplicate = 0;
        int skippedVanillaLookAlike = 0;
        int failed = 0;

        for (ItemMapping mapping : itemMappings) {
            try {
                if (!mapping.isNonVanilla && mapping.baseItem != null
                    && redundantPdc.contains(mapping.baseItem + "|" + mapping.name)) {
                    extension.logger().debug("Skipping redundant PDC mapping "
                        + mapping.name() + " (base=" + mapping.baseItem()
                        + "): already selected precisely by "
                        + bareName(mapping.name()));
                    skippedDuplicate++;
                    continue;
                }

                if (mapping.isNonVanilla) {
                    if (registerNonVanillaItem(event, mapping)) {
                        registered++;
                    } else {
                        failed++;
                    }
                    continue;
                }

                if (mapping.customModelData <= 0
                    && !mapping.hasPdcIdentifier()
                    && !mapping.hasItemModelId()) {
                    // Vanilla items with CMD<=0 and no PDC identifier would
                    // be registered with no predicate at all, which Geyser
                    // treats as a wholesale override of the base vanilla item
                    // (it logs: "Custom item ... overrides the vanilla item
                    // model ... without additional predicates" and the base
                    // item's texture is replaced by ours for every player).
                    // Drop these defensively even though the Paper-side
                    // scanner / pack reader already filter them out, in case
                    // the shared custom_items.json was hand-edited or written
                    // by an older build.
                    extension.logger().warning("Skipping " + mapping.name()
                        + " (base=" + mapping.baseItem + ", CMD=" + mapping.customModelData
                        + "): would override the vanilla item itself without a predicate");
                    skippedDuplicate++;
                    continue;
                }

                if (isVanillaLookAlike(mapping)) {
                    extension.logger().debug("Not registering " + mapping.name()
                        + " (base=" + mapping.baseItem() + ", pdc="
                        + mapping.pdcIdentifier() + "): the pack ships no icon and"
                        + " no attachable for it, so the definition would be an exact"
                        + " copy of the vanilla item that breaks its Bedrock recipes");
                    skippedVanillaLookAlike++;
                    continue;
                }

                if (shouldUseVanillaBaseFallback(mapping)) {
                    // No authored texture and no safe flat vanilla alias.
                    // Registering a custom Bedrock id would force an icon key
                    // that either does not exist or represents one block face.
                    // Leaving the definition absent makes Geyser translate the
                    // stack through its original vanilla mapping instead.
                    extension.logger().debug("Leaving " + mapping.name()
                        + " on vanilla base-item rendering (base="
                        + mapping.baseItem() + ", no authored flat icon)");
                    skippedDuplicate++;
                    continue;
                }

                Identifier baseId = Identifier.of(mapping.baseItem);
                Collection<CustomItemDefinition> existingForBase =
                    existing.getOrDefault(baseId, Collections.emptyList());

                if (mapping.customModelData > 0
                    && hasMatchingCmdPredicate(existingForBase, mapping.customModelData)) {
                    skippedDuplicate++;
                    continue;
                }

                if (registerVanillaItem(event, mapping, baseId)) {
                    registered++;
                }
            } catch (Throwable t) {
                // Distinguish expected dedup (Geyser refused to register a duplicate
                // predicate, which is normal when sibling plugins ship overlapping
                // mappings) from a genuinely unexpected registration failure (NPE,
                // type error, API mismatch). The former is INFO and lumped with
                // duplicates; the latter deserves a WARNING with the exception
                // class so an operator can investigate.
                String message = t.getMessage();
                String exClass = t.getClass().getSimpleName();
                boolean expectedDedup = "CustomItemDefinitionRegisterException".equals(exClass);
                if (expectedDedup) {
                    extension.logger().debug(
                        "Skipping " + mapping.name()
                            + " (CMD=" + mapping.customModelData() + "): "
                            + (message != null ? message : exClass));
                    skippedDuplicate++;
                } else {
                    extension.logger().error(
                        "Unexpected registration failure for " + mapping.name()
                            + " (CMD=" + mapping.customModelData() + "): "
                            + exClass + (message != null ? ": " + message : ""));
                    // Print the stack trace so the operator can diagnose. The Geyser
                    // API logger does not expose a (String, Throwable) overload, so
                    // route the trace through Throwable.printStackTrace().
                    t.printStackTrace();
                    failed++;
                }
            }
        }

        if (skippedVanillaLookAlike > 0) {
            // INFO, not debug: this is the difference between a Bedrock player
            // being able to use their gear in a smithing table or not, so an
            // operator comparing editions needs to see the count without
            // turning anything on.
            extension.logger().info("[CustomItems] Left " + skippedVanillaLookAlike
                + " PDC-only mapping(s) as plain vanilla items: the pack ships"
                + " neither an icon nor an attachable for them, so a custom"
                + " definition would look identical while breaking Bedrock"
                + " recipes that require the vanilla item id.");
        }

        extension.logger().debug("=== Registration Complete: " + registered + " registered, "
            + skippedDuplicate + " duplicate-skipped, "
            + skippedVanillaLookAlike + " vanilla-look-alike-skipped, "
            + failed + " failed ===");
    }

    /**
     * Whether registering this mapping would only produce a Bedrock item
     * indistinguishable from the vanilla one it shadows.
     *
     * <p>PDC-only mappings register with
     * {@code hasComponent("minecraft:custom_data")} because Geyser's v2 API has
     * no predicate for a specific PDC key. That predicate matches <em>any</em>
     * item carrying <em>any</em> persistent data — including ordinary gear that
     * a server plugin merely tagged (a {@code tradeable} or {@code soulbound}
     * flag, say). Such an item is then sent to Bedrock as
     * {@code geyser_custom:...} rather than {@code minecraft:diamond_sword}.</p>
     *
     * <p>When the pack ships no icon and no attachable for the mapping, the
     * definition renders with the stock Bedrock texture, so the swap buys
     * nothing visually — and costs the player every Bedrock interaction that
     * matches on the vanilla id. The smithing table is the loud one: it refuses
     * custom ingredients outright, so a tagged diamond sword can never be
     * upgraded to netherite (GeyserMC/Geyser#4706). Leaving the definition
     * unregistered lets Geyser translate the stack through its normal vanilla
     * mapping, which is both correct and identical on screen.</p>
     *
     * <p>Mappings selected by custom_model_data or item_model are exempt: their
     * predicates only fire on items that really do carry that marker, so they
     * never capture a plain vanilla item.</p>
     */
    private boolean isVanillaLookAlike(ItemMapping mapping) {
        String key = packKey(mapping.name());
        return isVanillaLookAlike(
            mapping.customModelData() > 0 || mapping.hasItemModelId(),
            mapping.hasPdcIdentifier(),
            mapping.icon(),
            customIconKeys.contains(key),
            customModelKeys.contains(key));
    }

    /**
     * The decision table behind {@link #isVanillaLookAlike(ItemMapping)}, kept
     * free of {@link ItemMapping} and of pack state so it can be pinned by
     * tests without a Geyser runtime.
     *
     * @param preciselySelected   the mapping registers on custom_model_data or
     *                            item_model, so its predicate cannot capture a
     *                            plain vanilla item
     * @param hasPdcIdentifier    the mapping registers on the catch-all
     *                            {@code hasComponent(custom_data)} predicate
     * @param explicitIcon        an icon the operator named by hand, if any
     * @param packShipsIcon       the active pack contains artwork for this key
     * @param packShipsAttachable the active pack contains an attachable for it
     */
    static boolean isVanillaLookAlike(
        boolean preciselySelected,
        boolean hasPdcIdentifier,
        String explicitIcon,
        boolean packShipsIcon,
        boolean packShipsAttachable
    ) {
        if (preciselySelected) {
            return false;
        }
        if (!hasPdcIdentifier) {
            return false;
        }
        if (explicitIcon != null && !explicitIcon.isBlank()) {
            // The operator named an icon explicitly; honour it even when the
            // active pack cannot be inspected.
            return false;
        }
        return !packShipsIcon && !packShipsAttachable;
    }

    /**
     * Whether registering this mapping would replace a correct vanilla icon
     * with a missing key or a single terrain face.
     */
    private boolean shouldUseVanillaBaseFallback(ItemMapping mapping) {
        if (mapping.icon() != null && !mapping.icon().isBlank()) {
            return false;
        }
        // packKey, not a bare lowercase: the pack sanitises "ns:name" to
        // "ns_name" for icon keys, so comparing the raw name meant every
        // namespaced mapping missed its own authored icon.
        if (customIconKeys.contains(packKey(mapping.name()))) {
            return false;
        }
        String base = bareBaseItemName(mapping.baseItem());
        return blockIconBases.containsKey(base) || !vanillaTextureBases.contains(base);
    }

    /**
     * Walks the loaded {@link ItemMapping}s and emits one WARN line per base
     * material that has more than one PDC-identified mapping (a "PDC same-base
     * collision"). Bedrock will render all of them using whichever entry Geyser
     * accepts first because the {@code hasComponent("minecraft:custom_data")}
     * predicate matches every PDC-carrying item on that material. The collision
     * is documented in the README; this warning surfaces the affected items at
     * registration time so the operator can investigate without having to
     * cross-reference logs by hand.
     */
    /**
     * Keys of PDC-only mappings that merely restate a mapping already
     * registered with a precise selector, in {@code base|barename} form.
     *
     * <p>The operator's PDC hint catalogue and the Java pack scan describe the
     * same items from two directions. {@code minecraft:blaze_rod} carries both
     * {@code abyss_cane} (custom_model_data 400013, read from the pack) and
     * {@code trinityforge:abyss_cane} (PDC only, read from the hint file) —
     * one physical item, two mappings. The pack entry is the one that decides
     * rendering, because custom_model_data is what the Java client itself
     * dispatches on; the PDC twin adds nothing.</p>
     *
     * <p>It is not merely redundant, though. Its predicate is
     * {@code hasComponent("minecraft:custom_data")}, which matches <em>every</em>
     * custom_data-bearing item on that base material. So it acts as a
     * catch-all: any item whose own custom_model_data was not registered gets
     * drawn as this one instead of falling back to the vanilla base — a
     * visibly wrong model where vanilla would have been right.</p>
     */
    private Set<String> redundantPdcKeys() {
        Set<String> preciselySelected = new HashSet<>();
        for (ItemMapping mapping : itemMappings) {
            if (mapping.isNonVanilla() || mapping.baseItem() == null) {
                continue;
            }
            if (mapping.customModelData() > 0 || mapping.hasItemModelId()) {
                // Keyed on the bare name on both sides: pack-derived entries
                // are unnamespaced (abyss_cane) while hint-derived ones are
                // not (trinityforge:abyss_cane), and an item_model mapping can
                // legitimately carry a namespace itself.
                preciselySelected.add(mapping.baseItem() + "|" + bareName(mapping.name()));
            }
        }
        Set<String> redundant = new HashSet<>();
        for (ItemMapping mapping : itemMappings) {
            if (mapping.isNonVanilla() || mapping.baseItem() == null) {
                continue;
            }
            if (mapping.customModelData() > 0 || mapping.hasItemModelId()) {
                continue;
            }
            if (!mapping.hasPdcIdentifier()) {
                continue;
            }
            String key = mapping.baseItem() + "|" + bareName(mapping.name());
            if (preciselySelected.contains(key)) {
                redundant.add(mapping.baseItem() + "|" + mapping.name());
            }
        }
        return redundant;
    }

    /** Strips a {@code namespace:} prefix, leaving the bare item name. */
    private static String bareName(String name) {
        if (name == null) {
            return null;
        }
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private void warnAboutPdcSameBaseCollisions(Set<String> redundantPdc) {
        Map<String, List<String>> pdcByBase = new java.util.LinkedHashMap<>();
        Map<String, List<String>> pdcIconOnlyByBase = new java.util.LinkedHashMap<>();
        for (ItemMapping mapping : itemMappings) {
            if (mapping.isNonVanilla() || mapping.baseItem() == null) {
                continue;
            }
            if (!mapping.hasPdcIdentifier()) {
                continue;
            }
            if (mapping.customModelData() > 0 || mapping.hasItemModelId()) {
                // Registers with its own precise predicate, so it never joins
                // the hasComponent(custom_data) pile-up.
                continue;
            }
            if (redundantPdc.contains(mapping.baseItem() + "|" + mapping.name())) {
                // Dropped before registration; counting it here would report a
                // collision that no longer happens.
                continue;
            }
            // Only mappings that ship their own attachable can actually
            // render as one another in the hand. The rest have no custom 3D
            // model at all: they already draw as the vanilla base item on
            // both editions, so a same-base collision changes nothing a
            // player sees in the world.
            boolean hasModel = customModelKeys.contains(packKey(mapping.name()));
            (hasModel ? pdcByBase : pdcIconOnlyByBase)
                .computeIfAbsent(mapping.baseItem(), k -> new ArrayList<>())
                .add(mapping.name() + " (pdc=" + mapping.pdcIdentifier() + ")");
        }
        List<String> collidingBases = collectCollidingBases(pdcByBase, "PDC model collision");
        List<String> iconOnlyBases = collectCollidingBases(pdcIconOnlyByBase, "PDC icon collision");

        if (!collidingBases.isEmpty()) {
            // One compact line per boot instead of one paragraph per base:
            // the limitation is static (Geyser v2 has no PDC-value predicate)
            // so repeating the explanation for every material adds no signal.
            extension.logger().warning(
                "[CustomItems] PDC collisions on " + collidingBases.size()
                    + " base material(s): " + String.join(", ", collidingBases)
                    + " — multiple PDC items with custom 3D models share a base,"
                    + " Bedrock renders the first registered one"
                    + " (Geyser API limitation; details at debug level)");
        }
        if (!iconOnlyBases.isEmpty()) {
            // Deliberately not a warning. These carry no attachable, so they
            // render as the plain vanilla base item either way; only the
            // inventory icon is shared. Warning about them every boot buried
            // the model collisions above, which are the ones worth acting on.
            extension.logger().debug(
                "[CustomItems] Icon-only PDC overlap on " + iconOnlyBases.size()
                    + " base material(s): " + String.join(", ", iconOnlyBases)
                    + " — these have no custom 3D model, so in-world rendering"
                    + " is the vanilla base item regardless.");
        }
    }

    /**
     * Reduces a base -> mappings map to the bases carrying more than one
     * mapping, logging the full membership of each at debug level.
     */
    private List<String> collectCollidingBases(Map<String, List<String>> byBase, String label) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byBase.entrySet()) {
            List<String> names = entry.getValue();
            if (names.size() <= 1) {
                continue;
            }
            out.add(entry.getKey() + " (" + names.size() + ")");
            extension.logger().debug("[CustomItems] " + label + " on " + entry.getKey()
                + ": " + String.join(", ", names));
        }
        return out;
    }

    /**
     * Snapshots the v2 custom item definition map at the start of registration.
     * Falls back to an empty map if Geyser exposes no v2 query (older runtime).
     */
    private Map<Identifier, Collection<CustomItemDefinition>> snapshotExistingDefinitions(
        GeyserDefineCustomItemsEvent event
    ) {
        try {
            Map<Identifier, Collection<CustomItemDefinition>> map = event.customItemDefinitions();
            return map != null ? map : Collections.emptyMap();
        } catch (Throwable t) {
            extension.logger().debug(
                "customItemDefinitions() unavailable; pre-check disabled ("
                    + t.getClass().getSimpleName() + ")");
            return Collections.emptyMap();
        }
    }

    /**
     * Registers a vanilla custom item extension via the v2 API.
     */
    private boolean registerVanillaItem(
        GeyserDefineCustomItemsEvent event,
        ItemMapping mapping,
        Identifier baseId
    ) {
        Identifier bedrockId = Identifier.of(BEDROCK_NAMESPACE, mapping.name);

        CustomItemBedrockOptions.Builder bedrockOptions = CustomItemBedrockOptions.builder()
            .allowOffhand(mapping.allowOffhand)
            // Bedrock decides "held diagonally like a tool" vs "held flat like
            // an item" from the item's own hand_equipped flag, which a vanilla
            // item has and a custom item does not inherit. Without this, every
            // registered tool — including the ones we register purely so the
            // Bedrock client will accept them in the off-hand — switches to
            // the flat item pose, which is what surfaced as "vanilla tools are
            // held like items". Items that ship their own attachable are
            // unaffected: the attachable drives their pose either way.
            .displayHandheld(isHandheldBaseItem(mapping.baseItem))
            // Without this the Bedrock client refuses the item in the smithing table's base slot.
            // See BEDROCK_SMITHING_BASE_TAG for why every item gets it.
            // Identifier.of() is resolved here rather than in a static field: building one at
            // class-load time drags in Geyser's runtime, which is absent in unit tests.
            .tag(Identifier.of(BEDROCK_SMITHING_BASE_TAG));

        // Geyser's public v2 API intentionally marks BLOCK_PLACER as a
        // non-vanilla-only component. These definitions extend vanilla Java
        // items, so attempting to attach it always throws
        // "That component cannot be used for vanilla items" even on current
        // Geyser builds. Use the generated flat icon instead.
        String iconKey = (mapping.icon != null && !mapping.icon.isBlank())
            ? mapping.icon
            : mapping.name;
        bedrockOptions.icon(iconKey);
        // Remembered so a later pack swap can be checked against it. Geyser
        // only fires GeyserDefineCustomItemsEvent at initialisation, but the
        // pack is re-registered every session, so a rebuilt pack that no
        // longer carries this key leaves the definition pointing at nothing —
        // which renders worse than the vanilla item it replaced.
        registeredIconKeys.add(iconKey.toLowerCase(Locale.ROOT));

        CreativeCategory creativeCategory = mapCreativeCategory(mapping.creativeCategory);
        if (creativeCategory != null) {
            bedrockOptions.creativeCategory(creativeCategory);
            if (mapping.creativeGroup != null && !mapping.creativeGroup.isBlank()) {
                bedrockOptions.creativeGroup(mapping.creativeGroup);
            }
        }

        Identifier modelId = mapping.hasItemModelId()
            ? Identifier.of(mapping.itemModelId())
            : baseId;
        CustomItemDefinition.Builder builder = CustomItemDefinition.builder(bedrockId, modelId)
            .bedrockOptions(bedrockOptions);

        applyCooldownCategory(builder, mapping);

        if (mapping.displayName != null && !mapping.displayName.isBlank()) {
            builder.displayName(mapping.displayName);
        }

        if (mapping.hasItemModelId()) {
            // The base-item + item-model pair is already the selector.
        } else if (mapping.customModelData > 0) {
            builder.predicate(ItemRangeDispatchPredicate.legacyCustomModelData(mapping.customModelData));
        } else if (mapping.hasPdcIdentifier()) {
            // PDC-only path: no CMD predicate exists, so we match on the
            // presence of the {@code minecraft:custom_data} component (= the
            // Java PDC blob). This is the broadest possible match — any item
            // whose Java side carries PDC data will surface as this Bedrock
            // item — but Geyser's v2 API does not yet expose a PDC-value
            // predicate, so multiple PDC items sharing the same base material
            // collapse onto whichever entry registers first. The Bedrock
            // client still distinguishes them by display name (which Geyser
            // transfers from the Java NBT), so recipe results remain visible
            // even when icons share. See plan
            // cmd-plugin-3dmodel-java-robust-meteor.md for the rationale.
            builder.predicate(ItemConditionPredicate.hasComponent(Identifier.of("minecraft:custom_data")));
        }

        // Per-item registration log lines were intentionally removed: each
        // line was ~80 bytes and a server with 1000+ custom items emitted
        // ~2000 lines on every boot, drowning out the diagnostic value of
        // the final "Registration Complete" summary. The caller logs an
        // aggregate count; per-item visibility is now only via the WARN
        // / ERROR paths for genuine failures.
        event.register(baseId, builder.build());
        if (mapping.customModelData > 0 && mapping.baseItem != null) {
            registeredBedrockIdentifiers.put(
                mapping.baseItem + "#" + mapping.customModelData, bedrockId.toString());
        }
        return true;
    }

    /**
     * The Bedrock identifiers this handler registered, keyed by {@code <base item>#<CMD>}.
     *
     * <p>Used by the recipe injector to point a corrected recipe at the real custom item.
     * Empty until {@code GeyserDefineCustomItemsEvent} has run.</p>
     */
    public Map<String, String> registeredBedrockIdentifiers() {
        return registeredBedrockIdentifiersView;
    }

    /**
     * Whether the running Geyser build accepts {@code USE_COOLDOWN} on
     * vanilla-based definitions. Older builds flag all Java data components
     * as non-vanilla-only and throw IllegalArgumentException("That component
     * cannot be used for vanilla items"); newer builds allow them. Detected
     * on the first failed attempt and disabled for the rest of the run so a
     * single compact log line replaces 500 stack traces.
     */
    private boolean cooldownComponentSupported = true;

    /**
     * Gives each Bedrock custom item its own {@code minecraft:cooldown}
     * category.
     *
     * <p>The Paper-side cooldown bridge mirrors the base-material cooldown
     * packet into this mapping-specific group. Using the base material here
     * would make every CMD/PDC item on that material animate together.</p>
     *
     * <p>The 0.05s duration is deliberately near-zero: it is only the
     * client-side <em>prediction</em> window started whenever the item is
     * used, and must stay invisible for items that have no real cooldown.
     * The actual visible duration always comes from the server packet.</p>
     */
    private void applyCooldownCategory(CustomItemDefinition.Builder builder, ItemMapping mapping) {
        if (!cooldownComponentSupported) {
            return;
        }
        String group = CustomItemCooldownGroups.forMapping(mapping.name());
        if (group == null) {
            return;
        }
        try {
            builder.component(
                JavaItemDataComponents.USE_COOLDOWN,
                JavaUseCooldown.builder()
                    .seconds(0.05f)
                    .cooldownGroup(Identifier.of(group))
                    .build());
        } catch (IllegalArgumentException e) {
            // The builder throws before mutating its component map, so the
            // definition stays valid — the item just loses the cooldown
            // overlay. Disable further attempts and tell the operator once.
            cooldownComponentSupported = false;
            extension.logger().warning("This Geyser build rejects the use_cooldown component on "
                + "vanilla-based custom items (" + e.getMessage() + "). Cooldown charge overlays "
                + "for custom items are disabled; update Geyser to a newer build to enable them.");
        }
    }

    /**
     * Registers a non-vanilla custom item via the v1 API.
     *
     * <p>TODO: migrate to v2 {@code NonVanillaCustomItemDefinition} once the v2 API for
     * non-vanilla items is exercised end-to-end in this codebase.</p>
     */
    @SuppressWarnings("deprecation")
    private boolean registerNonVanillaItem(GeyserDefineCustomItemsEvent event, ItemMapping mapping) {
        NonVanillaCustomItemData data = NonVanillaCustomItemData.builder()
            .name(mapping.name)
            .identifier(mapping.identifier)
            .javaId(mapping.javaId)
            .displayName(mapping.displayName)
            .icon(mapping.icon)
            .allowOffhand(mapping.allowOffhand)
            .textureSize(mapping.textureSize)
            .creativeCategory(
                mapping.creativeCategory > 0 && mapping.creativeCategory <= 5
                    ? mapping.creativeCategory : 0)
            .creativeGroup(
                mapping.creativeGroup != null && !mapping.creativeGroup.isBlank()
                    ? mapping.creativeGroup : null)
            .build();

        // Per-item logging removed for the same reasons as registerVanillaItem.
        event.register(data);
        return true;
    }

    /**
     * Maps the legacy 0-5 creative category integer onto Geyser's v2 enum.
     * Returns {@code null} when the value is 0 (no category) or out of range.
     */
    private CreativeCategory mapCreativeCategory(int value) {
        return switch (value) {
            case 1 -> CreativeCategory.CONSTRUCTION;
            case 2 -> CreativeCategory.NATURE;
            case 3 -> CreativeCategory.EQUIPMENT;
            case 4 -> CreativeCategory.ITEMS;
            case 5 -> CreativeCategory.ITEM_COMMAND_ONLY;
            default -> null;
        };
    }

    /**
     * Cached zero-arg accessor on {@link ItemRangeDispatchPredicate} that
     * returns the wrapped CMD integer (e.g. {@code value()} in recent Geyser
     * builds, possibly renamed in a future release). Resolved on first use
     * via reflection so a method rename surfaces as "fall back to text match"
     * rather than a class-load failure.
     */
    private static final AtomicReference<Method> RANGE_DISPATCH_VALUE_ACCESSOR =
        new AtomicReference<>();

    /**
     * Sentinel used in {@link #RANGE_DISPATCH_VALUE_ACCESSOR} to record that
     * the lookup ran and found nothing — saves a repeated reflection scan on
     * every subsequent dedup check.
     */
    private static final Method NO_ACCESSOR_FOUND;
    static {
        try {
            NO_ACCESSOR_FOUND = Object.class.getMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);  // Object#toString always exists
        }
    }

    /**
     * Does any existing definition for the same base item already carry a
     * {@code legacyCustomModelData} predicate matching {@code cmd}?
     *
     * <p>Two-phase match for resilience against Geyser API evolution:
     * <ol>
     *   <li><b>Typed match</b>: {@code instanceof ItemRangeDispatchPredicate}
     *       plus a reflectively-resolved zero-arg int accessor (cached after
     *       first call). This is the authoritative path — it survives any
     *       {@code toString()} reformatting and only breaks when Geyser
     *       renames both the public class and its accessor at the same time.</li>
     *   <li><b>Text fallback</b>: the previous {@code toString()} scan, kept
     *       as a safety net so the dedup still works when the typed accessor
     *       hasn't been resolved (e.g. the API was reshuffled but the new
     *       predicate type still surfaces the CMD integer textually).</li>
     * </ol>
     * </p>
     */
    private boolean hasMatchingCmdPredicate(Collection<CustomItemDefinition> defs, int cmd) {
        if (defs.isEmpty()) {
            return false;
        }
        String cmdLiteral = Integer.toString(cmd);
        for (CustomItemDefinition def : defs) {
            List<MinecraftPredicate<? super ItemPredicateContext>> predicates;
            try {
                predicates = def.predicates();
            } catch (Throwable ignored) {
                continue;
            }
            if (predicates == null) {
                continue;
            }
            for (MinecraftPredicate<? super ItemPredicateContext> predicate : predicates) {
                // Phase 1: type-aware match.
                if (predicate instanceof ItemRangeDispatchPredicate range) {
                    Integer typed = extractCmdViaAccessor(range);
                    if (typed != null && typed == cmd) {
                        return true;
                    }
                    // type matched but accessor unavailable / wrong value →
                    // fall through to text match for this predicate
                }
                // Phase 2: text fallback.
                String repr = String.valueOf(predicate);
                if (!containsCmdLiteral(repr, cmdLiteral)) {
                    continue;
                }
                String lower = repr.toLowerCase();
                if (lower.contains("legacy_custom_model_data")
                    || lower.contains("legacycustommodeldata")
                    || lower.contains("custom_model_data")
                    || lower.contains("custommodeldata")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the CMD integer wrapped inside an
     * {@link ItemRangeDispatchPredicate}, or {@code null} when no zero-arg
     * int accessor is available on the runtime Geyser API. Caches the
     * resolved method (or the not-found sentinel) so the reflection scan
     * runs at most once per JVM.
     */
    private static Integer extractCmdViaAccessor(ItemRangeDispatchPredicate range) {
        Method cached = RANGE_DISPATCH_VALUE_ACCESSOR.get();
        if (cached == null) {
            cached = resolveValueAccessor(range.getClass());
            RANGE_DISPATCH_VALUE_ACCESSOR.compareAndSet(null, cached);
        }
        if (cached == NO_ACCESSOR_FOUND) {
            return null;
        }
        try {
            Object result = cached.invoke(range);
            if (result instanceof Number n) {
                return n.intValue();
            }
        } catch (Throwable ignored) {
            // accessor present but invocation failed → fall back to text path
        }
        return null;
    }

    /**
     * Looks for a zero-arg public <b>instance</b> accessor on {@code clazz}
     * that returns the wrapped CMD integer. Tries the names most likely to
     * be in use across Geyser versions ({@code value}, {@code customModelData},
     * {@code legacyCustomModelData}), preferring primitive {@code int}
     * returns. Returns {@link #NO_ACCESSOR_FOUND} when none match so the
     * caller can short-circuit subsequent lookups.
     *
     * <p>Static methods with the same name (e.g. a hypothetical
     * {@code static int defaultValue()}) are explicitly excluded — invoking
     * one against the predicate instance would return a value unrelated to
     * the wrapped CMD and silently produce false duplicate-hit decisions.</p>
     */
    private static Method resolveValueAccessor(Class<?> clazz) {
        String[] candidateNames = {
            "value", "customModelData", "legacyCustomModelData"
        };
        for (String name : candidateNames) {
            for (Method m : clazz.getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!name.equals(m.getName())) continue;
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Class<?> ret = m.getReturnType();
                if (ret == int.class || ret == Integer.class
                    || Number.class.isAssignableFrom(ret)) {
                    return m;
                }
            }
        }
        return NO_ACCESSOR_FOUND;
    }

    /**
     * Word-boundary-aware containment check: ensures the CMD literal is not a substring
     * of a longer number (e.g. avoids matching {@code 8778216} when looking for {@code 877821}).
     */
    private boolean containsCmdLiteral(String repr, String cmdLiteral) {
        int idx = 0;
        while ((idx = repr.indexOf(cmdLiteral, idx)) >= 0) {
            int before = idx - 1;
            int after = idx + cmdLiteral.length();
            boolean leftOk = before < 0 || !Character.isDigit(repr.charAt(before));
            boolean rightOk = after >= repr.length() || !Character.isDigit(repr.charAt(after));
            if (leftOk && rightOk) {
                return true;
            }
            idx = after;
        }
        return false;
    }

    /**
     * Vanilla base items Bedrock renders as held-in-hand rather than flat,
     * matched by suffix so modded/plugin tiers (copper_sword, netherite_spear,
     * whatever ValhallaMMO adds next) are covered without a per-item list.
     */
    private static final String[] HANDHELD_BASE_SUFFIXES = {
        "_sword", "_axe", "_pickaxe", "_shovel", "_hoe", "_spear"
    };

    /** Handheld base items whose id carries no tool suffix to match on. */
    private static final Set<String> HANDHELD_BASE_ITEMS = Set.of(
        "minecraft:bow", "minecraft:crossbow", "minecraft:trident", "minecraft:mace",
        "minecraft:fishing_rod", "minecraft:carrot_on_a_stick",
        "minecraft:warped_fungus_on_a_stick", "minecraft:stick", "minecraft:shears",
        "minecraft:brush", "minecraft:spyglass"
    );

    /**
     * Whether Bedrock renders {@code baseItem} in the hand-equipped pose.
     *
     * <p>Kept as a table rather than derived from the Java item because the
     * extension has no Bukkit/Material access — it sees only the identifier
     * string that the Paper side wrote into custom_items.json.</p>
     */
    private static boolean isHandheldBaseItem(String baseItem) {
        if (baseItem == null) {
            return false;
        }
        if (HANDHELD_BASE_ITEMS.contains(baseItem)) {
            return true;
        }
        for (String suffix : HANDHELD_BASE_SUFFIXES) {
            if (baseItem.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sanitizes a name/identifier value for Geyser compatibility.
     * Geyser only allows [a-z0-9_\-./]+ in identifier values.
     */
    private String sanitizeIdentifierValue(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        String sanitized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-./]", "_");
        if (!sanitized.equals(value)) {
            // Routine and expected for namespaced ids (foo:bar → foo_bar);
            // debug-level so 500-item servers don't get a wall of warnings.
            extension.logger().debug("Sanitized " + fieldName + " '" + value + "' -> '" + sanitized
                + "' (invalid characters replaced with '_')");
        }
        return sanitized;
    }

    private String getStringOrDefault(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }

    private int getIntOrDefault(JsonObject obj, String key, int defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsInt();
        }
        return defaultValue;
    }

    private boolean getBooleanOrDefault(JsonObject obj, String key, boolean defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsBoolean();
        }
        return defaultValue;
    }

    /**
     * Returns an unmodifiable list of loaded item mappings.
     */
    public List<ItemMapping> getItemMappings() {
        return Collections.unmodifiableList(itemMappings);
    }

    /**
     * Internal record for item mapping data.
     *
     * @param creativeCategory legacy 0-5 category code; mapped to v2 {@link CreativeCategory} at register time
     * @param creativeGroup    Bedrock creative group for sub-categorization
     * @param pdcIdentifier    stable PersistentDataContainer identifier (e.g. {@code "oraxen:fire_sword"})
     *                         that triggers the {@code hasComponent("minecraft:custom_data")} predicate
     *                         path. Null for legacy CMD-only mappings — preserves pre-feature behaviour.
     */
    public record ItemMapping(
        String baseItem,
        String name,
        int customModelData,
        boolean unbreakable,
        int damagePredicate,
        String displayName,
        String icon,
        boolean allowOffhand,
        int textureSize,
        boolean isNonVanilla,
        String identifier,
        int javaId,
        int creativeCategory,
        String creativeGroup,
        String pdcIdentifier,
        String itemModelId
    ) {
        /**
         * Backward-compatible 14-arg constructor used by call sites that
         * don't carry PDC information (legacy CMD-only mappings,
         * non-vanilla items). Delegates to the canonical constructor with
         * {@code pdcIdentifier=null}.
         */
        public ItemMapping(
            String baseItem,
            String name,
            int customModelData,
            boolean unbreakable,
            int damagePredicate,
            String displayName,
            String icon,
            boolean allowOffhand,
            int textureSize,
            boolean isNonVanilla,
            String identifier,
            int javaId,
            int creativeCategory,
            String creativeGroup
        ) {
            this(baseItem, name, customModelData, unbreakable, damagePredicate,
                 displayName, icon, allowOffhand, textureSize,
                 isNonVanilla, identifier, javaId, creativeCategory, creativeGroup,
                 null, null);
        }

        public ItemMapping(
            String baseItem,
            String name,
            int customModelData,
            boolean unbreakable,
            int damagePredicate,
            String displayName,
            String icon,
            boolean allowOffhand,
            int textureSize,
            boolean isNonVanilla,
            String identifier,
            int javaId,
            int creativeCategory,
            String creativeGroup,
            String pdcIdentifier
        ) {
            this(baseItem, name, customModelData, unbreakable, damagePredicate,
                displayName, icon, allowOffhand, textureSize, isNonVanilla,
                identifier, javaId, creativeCategory, creativeGroup,
                pdcIdentifier, null);
        }

        /** True when this mapping is identified by a stable PDC value rather than CMD. */
        public boolean hasPdcIdentifier() {
            return pdcIdentifier != null && !pdcIdentifier.isBlank();
        }

        public boolean hasItemModelId() {
            return itemModelId != null && !itemModelId.isBlank();
        }
    }
}
