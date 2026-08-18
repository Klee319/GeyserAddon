package com.geyserextra.paper.skin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Resolves a Bedrock player's Java-side skin from the public GeyserMC skin API.
 *
 * <p><b>Why this exists.</b> Geyser uploads every Bedrock player's skin to the
 * GeyserMC global API, which hands back a Mojang-signed {@code textures}
 * property. Floodgate is supposed to graft that property onto the player's
 * profile so Java clients render it. On this network it does not arrive: the
 * {@code GameProfile} that reaches the Paper backend carries no {@code textures}
 * property at all, which is why the Java view, the tab list, the DiscordSRV
 * avatar and player heads all fall back to Steve simultaneously. The upload half
 * is demonstrably healthy — every Bedrock player on the server already has a
 * signed, recently-updated skin at
 * {@code https://api.geysermc.org/v2/skin/<xuid>}. Only the apply half is
 * broken, and it is broken inside Floodgate/Velocity where this project cannot
 * reach.</p>
 *
 * <p>So this class goes to the same source Floodgate would have used and lets
 * {@link com.geyserextra.paper.listener.BedrockSkinApplier} put the property on
 * the profile itself. That is a fix at our layer, not a workaround for a bug in
 * ours: the authoritative skin data is public, unchanged, and identical to what
 * Floodgate would have applied.</p>
 *
 * <p><b>The XUID.</b> Floodgate encodes a Bedrock account's XUID in the low 64
 * bits of the fake UUID it mints ({@code 00000000-0000-0000-XXXX-XXXXXXXXXXXX}).
 * The API is keyed by that XUID in decimal, so no extra lookup is needed.</p>
 *
 * <p><b>Threading.</b> Every network call runs on Bukkit's async scheduler and
 * every callback is handed back on the main thread. {@link #cached(UUID)} is the
 * only method safe to call from a hot synchronous path (the skull scanner runs
 * during inventory opens); it never blocks and never fetches.</p>
 */
public final class BedrockSkinService {

    /** Public GeyserMC skin lookup, keyed by XUID in decimal. */
    private static final String API_BASE = "https://api.geysermc.org/v2/skin/";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * How long a failed lookup is remembered before another attempt is made.
     *
     * <p>Without it, a player whose skin genuinely is not on the API (a brand
     * new account, or an outage) would trigger a fresh HTTP request on every
     * chunk scan that happens to contain their head.</p>
     */
    private static final long FAILURE_BACKOFF_MILLIS = Duration.ofMinutes(10).toMillis();

    /**
     * A Mojang-signed skin, in exactly the shape a {@code textures} profile
     * property needs.
     *
     * @param value     base64 profile JSON, as signed by Mojang
     * @param signature Mojang's signature over {@code value}
     * @param textureId the texture hash, i.e. the tail of the
     *                  {@code textures.minecraft.net} URL — this is what skull
     *                  registration needs
     */
    public record BedrockSkin(String value, String signature, String textureId) {
        public BedrockSkin {
            Objects.requireNonNull(value, "value must not be null");
            Objects.requireNonNull(signature, "signature must not be null");
            Objects.requireNonNull(textureId, "textureId must not be null");
        }
    }

    private final Plugin plugin;
    private final HttpClient http;

    private final Map<UUID, BedrockSkin> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> failedAt = new ConcurrentHashMap<>();
    /** Guards against a second fetch for a UUID whose first one is still in flight. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public BedrockSkinService(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * The skin already known for this player, without touching the network.
     *
     * <p>Safe from any thread and from synchronous game logic.</p>
     */
    public Optional<BedrockSkin> cached(UUID uuid) {
        return uuid == null ? Optional.empty() : Optional.ofNullable(cache.get(uuid));
    }

    /**
     * Fetches the skin if it is not cached yet, then calls {@code onResolved} on
     * the main thread. The callback does not fire when the lookup fails.
     *
     * <p>May be called from any thread. A cached hit still dispatches through
     * the scheduler so callers always observe the same threading.</p>
     */
    public void resolve(UUID uuid, Consumer<BedrockSkin> onResolved) {
        Objects.requireNonNull(onResolved, "onResolved must not be null");
        if (uuid == null) {
            return;
        }

        BedrockSkin hit = cache.get(uuid);
        if (hit != null) {
            runOnMain(() -> onResolved.accept(hit));
            return;
        }
        fetchAsync(uuid, skin -> runOnMain(() -> onResolved.accept(skin)));
    }

    /**
     * Populates the cache in the background without a callback.
     *
     * <p>For callers that can only use {@link #cached(UUID)} — the skull scanner
     * runs inside inventory and chunk handling, where waiting for HTTP is not an
     * option, so it registers what it can now and gets it right next time.</p>
     */
    public void warm(UUID uuid) {
        if (uuid == null || cache.containsKey(uuid)) {
            return;
        }
        fetchAsync(uuid, skin -> { });
    }

    /** Drops a player's cached skin, e.g. when they disconnect. */
    public void forget(UUID uuid) {
        if (uuid != null) {
            cache.remove(uuid);
            failedAt.remove(uuid);
        }
    }

    /** Number of skins currently cached. Exposed for startup/debug reporting. */
    public int cachedCount() {
        return cache.size();
    }

    // ================== fetching ==================

    private void fetchAsync(UUID uuid, Consumer<BedrockSkin> onSuccess) {
        long xuid = xuidOf(uuid);
        if (xuid <= 0) {
            // Not a Floodgate UUID — nothing on the API is keyed by this.
            return;
        }
        if (isBackedOff(uuid) || !inFlight.add(uuid)) {
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BedrockSkin skin = fetchBlocking(xuid);
                if (skin == null) {
                    failedAt.put(uuid, System.currentTimeMillis());
                    plugin.getLogger().fine(() ->
                        "[BedrockSkin] No usable skin on the GeyserMC API for xuid " + xuid);
                    return;
                }
                cache.put(uuid, skin);
                failedAt.remove(uuid);
                onSuccess.accept(skin);
            } catch (Exception e) {
                failedAt.put(uuid, System.currentTimeMillis());
                // Warning, not severe: a skin is cosmetic and the next join retries.
                plugin.getLogger().warning("[BedrockSkin] Lookup failed for xuid " + xuid
                    + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            } finally {
                inFlight.remove(uuid);
            }
        });
    }

    private BedrockSkin fetchBlocking(long xuid) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + xuid))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("User-Agent", "GeyserExtra")
            .GET()
            .build();

        HttpResponse<String> response =
            http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            plugin.getLogger().fine(() -> "[BedrockSkin] API returned HTTP "
                + response.statusCode() + " for xuid " + xuid);
            return null;
        }
        return parse(response.body());
    }

    private boolean isBackedOff(UUID uuid) {
        Long when = failedAt.get(uuid);
        if (when == null) {
            return false;
        }
        if (System.currentTimeMillis() - when < FAILURE_BACKOFF_MILLIS) {
            return true;
        }
        failedAt.remove(uuid);
        return false;
    }

    private void runOnMain(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    // ================== pure helpers (unit-tested) ==================

    /**
     * The Xbox Live ID Floodgate packed into a Bedrock player's UUID.
     *
     * <p>Floodgate mints {@code 00000000-0000-0000-XXXX-XXXXXXXXXXXX}, putting
     * the XUID in the low 64 bits and leaving the high bits zero. A UUID with
     * non-zero high bits belongs to a Java account and has no XUID at all.</p>
     *
     * @return the XUID, or {@code 0} when {@code uuid} is not a Floodgate UUID
     */
    public static long xuidOf(UUID uuid) {
        if (uuid == null || uuid.getMostSignificantBits() != 0L) {
            return 0L;
        }
        return uuid.getLeastSignificantBits();
    }

    /**
     * Reads a {@code /v2/skin/<xuid>} response.
     *
     * <p>{@code texture_id} is the field the API documents as the texture hash,
     * but it is derived data — the authoritative hash lives inside the signed
     * {@code value}. When the field is missing or empty this falls back to
     * decoding {@code value}, so a response shape change cannot silently strip
     * skull support while leaving the profile fix working.</p>
     *
     * @return the parsed skin, or {@code null} when the response cannot supply
     *     a complete, signed texture property
     */
    static BedrockSkin parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String value = optString(root, "value");
            String signature = optString(root, "signature");
            if (value == null || signature == null) {
                return null;
            }
            String textureId = optString(root, "texture_id");
            if (textureId == null) {
                textureId = textureIdFromValue(value);
            }
            if (textureId == null) {
                return null;
            }
            return new BedrockSkin(value, signature, textureId);
        } catch (RuntimeException e) {
            // Malformed JSON, an HTML error page, an unexpected array — all of
            // them mean "no skin", never a crash on the fetch thread.
            return null;
        }
    }

    /** The texture hash carried by the signed profile JSON, or null. */
    static String textureIdFromValue(String base64Value) {
        try {
            String decoded = new String(
                Base64.getDecoder().decode(base64Value), StandardCharsets.UTF_8);
            JsonObject textures = JsonParser.parseString(decoded)
                .getAsJsonObject().getAsJsonObject("textures");
            if (textures == null) {
                return null;
            }
            JsonObject skin = textures.getAsJsonObject("SKIN");
            if (skin == null) {
                return null;
            }
            String url = optString(skin, "url");
            if (url == null) {
                return null;
            }
            int lastSlash = url.lastIndexOf('/');
            String hash = lastSlash >= 0 ? url.substring(lastSlash + 1) : url;
            return hash.isEmpty() ? null : hash;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String optString(JsonObject object, String member) {
        var element = object.get(member);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String text = element.getAsString();
        return text.isEmpty() ? null : text;
    }
}
