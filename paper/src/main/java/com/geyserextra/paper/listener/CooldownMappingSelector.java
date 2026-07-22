package com.geyserextra.paper.listener;

import com.geyserextra.core.api.CustomItemMapping;
import com.geyserextra.core.util.CustomItemCooldownGroups;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Pure mapping-selection logic for {@link CooldownBridgeListener}.
 *
 * <p>Kept free of Bukkit types so unit tests can exercise it without a
 * Paper/Bukkit runtime on the classpath.</p>
 */
final class CooldownMappingSelector {

    private static final String MINECRAFT_NAMESPACE = "minecraft";

    private CooldownMappingSelector() {}

    static Optional<CustomItemMapping> select(
        String sourceGroup,
        Optional<CustomItemMapping> mainHand,
        Optional<CustomItemMapping> offHand,
        Optional<CustomItemMapping> recent
    ) {
        if (sourceGroup == null || CustomItemCooldownGroups.isSynthetic(sourceGroup)) {
            return Optional.empty();
        }
        // PlayerInteractEvent / melee damage identify the item that actually
        // initiated the use, so the one-shot recent mapping must win when both
        // hands contain different CMD items on the same material — and when a
        // plugin cooldown group has no material metadata to match against.
        return Stream.of(recent, mainHand, offHand)
            .flatMap(Optional::stream)
            .filter(mapping -> groupCanApplyTo(sourceGroup, mapping))
            .findFirst();
    }

    private static boolean groupCanApplyTo(String sourceGroup, CustomItemMapping mapping) {
        if (isCustomPluginCooldownGroup(sourceGroup)) {
            // Arbitrary plugin NamespacedKeys (valhallammo:*, mythic:*, …)
            // have no material base to compare; attribute by held/recent item.
            return true;
        }
        return normalize(sourceGroup).equals(normalize(mapping.baseItem()));
    }

    private static boolean isCustomPluginCooldownGroup(String sourceGroup) {
        String normalized = normalize(sourceGroup);
        int colon = normalized.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        return !MINECRAFT_NAMESPACE.equals(normalized.substring(0, colon));
    }

    /**
     * Normalises cooldown / base-item ids so {@code golden_axe} and
     * {@code minecraft:golden_axe} compare equal (Paper NamespacedKey rules
     * without depending on Bukkit at test time).
     */
    private static String normalize(String group) {
        if (group == null) {
            return "";
        }
        String trimmed = group.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!trimmed.contains(":")) {
            return MINECRAFT_NAMESPACE + ":" + trimmed;
        }
        return trimmed;
    }
}
