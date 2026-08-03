package com.geyserextra.paper.pack;

import org.bukkit.Material;

/**
 * Single resolver for "what should a Bedrock player see as the name of this
 * vanilla item".
 *
 * <p>There are two places that need this answer — the Paper-side scanner that
 * writes {@code custom_items.json} (and from it the pack's {@code texts/*.lang})
 * and the packet path that injects a display name into outgoing stacks. They
 * used to answer it differently: the scanner walked lang → Bedrock's own name →
 * prettified id, while the packet path went straight to the prettified id. The
 * result was that a stack carrying only a PDC marker — TrinityForge stamps
 * those on ordinary tools, blocks and bottles — arrived at a Japanese client
 * renamed to "Diamond Pickaxe", overwriting the name the client already had.
 *
 * <p>Resolution order, most authoritative first:
 * <ol>
 *   <li>the operator's Java pack lang file, which is exactly what a Java client
 *       would render for the same item;</li>
 *   <li>Bedrock's own name for the item, generated at build time from Mojang's
 *       {@code bedrock-samples} pack — correct for the edition the text is
 *       heading to, and localised;</li>
 *   <li>the prettified Java identifier, English and last-ditch.</li>
 * </ol>
 */
public final class VanillaDisplayNames {

    private static final String UNKNOWN = "Unknown";

    private VanillaDisplayNames() {}

    /**
     * @param material  the base material
     * @param langReader the operator's Java pack lang file, or {@code null}
     * @return a player-facing name; never null
     */
    public static String resolve(Material material, JavaPackLangReader langReader) {
        if (material == null) {
            return UNKNOWN;
        }
        String key = material.getKey().getKey();

        if (langReader != null && !langReader.isEmpty()) {
            String resolved = langReader.resolve("item.minecraft." + key);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
            if (material.isBlock()) {
                resolved = langReader.resolve("block.minecraft." + key);
                if (resolved != null && !resolved.isBlank()) {
                    return resolved;
                }
            }
        }

        String bedrockName = BedrockVanillaTexturePaths.vanillaName(key);
        if (bedrockName != null && !bedrockName.isBlank()) {
            return bedrockName;
        }
        return prettify(key);
    }

    /** {@code diamond_sword → "Diamond Sword"}. */
    public static String prettify(String raw) {
        if (raw == null || raw.isEmpty()) {
            return UNKNOWN;
        }
        StringBuilder pretty = new StringBuilder(raw.length());
        boolean upcaseNext = true;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '_' || c == '/' || c == ':') {
                pretty.append(' ');
                upcaseNext = true;
            } else if (upcaseNext) {
                pretty.append(Character.toUpperCase(c));
                upcaseNext = false;
            } else {
                pretty.append(c);
            }
        }
        return pretty.toString();
    }
}
