package com.geyserextra.extension.bedrock;

import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.RecipeUnlockingRequirement;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.ShapedRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.ShapelessRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.SmithingTransformRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptorWithCount;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.geysermc.geyser.network.GameProtocol;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.type.ItemMapping;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundUpdateRecipesPacket;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Sends Bedrock clients a supplementary {@link CraftingDataPacket} whose ingredients point at the
 * <b>real custom Bedrock items</b>, so recipes with custom ingredients can finally match.
 *
 * <h2>Why the client needs this</h2>
 * Bedrock computes crafting results client-side. Geyser's recipe translation drops the identity
 * of every ingredient (CustomModelData does not travel on the Java {@code Ingredient}, so it is
 * absent at the source) while translating the <em>result</em> into the real custom item. The
 * client therefore holds "vanilla ingredient -&gt; custom result" recipes that never match what
 * is on the grid — the Bedrock player sees no result at all. This class adds the recipes the
 * client was missing, built from the tables the backends ship.
 *
 * <h2>Why it hooks the packet translator</h2>
 * Geyser's public API exposes no downstream packet event and no server-transfer event, and the
 * Java server re-sends its whole recipe list on every {@code Bukkit.addRecipe} and on every
 * backend switch — each of which makes Geyser resend with {@code cleanRecipes = true}, wiping
 * whatever we added. Sending once at session join would therefore work until the first reload or
 * server hop and then silently stop. Running immediately after Geyser's own translator is the
 * only placement that stays correct, so this wraps
 * {@code JavaUpdateRecipesTranslator} in the internal registry.
 *
 * <h2>Smithing</h2>
 * Smithing entries become {@code SmithingTransformRecipeData}. The recipe is only half of what
 * Bedrock needs: the client also refuses to <em>place</em> anything in the base slot that does not
 * carry the vanilla item tag {@code minecraft:transformable_items}, which
 * {@code CustomItemsHandler} attaches when it registers custom items. Sending the recipe without
 * the tag leaves the slot unusable; tagging without the recipe leaves the result slot empty.
 *
 * <p>Taking the finished item stays server-authoritative either way — Geyser converts the Bedrock
 * take into a plain click on the Java result slot — so an injected recipe can only ever change
 * what the client is willing to show, never what the player actually receives.
 *
 * <h2>Fragility, stated plainly</h2>
 * This is the one part of GeyserExtra that touches Geyser internals ({@code Registries},
 * {@code GeyserSession}, {@code GameProtocol}). A Geyser update that renames any of them breaks
 * it at class-load or first use. Every entry point is therefore guarded: the first
 * {@link Throwable} disables the injector for the rest of the run and logs once, so a broken
 * internal API costs Bedrock crafting hints and nothing else.
 */
public final class BedrockRecipeInjector {

    /**
     * Recipes generated per table entry when a slot accepts several items.
     *
     * <p>A {@code list:} ingredient must become one Bedrock recipe per combination, and the count
     * is the product of the slot sizes. The cap keeps a wide list from turning one recipe into
     * thousands of packets; whatever it drops is logged rather than silently discarded.
     */
    private static final int MAX_COMBINATIONS_PER_RECIPE = 32;

    /** Bedrock recipe tag for the 3x3 crafting grid. Same value Geyser uses for its own recipes. */
    private static final String CRAFTING_TABLE_TAG = "crafting_table";

    /**
     * Bedrock recipe tag for the smithing table. Same value Geyser's own
     * {@code GeyserSmithingRecipe} uses; a different string makes the client file the recipe
     * under a station that never opens it.
     */
    private static final String SMITHING_TABLE_TAG = "smithing_table";

    /** Prefix so our recipes cannot collide with Geyser's own ids. */
    private static final String ID_PREFIX = "geyserextra_";

    private final Consumer<String> info;
    private final Consumer<String> warn;
    private final Consumer<String> debug;

