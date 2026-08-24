package com.geyserextra.paper.pack;

import com.geyserextra.core.api.CustomItemMapping;
import com.geyserextra.core.registry.ItemMappingRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reconciles the persistent item-mapping registry with what the operator's Java
 * resource pack actually defines <em>right now</em>.
 *
 * <p>Why this exists — the registry used to be strictly append-only ("the first
 * registration wins"), which turned two transient failures into permanent damage:
 *
 * <ol>
 *   <li><b>Placeholder display names never healed.</b> When a registration ran
 *       while the pack was unreadable (the extract-directory race, a partial
 *       download) or a nameless ItemStack was scanned, the entry was written
 *       with the base material's vanilla name ("糸", "String"). Because the
 *       entry now <em>had</em> a display name, every later upgrade path
 *       declined to touch it — Bedrock players saw dozens of creative items all
 *       named after their base material, forever.</li>
 *   <li><b>Re-based items left stale ghosts.</b> When an item kept its CMD but
 *       changed base material across a config generation (the thread items:
 *       armor-trim templates → string), the old (base, cmd) entry stayed
 *       registered. It still appeared in the Bedrock creative inventory with
 *       the <em>old</em> base's texture — reported as "旧テクスチャ".</li>
 * </ol>
 *
 * <p>The pack is the live truth (it is what Java players see), so this pass
 * makes the registry converge toward it while leaving genuinely
 * operator-curated data alone:
 *
 * <ul>
 *   <li><b>Add</b>: a pack (base, cmd) with no registry entry is registered,
 *       exactly like the old pre-population pass.</li>
 *   <li><b>Heal</b>: an auto-named entry ({@code custom_<base>_<cmd>}) whose
 *       display name is missing <em>or equals a known placeholder</em> (the
 *       base material's vanilla/prettified name) gets the pack-resolved name.
 *       Entries with operator-chosen names or non-placeholder display names
 *       are never touched.</li>
 *   <li><b>Evict</b>: an entry whose (base, cmd) is absent from every
 *       configured pack, but whose CMD <em>and</em> display name both match a
 *       live pack entry on a different base, is a stale ghost of a re-based
 *       item and is unregistered. Requiring both to match keeps entries that
 *       merely share a name (or merely share a CMD) safe; if the ghost's item
 *       genuinely still circulates, the runtime scanner re-registers it within
 *       minutes of anyone touching one.</li>
 * </ul>
 */
public final class PackRegistryReconciler {

    /** What one reconcile pass did, for the caller's log line. */
    public record Result(int added, int healed, int evicted) {
        public boolean changedAnything() {
            return added > 0 || healed > 0 || evicted > 0;
        }
    }

    private PackRegistryReconciler() {
    }

    /**
     * Runs one reconcile pass. Pure with respect to Bukkit — everything
     * environment-shaped is injected, so unit tests can drive it with plain
     * maps and lambdas.
     *
     * @param registry the live registry (mutated in place)
     * @param packEntries merged scan result of every configured Java pack
     * @param langDisplayResolver resolves a pack entry to its lang-file display
     *        name, or {@code null} when the lang file has no entry — this must
     *        NOT fall back to the material name, or healing would ping-pong
     *        between two placeholders
     * @param fallbackDisplayForBase display name used for newly added entries
     *        when the lang resolver returns null (the prettified material name)
     * @param placeholderDisplaysForBase every display name that counts as "not
     *        actually chosen by anyone" for a base item (vanilla lang name,
     *        prettified name); entries carrying one of these may be healed
     * @param infoLog sink for one line per eviction so the operator can see
     *        what disappeared and why
     */
    public static Result reconcile(
        ItemMappingRegistry registry,
        Map<JavaPackReader.CmdKey, JavaPackReader.JavaModelDefinition> packEntries,
        BiFunction<JavaPackReader.CmdKey, JavaPackReader.JavaModelDefinition, String> langDisplayResolver,
        Function<String, String> fallbackDisplayForBase,
        Function<String, Set<String>> placeholderDisplaysForBase,
        Consumer<String> infoLog
    ) {
        int added = 0;
        int healed = 0;
        int evicted = 0;

        // Lang-resolved display names per CMD, for the eviction pass. Only
        // real lang resolutions go in here — a prettified fallback matching a
        // ghost's display name would prove nothing.
        Map<Integer, Set<String>> packDisplaysByCmd = new HashMap<>();

        for (Map.Entry<JavaPackReader.CmdKey, JavaPackReader.JavaModelDefinition> entry
            : packEntries.entrySet()) {
            JavaPackReader.CmdKey key = entry.getKey();
            if (key.cmd() <= 0) {
                // Defence-in-depth: registering CMD 0 would build a Geyser
                // definition with no predicate, wholesale-overriding the base
                // vanilla item for every player.
                continue;
            }
            String resolved = langDisplayResolver.apply(key, entry.getValue());
            if (resolved != null && resolved.isBlank()) {
                resolved = null;
            }
            if (resolved != null) {
                packDisplaysByCmd.computeIfAbsent(key.cmd(), c -> new HashSet<>()).add(resolved);
            }

            CustomItemMapping existing =
                registry.getByCustomModelData(key.baseItem(), key.cmd()).orElse(null);
            if (existing != null) {
                if (resolved != null
                    && !resolved.equals(existing.displayName())
                    && isAutoName(existing.name(), key.baseItem(), key.cmd())
                    && isPlaceholderDisplay(existing, placeholderDisplaysForBase)) {
                    // register() replaces by name, so the entry keeps its
                    // identifier, icon, category — only the display heals.
                    registry.register(existing.withDisplayName(resolved));
                    healed++;
                }
                continue;
            }

            String autoName = autoMappingName(key.baseItem(), key.cmd());
            if (registry.contains(autoName)) {
                // Same auto name, different (base, cmd) is impossible (the name
                // embeds both), so this only guards against a registry whose
                // CMD index is out of sync with its name index.
                continue;
            }
            registry.register(new CustomItemMapping(
                autoName,
                key.baseItem(),
                key.cmd(),
                false,   // unbreakable unknown from pack alone
                resolved != null ? resolved : fallbackDisplayForBase.apply(key.baseItem()),
                null,    // icon falls back to name via item_texture.json
                CustomItemMapping.CREATIVE_CATEGORY_ITEMS,
                null,    // creative group unset
                true     // register with Geyser
            ));
            added++;
        }

        // Eviction pass over a snapshot — unregister() mutates the backing map.
        for (CustomItemMapping mapping : new ArrayList<>(registry.getMappings())) {
            if (mapping.customModelData() <= 0 || !mapping.hasDisplayName()) {
                continue;
            }
            if (packEntries.containsKey(
                new JavaPackReader.CmdKey(mapping.baseItem(), mapping.customModelData()))) {
                continue;
            }
            Set<String> liveDisplays = packDisplaysByCmd.get(mapping.customModelData());
            if (liveDisplays == null || !liveDisplays.contains(mapping.displayName())) {
                continue;
            }
            registry.unregister(mapping.name());
            evicted++;
            infoLog.accept("evicted stale mapping " + mapping.name()
                + " (" + mapping.baseItem() + " cmd=" + mapping.customModelData()
                + ", \"" + mapping.displayName() + "\") — the Java pack now defines an item"
                + " with the same CMD and display name on a different base item; the old"
                + " base is no longer in any configured pack");
        }

        return new Result(added, healed, evicted);
    }

    /**
     * The auto-generated mapping name for a (base, cmd) pair. Mirrors
     * {@code CustomItemScanner.generateMappingName}'s fallback so both paths
     * produce (and recognise) the same names.
     */
    public static String autoMappingName(String baseItem, int cmd) {
        String trimmed = baseItem.startsWith("minecraft:")
            ? baseItem.substring("minecraft:".length())
            : baseItem;
        return "custom_" + trimmed + "_" + cmd;
    }

    private static boolean isAutoName(String name, String baseItem, int cmd) {
        return name.equals(autoMappingName(baseItem, cmd));
    }

    private static boolean isPlaceholderDisplay(
        CustomItemMapping mapping,
        Function<String, Set<String>> placeholderDisplaysForBase
    ) {
        if (!mapping.hasDisplayName()) {
            return true;
        }
        Set<String> placeholders = placeholderDisplaysForBase.apply(mapping.baseItem());
        return placeholders != null && placeholders.contains(mapping.displayName());
    }

    /** Convenience list form used by tests. */
    static List<CustomItemMapping> snapshot(ItemMappingRegistry registry) {
        return new ArrayList<>(registry.getMappings());
    }
}
