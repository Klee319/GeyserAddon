package com.geyserextra.core.api;

import com.geyserextra.core.config.DynamicResourcePackEntry;

import java.util.Collection;

/**
 * SPI for plugins that host their own Java Edition resource packs at runtime
 * URLs and want GeyserExtra to mirror them into the Bedrock auto-pack.
 *
 * <p>Forward-looking extension point: in the current release this interface
 * is declared but not yet enumerated by {@code GeyserExtraPaper}. A future
 * release is intended to discover implementations via Bukkit's
 * {@code ServicesManager} and merge their entries with the config-driven
 * {@code customItems.dynamicResourcePackUrls} list.</p>
 *
 * <p>Implementations should be idempotent and side-effect free; the method
 * is expected to be callable at any time during plugin enable.</p>
 */
public interface JavaResourcePackProvider {

    /**
     * Returns the dynamic resource pack entries this plugin wants
     * GeyserExtra to mirror. May return an empty collection but must
     * not return {@code null}.
     */
    Collection<DynamicResourcePackEntry> dynamicPacks();
}