    /**
     * {@code <java base item>#<cmd>} to the Bedrock identifier GeyserExtra registered for it.
     *
     * <p>A supplier, not a snapshot: custom items are registered during
     * {@code GeyserDefineCustomItemsEvent}, and pinning the map at construction would silently
     * capture an empty one if this is ever built earlier in the lifecycle.
     */
    private final Supplier<Map<String, String>> customBedrockIdentifiers;

    private volatile BedrockRecipeTable table = new BedrockRecipeTable(List.of());
    private volatile Path dataFolder;
    private volatile List<String> tableFingerprint = List.of();
    private volatile boolean disabled;
    private volatile boolean installed;

    /** Ingredients we could not resolve, remembered so each one is only reported once. */
    private final Set<String> reportedMissing = Collections.synchronizedSet(new LinkedHashSet<>());

    /**
     * Whether the "recipes were dropped" warning has already been emitted.
     *
     * <p>The whole feature fails <b>into the exact symptom it exists to fix</b>: if the base items
     * are not registered on the Bedrock side, every recipe is dropped and Bedrock crafting looks
     * broken in precisely the way it did before. That must not be a debug-only line, or the only
     * way to tell "not deployed" from "deployed and silently dropping everything" is to reproduce
     * it in game. Warned once per load, not per session, so it cannot flood.
     */
    private final java.util.concurrent.atomic.AtomicBoolean dropWarningEmitted =
        new java.util.concurrent.atomic.AtomicBoolean();

    public BedrockRecipeInjector(Supplier<Map<String, String>> customBedrockIdentifiers,
                                 Consumer<String> info, Consumer<String> warn, Consumer<String> debug) {
        this.customBedrockIdentifiers = customBedrockIdentifiers;
        this.info = info;
        this.warn = warn;
        this.debug = debug;
    }

    /** Key used by {@link #customBedrockIdentifiers}. */
    public static String key(String javaIdentifier, int customModelData) {
        return javaIdentifier + "#" + customModelData;
    }

    /** Reads the shipped tables. Safe to call again to pick up a backend's reload. */
    public void reload(Path dataFolder) {
        this.dataFolder = dataFolder;
        List<String> problems = new ArrayList<>();
        BedrockRecipeTable loaded = BedrockRecipeTable.readAll(dataFolder, problems::add);
        this.table = loaded;
        // Fingerprint last: a session sending in parallel must never see "unchanged" while the
        // table it would read is still the old one.
        this.tableFingerprint = fingerprint(dataFolder);
        this.reportedMissing.clear();
        this.dropWarningEmitted.set(false);
        if (loaded.isEmpty()) {
            info.accept("[bedrock-recipes] no corrected recipes available"
                + " (backends have not shipped a table yet)");
        } else {
            info.accept("[bedrock-recipes] " + loaded.recipes().size()
                + " corrected recipes loaded; " + customBedrockIdentifiers.get().size()
                + " custom Bedrock items are addressable");
        }
        problems.forEach(problem -> warn.accept("[bedrock-recipes] " + problem));
    }

