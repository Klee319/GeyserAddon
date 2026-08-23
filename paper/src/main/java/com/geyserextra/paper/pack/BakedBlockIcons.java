package com.geyserextra.paper.pack;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventory icons baked at build time for blocks, looked up by Bedrock block id.
 *
 * <p>Java renders a block model live in the slot. Bedrock custom items can only name a flat PNG,
 * and Geyser refuses {@code geysermc:block_placer} on definitions that extend a vanilla Java item
 * (every component in that namespace reports {@code vanilla() == false}). A block-based custom item
 * therefore had no icon it could point at, so {@code CustomItemsHandler} left it unregistered — and
 * an unregistered item cannot be named by an injected Bedrock recipe. That is the whole reason
 * block-based crafts were impossible on Bedrock.</p>
 *
 * <p>{@code BedrockBlockIconGenerator} bakes the same isometric cube Java draws and ships it in the
 * jar; this class hands those bytes to the pack builder.</p>
 *
 * <p>Absence is normal, not an error: blocks that are not full cubes (decorated pots, lecterns,
 * beacons) are deliberately not baked, because a cube of their side texture would misrepresent
 * them. Those items keep the previous vanilla-base fallback.</p>
 */
public final class BakedBlockIcons {

    /** Where {@code generateBlockIcons} writes, relative to the jar root. */
    private static final String RESOURCE_ROOT = "/bedrock/block_icons/";

    /**
     * Cache of resolved lookups. Holds negatives as a zero-length array so a block with no baked
     * icon costs one classpath probe per build rather than one per mapping that uses it — the
     * compressed-block families put dozens of mappings on the same base.
     */
    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();

    private static final byte[] ABSENT = new byte[0];

    private BakedBlockIcons() {
    }

    /**
     * Returns the baked PNG for {@code bedrockBlockId}, or {@code null} when none was baked.
     *
     * @param bedrockBlockId bare Bedrock block id, e.g. {@code stone} or {@code oak_log}
     */
    public static byte[] iconFor(String bedrockBlockId) {
        if (bedrockBlockId == null || bedrockBlockId.isBlank()) {
            return null;
        }
        String key = bedrockBlockId.toLowerCase(Locale.ROOT);
        byte[] cached = CACHE.computeIfAbsent(key, BakedBlockIcons::load);
        return cached == ABSENT ? null : cached;
    }

    private static byte[] load(String bedrockBlockId) {
        // getResourceAsStream, not a Path: at runtime these live inside the shaded jar.
        try (InputStream in = BakedBlockIcons.class.getResourceAsStream(
            RESOURCE_ROOT + bedrockBlockId + ".png")) {
            if (in == null) {
                return ABSENT;
            }
            byte[] bytes = in.readAllBytes();
            return bytes.length == 0 ? ABSENT : bytes;
        } catch (IOException ex) {
            return ABSENT;
        }
    }
}
