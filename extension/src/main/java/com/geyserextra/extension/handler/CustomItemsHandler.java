/*
 * GeyserExtra Extension - Custom Items Handler
 * Loads and registers custom items from shared configuration file.
 */
package com.geyserextra.extension.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.item.custom.NonVanillaCustomItemData;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.api.predicate.MinecraftPredicate;
import org.geysermc.geyser.api.predicate.context.item.ItemPredicateContext;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
 * {@code AutoBedrockPackBuilder} (Paper module) generates a textureless BE pack
 * whose {@code item_texture.json} points each generated icon key at the matching
 * vanilla BE texture path, so BE clients fall back to bundled vanilla textures
 * without operators authoring a custom pack.</p>
 */
public class CustomItemsHandler {

    private static final String ITEMS_FILE_NAME = "custom_items.json";

    /** Bedrock-side namespace for items registered by this extension. */
    private static final String BEDROCK_NAMESPACE = "geyserextra";

    private final Extension extension;
    private final Path sharedFolder;
    private final List<ItemMapping> itemMappings;

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
        loadItemMappings();
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
            extension.logger().info("Loaded " + itemMappings.size()
                + " custom item mapping(s) from " + itemsFile.getFileName());
        } catch (IOException e) {
            extension.logger().error("Failed to read custom_items.json ("
                + e.getClass().getSimpleName() + "): " + e.getMessage());
        } catch (Exception e) {
            extension.logger().error("Failed to parse custom_items.json ("
                + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
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
                extension.logger().info("Skipping item '" + name + "': register=false");
                return null;
            }

            int customModelData = getIntOrDefault(itemDef, "custom_model_data", 0);
            boolean unbreakable = getBooleanOrDefault(itemDef, "unbreakable", false);
            int damagePredicate = getIntOrDefault(itemDef, "damage_predicate", -1);
            String displayName = getStringOrDefault(itemDef, "display_name", name);
            String icon = getStringOrDefault(itemDef, "icon", name);
            boolean allowOffhand = getBooleanOrDefault(itemDef, "allow_offhand", true);
            int textureSize = getIntOrDefault(itemDef, "texture_size", 16);
            int creativeCategory = getIntOrDefault(itemDef, "creative_category", 0);
            String creativeGroup = getStringOrDefault(itemDef, "creative_group", null);

            return new ItemMapping(
                baseItem, name, customModelData, unbreakable, damagePredicate,
                displayName, icon, allowOffhand, textureSize,
                false, null, 0, creativeCategory, creativeGroup);
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
            String icon = getStringOrDefault(itemDef, "icon", name);
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
        extension.logger().info("=== Custom Items Registration ===");
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
            extension.logger().info("Mapping count changed after re-read: "
                + beforeCount + " -> " + afterCount
                + " (likely cause: Paper plugin completed its initial scan "
                + "after this extension was constructed)");
        }
        Map<Identifier, Collection<CustomItemDefinition>> existing = snapshotExistingDefinitions(event);

        int registered = 0;
        int skippedDuplicate = 0;
        int failed = 0;

        for (ItemMapping mapping : itemMappings) {
            try {
                if (mapping.isNonVanilla) {
                    if (registerNonVanillaItem(event, mapping)) {
                        registered++;
                    } else {
                        failed++;
                    }
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
                    extension.logger().info(
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

        extension.logger().info("=== Registration Complete: " + registered + " registered, "
            + skippedDuplicate + " duplicate-skipped, " + failed + " failed ===");
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
            extension.logger().info(
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
            .allowOffhand(mapping.allowOffhand);

        if (mapping.icon != null && !mapping.icon.isBlank()) {
            bedrockOptions.icon(mapping.icon);
        }

        CreativeCategory creativeCategory = mapCreativeCategory(mapping.creativeCategory);
        if (creativeCategory != null) {
            bedrockOptions.creativeCategory(creativeCategory);
            if (mapping.creativeGroup != null && !mapping.creativeGroup.isBlank()) {
                bedrockOptions.creativeGroup(mapping.creativeGroup);
            }
        }

        CustomItemDefinition.Builder builder = CustomItemDefinition.builder(bedrockId, baseId)
            .bedrockOptions(bedrockOptions);

        if (mapping.displayName != null && !mapping.displayName.isBlank()) {
            builder.displayName(mapping.displayName);
        }

        if (mapping.customModelData > 0) {
            builder.predicate(ItemRangeDispatchPredicate.legacyCustomModelData(mapping.customModelData));
        }

        // Per-item registration log lines were intentionally removed: each
        // line was ~80 bytes and a server with 1000+ custom items emitted
        // ~2000 lines on every boot, drowning out the diagnostic value of
        // the final "Registration Complete" summary. The caller logs an
        // aggregate count; per-item visibility is now only via the WARN
        // / ERROR paths for genuine failures.
        event.register(baseId, builder.build());
        return true;
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
     * Sanitizes a name/identifier value for Geyser compatibility.
     * Geyser only allows [a-z0-9_\-./]+ in identifier values.
     */
    private String sanitizeIdentifierValue(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        String sanitized = value.toLowerCase().replaceAll("[^a-z0-9_\\-./]", "_");
        if (!sanitized.equals(value)) {
            extension.logger().warning("Sanitized " + fieldName + " '" + value + "' -> '" + sanitized
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
        String creativeGroup
    ) {}
}