    /**
     * Wraps Geyser's recipe translator so our packet always follows its own.
     *
     * <p>Idempotent, and a no-op if the original translator cannot be found — that means the
     * internal registry moved, and quietly registering ours in its place would delete every
     * vanilla recipe on Bedrock.
     */
    public void install() {
        if (installed || disabled) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            PacketTranslator<ClientboundUpdateRecipesPacket> original =
                (PacketTranslator<ClientboundUpdateRecipesPacket>) (PacketTranslator<?>)
                    Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundUpdateRecipesPacket.class);
            if (original == null) {
                warn.accept("[bedrock-recipes] Geyser has no translator registered for"
                    + " ClientboundUpdateRecipesPacket; leaving recipes untouched");
                disabled = true;
                return;
            }
            if (original instanceof Wrapper) {
                installed = true;
                return;
            }
            Registries.JAVA_PACKET_TRANSLATORS.register(
                ClientboundUpdateRecipesPacket.class, new Wrapper(original));
            installed = true;
            info.accept("[bedrock-recipes] corrected recipes will be sent after Geyser's own");
        } catch (Throwable t) {
            disabled = true;
            warn.accept("[bedrock-recipes] could not hook Geyser's recipe translator ("
                + t.getClass().getSimpleName() + ": " + t.getMessage()
                + "); Bedrock crafting with custom ingredients stays broken");
        }
    }

    /** Delegates to Geyser's translator, then appends ours. */
    private final class Wrapper extends PacketTranslator<ClientboundUpdateRecipesPacket> {

        private final PacketTranslator<ClientboundUpdateRecipesPacket> original;

        private Wrapper(PacketTranslator<ClientboundUpdateRecipesPacket> original) {
            this.original = original;
        }

        @Override
        public void translate(GeyserSession session, ClientboundUpdateRecipesPacket packet) {
            original.translate(session, packet);
            if (disabled) {
                return;
            }
            try {
                send(session);
            } catch (Throwable t) {
                // One failure disables the feature rather than logging per session per reload.
                disabled = true;
                warn.accept("[bedrock-recipes] injection failed and is now off for this run ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            }
        }

        @Override
        public boolean shouldExecuteInEventLoop() {
            return original.shouldExecuteInEventLoop();
        }
    }

    /**
     * Builds and sends the supplementary packet for one session.
     *
     * <p>{@code cleanRecipes} stays false: Geyser's own (uncorrected) recipes must survive, since
     * they are the only source for every vanilla recipe. The uncorrected custom ones remain too
     * and simply never match — they are already unmatched today, so nothing regresses.
     */
    private void send(GeyserSession session) {
        refreshIfChanged();
        BedrockRecipeTable snapshot = table;
        if (snapshot.isEmpty()) {
            return;
        }
        boolean split = GameProtocol.is26_40orHigher(session.protocolVersion());
        CraftingDataPacket packet = new CraftingDataPacket();
        packet.setCleanRecipes(false);

        int added = 0;
        int addedSmithing = 0;
        int dropped = 0;
        long truncated = 0;
        for (BedrockRecipeTable.Recipe recipe : snapshot.recipes()) {
            ItemData result = resolveResult(session, recipe.result());
            if (result == null) {
                dropped++;
                continue;
            }
            long possible = BedrockRecipeTable.combinationCount(recipe.slots());
            List<List<BedrockRecipeTable.ItemRef>> combinations =
                BedrockRecipeTable.expand(recipe.slots(), MAX_COMBINATIONS_PER_RECIPE);
            if (possible > combinations.size()) {
                truncated += possible - combinations.size();
            }

            int variant = 0;
            for (List<BedrockRecipeTable.ItemRef> combination : combinations) {
                List<ItemDescriptorWithCount> descriptors = describe(session, combination, recipe.type());
                if (descriptors == null) {
                    dropped++;
                    continue;
                }
                int netId = session.getLastRecipeNetId().getAndIncrement();
                String id = ID_PREFIX + recipe.id().replace(':', '_') + "_" + variant++;
                switch (recipe.type()) {
                    case SHAPED -> {
                        ShapedRecipeData data = ShapedRecipeData.shaped(id, recipe.width(), recipe.height(),
                            descriptors, Collections.singletonList(result), UUID.randomUUID(),
                            CRAFTING_TABLE_TAG, 0, netId, true, RecipeUnlockingRequirement.INVALID);
                        if (split) {
                            packet.getShapedData().add(data);
                        } else {
                            packet.getCraftingData().add(data);
                        }
                    }
                    case SHAPELESS -> {
                        ShapelessRecipeData data = ShapelessRecipeData.shapeless(id, descriptors,
                            Collections.singletonList(result), UUID.randomUUID(),
                            CRAFTING_TABLE_TAG, 0, netId, RecipeUnlockingRequirement.INVALID);
                        if (split) {
                            packet.getShapelessData().add(data);
                        } else {
                            packet.getCraftingData().add(data);
                        }
                    }
                    case SMITHING -> {
                        if (descriptors.size() != BedrockRecipeTable.SMITHING_SLOT_COUNT) {
                            // The parser already rejects this shape, so reaching here means the
                            // parser changed. Drop the one recipe rather than letting an
                            // IndexOutOfBounds disable the whole injector for the run.
                            dropped++;
                            continue;
                        }
                        SmithingTransformRecipeData data = SmithingTransformRecipeData.of(id,
                            descriptors.get(BedrockRecipeTable.SMITHING_TEMPLATE_SLOT),
                            descriptors.get(BedrockRecipeTable.SMITHING_BASE_SLOT),
                            descriptors.get(BedrockRecipeTable.SMITHING_ADDITION_SLOT),
                            result, SMITHING_TABLE_TAG, netId);
                        if (split) {
                            packet.getSmithingTransformData().add(data);
                        } else {
                            packet.getCraftingData().add(data);
                        }
                        addedSmithing++;
                    }
                    // アロー形式でも switch "文" なので網羅性は検査されない。default 無しだと、
                    // Type が増えた日に「何もパケットへ足さないまま added++ だけ回り、ログは
                    // N 件送ったと言う」という嘘のつき方をする。投げると外側の catch(Throwable)
                    // が injector をこの起動の間だけ止めて WARN を出す ── 補正が効かなくなる
                    // だけで、間違った素材のレシピが配られるよりはるかに安全な倒れ方。
                    default -> throw new IllegalStateException(
                        "unhandled recipe type " + recipe.type() + " for " + recipe.id());
                }
                added++;
            }
        }

        if (added == 0) {
            if (dropped > 0 && dropWarningEmitted.compareAndSet(false, true)) {
                warn.accept("[bedrock-recipes] all " + dropped + " corrected recipes were dropped:"
                    + " none of their items resolve to a registered Bedrock item."
                    + " Bedrock crafting and smithing with custom items stay broken."
                    + " Enable debug to see which items.");
            }
            debug.accept("[bedrock-recipes] nothing addressable for this session"
                + " (dropped=" + dropped + ")");
            return;
        }
        if (dropped > 0 && dropWarningEmitted.compareAndSet(false, true)) {
            warn.accept("[bedrock-recipes] sent " + added + " corrected recipes but dropped "
                + dropped + " (their items are not registered Bedrock items)."
                + " Enable debug to see which.");
        }
        session.sendUpstreamPacket(packet);
        debug.accept("[bedrock-recipes] sent " + added + " corrected recipes"
            + (addedSmithing > 0 ? " (" + addedSmithing + " smithing)" : "")
            + (dropped > 0 ? ", dropped " + dropped + " (unresolvable items)" : "")
            + (truncated > 0 ? ", " + truncated + " ingredient combinations over the per-recipe"
                + " cap of " + MAX_COMBINATIONS_PER_RECIPE + " were not sent" : ""));
    }

    /**
     * Descriptors for one combination, or {@code null} if any ingredient is unaddressable.
     *
     * <p>All-or-nothing on purpose: a recipe with one wrong ingredient is worse than a missing
     * recipe, because it can make a <em>different</em> craft succeed.
     */
    private List<ItemDescriptorWithCount> describe(GeyserSession session,
                                                   List<BedrockRecipeTable.ItemRef> combination,
                                                   BedrockRecipeTable.Type type) {
        List<ItemDescriptorWithCount> descriptors = new ArrayList<>(combination.size());
        for (BedrockRecipeTable.ItemRef ref : combination) {
            if (ref == null) {
                // Empty square. Only a shaped grid has one, and it has to be kept so the pattern
                // lines up. A shapeless list or a smithing slot with a hole is malformed input.
                if (type != BedrockRecipeTable.Type.SHAPED) {
                    return null;
                }
                descriptors.add(ItemDescriptorWithCount.EMPTY);
                continue;
            }
            ItemDefinition definition = resolveDefinition(session, ref);
            if (definition == null) {
                return null;
            }
            descriptors.add(ItemDescriptorWithCount.fromItem(
                ItemData.builder().definition(definition).count(1).build()));
        }
        return descriptors;
    }

    private ItemData resolveResult(GeyserSession session, BedrockRecipeTable.ItemRef ref) {
        ItemDefinition definition = resolveDefinition(session, ref);
        if (definition == null) {
            return null;
        }
        return ItemData.builder().definition(definition).count(Math.max(1, ref.count())).build();
    }

    /**
     * Finds the Bedrock item a table entry refers to.
     *
     * <p>Custom entries go through the identifiers GeyserExtra actually registered — an item that
     * was <b>not</b> registered (a look-alike with no icon and no attachable) has no distinct
     * Bedrock item at all, so no recipe can address it and the caller must drop the recipe.
     * Vanilla entries go through Geyser's own Java-to-Bedrock item mapping rather than a name
     * guess, because plenty of items are named differently on the two editions.
     */
    private ItemDefinition resolveDefinition(GeyserSession session, BedrockRecipeTable.ItemRef ref) {
        if (ref.isCustom()) {
            String bedrockIdentifier = customBedrockIdentifiers.get()
                .get(key(ref.javaIdentifier(), ref.cmd()));
            if (bedrockIdentifier == null) {
                reportMissing(ref.javaIdentifier() + " CMD=" + ref.cmd()
                    + " (GeyserExtra did not register a Bedrock item for it,"
                    + " so the client has nothing to match against)");
                return null;
            }
            ItemDefinition definition = session.getItemMappings().getDefinition(bedrockIdentifier);
            if (definition == null) {
                reportMissing(bedrockIdentifier + " (registered but absent from this session's"
                    + " item mappings)");
            }
            return definition;
        }
        ItemMapping mapping = session.getItemMappings().getMapping(ref.javaIdentifier());
        if (mapping == null || mapping == ItemMapping.AIR || mapping.getBedrockDefinition() == null) {
            reportMissing(ref.javaIdentifier() + " (no Bedrock mapping)");
            return null;
        }
        return mapping.getBedrockDefinition();
    }

    /**
     * Re-reads the shipped tables when a backend has rewritten one.
     *
     * <p>Checked here rather than on a timer because this is the only moment the result is used,
     * and recipe updates are rare (a backend switch or a plugin reload). Skipping it would mean
     * {@code /trinityforge reload} never reaches Bedrock players until the proxy restarts —
     * a silence that looks exactly like the feature not working.
     */
    private void refreshIfChanged() {
        Path folder = dataFolder;
        if (folder == null) {
            return;
        }
        List<String> current = fingerprint(folder);
        if (current.equals(tableFingerprint)) {
            return;
        }
        reload(folder);
    }

    /** File names plus sizes and modification times under the shipped-tables folder. */
    private static List<String> fingerprint(Path dataFolder) {
        Path directory = dataFolder.resolve(BedrockRecipeTable.DIRECTORY);
        if (!java.nio.file.Files.isDirectory(directory)) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> files =
                 java.nio.file.Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                parts.add(file.getFileName() + "|" + java.nio.file.Files.size(file)
                    + "|" + java.nio.file.Files.getLastModifiedTime(file).toMillis());
            }
        } catch (java.io.IOException e) {
            // Returning the previous fingerprint would hide a real change; returning a marker
            // forces exactly one reload attempt, which reports the problem properly.
            return List.of("unreadable:" + e.getMessage());
        }
        java.util.Collections.sort(parts);
        return List.copyOf(parts);
    }

    private void reportMissing(String what) {
        if (reportedMissing.add(what)) {
            debug.accept("[bedrock-recipes] unaddressable ingredient: " + what);
        }
    }
}
