package com.geyserextra.paper.enchantment;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.geyserextra.core.api.CustomItemMapping;
import com.geyserextra.core.config.GeyserExtraConfig.EnchantmentConfig;
import com.geyserextra.paper.GeyserExtraPaper;
import com.geyserextra.paper.pack.JavaPackLangReader;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.logging.Level;

/**
 * Handles enchantment lore injection via ProtocolLib packet interception
 * and anvil over-enchantment protection for Bedrock players.
 *
 * <p>This handler intercepts SET_SLOT and WINDOW_ITEMS packets to inject
 * custom/over-enchantment information as lore text visible only to Bedrock players.
 * It also integrates anvil protection logic formerly in AnvilRecipeHandler,
 * preventing Bedrock clients from downgrading over-enchantments during anvil operations.</p>
 *
 * <p>Requires ProtocolLib, Floodgate, and Paper API as compileOnly dependencies.</p>
 */
public final class BedrockEnchantmentHandler implements Listener {

    /**
     * Visible prefix prepended to every lore line we inject. Used to strip
     * previously-injected lines on the next packet so the tooltip cannot
     * accumulate when a re-broadcast SET_SLOT carries lore that was the
     * result of our own prior injection.
     *
     * <p>Plain ASCII tag was chosen over a Private Use Area codepoint
     * (U+E000): the PUA approach rendered as a visible tofu glyph on
     * Bedrock clients whose resource pack did not supply a font mapping,
     * defeating the "invisible marker" intent. A short readable tag is
     * unambiguous, font-independent, and lets operators see at a glance
     * which lore lines were added by this plugin.</p>
     *
     * <p>Trade-off: an operator who deliberately authors a lore line
     * starting with {@code [GE]} would see it silently stripped on the
     * next packet pass. The literal substring is uncommon enough that
     * this is judged an acceptable cost in exchange for the
     * font-independent visibility guarantee.</p>
     */
    private static final String INJECTED_LORE_MARKER = "[GE]";

    private final GeyserExtraPaper plugin;
    private final FloodgateApi floodgateApi;
    private final BedrockEnchantmentTablePacketStripper stripper;
    private final Map<String, AnvilRecipe> customRecipes;
    private final Map<UUID, CachedAnvilResult> bedrockAnvilCache;
    private final List<PacketListener> registeredListeners = new ArrayList<>();
    private final boolean enabled;

    /**
     * Creates a new BedrockEnchantmentHandler.
     *
     * <p>Attempts to obtain FloodgateApi and ProtocolManager. If either is unavailable,
     * the handler is disabled gracefully without crashing.</p>
     *
     * @param plugin   the main plugin instance, must not be null
     * @param stripper packet-level CMD-strip helper backed by a thread-safe inventory
     *                 state tracker; must not be null
     */
    public BedrockEnchantmentHandler(
        GeyserExtraPaper plugin,
        BedrockEnchantmentTablePacketStripper stripper
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.stripper = Objects.requireNonNull(stripper, "stripper must not be null");
        this.customRecipes = new ConcurrentHashMap<>();
        this.bedrockAnvilCache = new ConcurrentHashMap<>();

        FloodgateApi api = null;
        boolean isEnabled = false;
        try {
            api = FloodgateApi.getInstance();
            EnchantmentConfig enchantConfig = plugin.getGeyserExtraConfig().enchantment();
            isEnabled = api != null && enchantConfig.enabled();
            if (isEnabled) {
                registerPacketListeners();
                plugin.getLogger().info(
                    "BedrockEnchantmentHandler: Enchantment lore injection and anvil protection enabled"
                );
            }
        } catch (NoClassDefFoundError | Exception e) {
            plugin.getLogger().info(
                "BedrockEnchantmentHandler: Floodgate or ProtocolLib not available, disabled"
            );
        }

        this.floodgateApi = api;
        this.enabled = isEnabled;
    }

    /**
     * Whether this handler is enabled and actively processing packets/events.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    // ========================================================================
    // Packet Listener Registration (ProtocolLib)
    // ========================================================================

    /**
     * Registers ProtocolLib packet listeners for SET_SLOT and WINDOW_ITEMS.
     *
     * <p>Why: Bedrock players cannot see custom or over-enchantments natively.
     * By intercepting outbound item packets, we inject enchantment info as lore
     * so Bedrock players can see what enchantments are on their items.</p>
     */
    /**
     * Removes all registered ProtocolLib listeners. Call from plugin onDisable.
     */
    public void cleanup() {
        if (!registeredListeners.isEmpty()) {
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();
            for (PacketListener listener : registeredListeners) {
                pm.removePacketListener(listener);
            }
            registeredListeners.clear();
        }
        bedrockAnvilCache.clear();
    }

    private void registerPacketListeners() {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

        // Priority HIGHEST: we want the final word on item appearance for Bedrock
        // players. Any other plugin that mutates SET_SLOT / WINDOW_ITEMS (item
        // model framework plugins, NBT injectors) typically runs at NORMAL/HIGH;
        // we run last so the displayName fallback and CMD strip see the most
        // up-to-date payload and are not silently overwritten by a later
        // listener. The packet is only mutated when isBedrockPlayer(player) is
        // true, so Java clients are unaffected and there is no contention with
        // other plugins' Java-side modifications.
        PacketListener listener = new PacketAdapter(
            plugin,
            ListenerPriority.HIGHEST,
            PacketType.Play.Server.SET_SLOT,
            PacketType.Play.Server.WINDOW_ITEMS
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                try {
                    if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                        handleSetSlotPacket(event);
                    } else {
                        handleWindowItemsPacket(event);
                    }
                } catch (Exception e) {
                    // Why log full stack trace: e.getMessage() alone yields "null" on
                    // many ProtocolLib internal failures (NPE, ClassCastException),
                    // erasing all diagnostic information.
                    BedrockEnchantmentHandler.this.plugin.getLogger().log(
                        Level.WARNING,
                        "BedrockEnchantmentHandler: Error processing "
                            + event.getPacketType() + " packet",
                        e
                    );
                }
            }
        };
        protocolManager.addPacketListener(listener);
        registeredListeners.add(listener);
    }

    /**
     * Handles SET_SLOT packet by injecting enchantment lore for Bedrock players.
     *
     * <p>Also strips the {@code CUSTOM_MODEL_DATA} component when the slot is
     * the enchantment-table input slot or the cursor (while the player has an
     * enchantment table open). Why: Geyser registers CMD-bearing items as
     * Bedrock-side custom items via the auto-generated pack. Bedrock's
     * enchantment-preview computation reads enchantability/tag metadata from
     * the item identifier; the auto pack does not supply that, and the client
     * crashes. Removing the CMD component before Geyser translates the packet
     * causes Geyser to forward the item as its vanilla base material, which
     * Bedrock can preview safely. Server-side state is untouched — only the
     * outbound packet is mutated.</p>
     *
     * @param event the packet event
     */
    private void handleSetSlotPacket(PacketEvent event) {
        Player player = event.getPlayer();
        if (!isBedrockPlayer(player)) {
            return;
        }

        UUID playerId = getPlayerUuidSafely(player);
        PacketContainer packet = event.getPacket();
        ItemStack item = packet.getItemModifier().read(0);

        boolean targetsEnchantInput = playerId != null
            && stripper.isEnchantmentTableInputSlot(playerId, packet);
        boolean targetsCursorAtTable = playerId != null
            && stripper.isAtEnchantmentTable(playerId)
            && stripper.isCursorSetSlot(packet);

        if (targetsEnchantInput || targetsCursorAtTable) {
            ItemStack stripped = stripper.stripCustomModelData(item);
            if (stripped != null) {
                packet.getItemModifier().write(0, stripped);
                return;
            }
        }

        // Per-player opt-out for the durability / over-enchant lore lines
        // (toggled via /ga menu, stored on PlayerSettings). The opt-out is
        // intentionally scoped to *lore* only — the displayName fallback that
        // hides raw Geyser identifiers ("gmdl_xxx") still runs regardless,
        // because exposing those identifiers is never the user's intent and
        // toggling lore off must not bring them back.
        boolean loreEnabled = isLoreTooltipEnabledFor(playerId);
        ItemStack modified = injectEnchantmentLore(item, loreEnabled);
        if (modified != null) {
            packet.getItemModifier().write(0, modified);
        }
    }

    /**
     * Handles WINDOW_ITEMS packet by injecting enchantment lore for Bedrock players.
     *
     * <p>Also strips {@code CUSTOM_MODEL_DATA} from the enchantment-table input
     * slot (slot 0 of the contents list) and the carried cursor item (separate
     * field) when the player has an enchantment table open.</p>
     *
     * <p>Uses lazy allocation: only creates a new list when at least one item is modified,
     * avoiding unnecessary ArrayList allocation for packets with no enchanted items.</p>
     *
     * @param event the packet event
     */
    private void handleWindowItemsPacket(PacketEvent event) {
        Player player = event.getPlayer();
        if (!isBedrockPlayer(player)) {
            return;
        }

        UUID playerId = getPlayerUuidSafely(player);
        PacketContainer packet = event.getPacket();
        List<ItemStack> items = packet.getItemListModifier().read(0);
        if (items == null || items.isEmpty()) {
            return;
        }

        boolean isEnchantingTableWindow = playerId != null
            && stripper.isEnchantmentTableWindow(playerId, packet);
        // Lore lines are user-toggleable; displayName fallback is not (raw
        // gmdl_* identifiers must never reach a Bedrock player). The flag is
        // passed *into* injectEnchantmentLore which decides per-item whether
        // to emit lore lines, while the displayName branch always runs.
        boolean loreEnabled = isLoreTooltipEnabledFor(playerId);

        // Lazy allocation: only create modifiedList when first modification is found
        List<ItemStack> modifiedList = null;

        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            ItemStack output = null;

            if (i == 0 && isEnchantingTableWindow) {
                output = stripper.stripCustomModelData(item);
            }
            if (output == null) {
                output = injectEnchantmentLore(item, loreEnabled);
            }

            if (output != null) {
                if (modifiedList == null) {
                    // First modification found: copy all items up to this point
                    modifiedList = new ArrayList<>(items.size());
                    for (int j = 0; j < i; j++) {
                        modifiedList.add(items.get(j));
                    }
                }
                modifiedList.add(output);
            } else if (modifiedList != null) {
                modifiedList.add(item);
            }
        }

        if (modifiedList != null) {
            packet.getItemListModifier().write(0, modifiedList);
        }

        // Strip the carried (cursor) item separately. WINDOW_ITEMS encodes the
        // cursor in a dedicated trailing field that ProtocolLib exposes on the
        // single-item modifier; not all protocol/ProtocolLib variants expose
        // it the same way, so we tolerate read failures silently.
        if (isEnchantingTableWindow) {
            try {
                ItemStack carried = packet.getItemModifier().read(0);
                ItemStack strippedCarried = stripper.stripCustomModelData(carried);
                if (strippedCarried != null) {
                    packet.getItemModifier().write(0, strippedCarried);
                }
            } catch (Exception ex) {
                if (plugin.getGeyserExtraConfig().general().debugMode()) {
                    plugin.getLogger().fine(
                        "CMD-strip: WINDOW_ITEMS carried-item access unavailable ("
                            + ex.getClass().getSimpleName() + ")");
                }
            }
        }
    }

    // ========================================================================
    // Lore Injection Logic
    // ========================================================================

    /**
     * Injects Bedrock-specific tooltip information into the item.
     *
     * <p>Two independent responsibilities collapsed into one slow path:
     * <ol>
     *   <li><b>Lore lines</b> (durability, over-enchant) — added only when
     *       {@code loreEnabled} is {@code true}. The user can toggle these off
     *       via {@code /ga} to reduce visual noise.</li>
     *   <li><b>Display-name fallback</b> — always evaluated for CMD-bearing
     *       items, regardless of {@code loreEnabled}. A custom item without a
     *       Bedrock-resolvable name would otherwise surface as the raw Geyser
     *       identifier ({@code gmdl_xxx}); never showing that to the player is
     *       a non-negotiable invariant, so this branch is not toggleable.</li>
     * </ol>
     * </p>
     *
     * <p>Clones the item before modification to avoid mutating the original.</p>
     *
     * @param item         the original item
     * @param loreEnabled  whether the per-player lore opt-in is on
     * @return a cloned item with tooltip injected, or null if no change was
     *         applied (caller passes the original packet through untouched)
     */
    private ItemStack injectEnchantmentLore(ItemStack item, boolean loreEnabled) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }

        // Fast pre-filter: an item is a candidate for the slow path only if at
        // least one of the responsibilities has a reason to fire.
        //   - Lore branch needs lore enabled AND a damageable / enchanted item
        //   - displayName branch needs a CMD component OR an item whose
        //     identity is carried through PDC (i.e. an external-plugin
        //     custom item that does not use CUSTOM_MODEL_DATA). The PDC
        //     check is narrower than blanket hasItemMeta() — vanilla
        //     enchanted books / armour with no PDC fall through and keep
        //     their localised default name instead of being force-renamed
        //     to "Enchanted Book" by prettifyMaterialName.
        // Items that fail both predicates are returned untouched without
        // paying for a getItemMeta() snapshot. Most inventory items (food,
        // materials, blocks) hit this short-circuit.
        boolean potentiallyDamageable = item.getType().getMaxDurability() > 0;
        boolean possiblyEnchanted = !item.getEnchantments().isEmpty()
            || item.getType() == Material.ENCHANTED_BOOK;
        boolean possiblyCmd = hasCustomModelData(item);
        boolean hasPdcIdentity = hasPdcMeta(item);
        boolean loreCandidate = loreEnabled && (potentiallyDamageable || possiblyEnchanted);
        if (!loreCandidate && !possiblyCmd && !hasPdcIdentity) {
            return null;
        }

        List<Component> tooltipLines = new ArrayList<>();

        if (loreEnabled) {
            // Enchantment lore (existing feature)
            Map<Enchantment, Integer> enchantments = getEnchantments(item);
            if (!enchantments.isEmpty()) {
                EnchantmentConfig config = plugin.getGeyserExtraConfig().enchantment();
                tooltipLines.addAll(buildEnchantmentLoreLines(enchantments, config));
            }

            // Bedrock tooltip: durability display
            // Why: Bedrock Edition doesn't show numeric durability values natively.
            // Displaying remaining/max durability helps Bedrock players manage their tools.
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.Damageable damageable) {
                // Why: Use Damageable.getMaxDamage() first (respects custom max_damage component),
                // then fall back to Material.getMaxDurability() for vanilla items.
                // Custom items (plugins, data packs) can set max_damage higher than vanilla.
                int maxDurability = damageable.hasMaxDamage()
                    ? damageable.getMaxDamage()
                    : item.getType().getMaxDurability();
                if (maxDurability > 0) {
                    int remaining = maxDurability - damageable.getDamage();
                    // Color based on remaining percentage
                    float ratio = (float) remaining / maxDurability;
                    net.kyori.adventure.text.format.TextColor durColor;
                    if (ratio > 0.5f) {
                        durColor = net.kyori.adventure.text.format.NamedTextColor.GREEN;
                    } else if (ratio > 0.2f) {
                        durColor = net.kyori.adventure.text.format.NamedTextColor.YELLOW;
                    } else {
                        durColor = net.kyori.adventure.text.format.NamedTextColor.RED;
                    }
                    tooltipLines.add(Component.text("耐久値: " + remaining + "/" + maxDurability)
                        .color(durColor));
                }
            }
        }

        // Decide whether the item also needs the fallback display-name fix.
        // Why: an item without a Bedrock-resolvable display name renders on
        // Bedrock as the registered Geyser identifier (e.g. "gmdl_abc1234")
        // or as nothing for PDC-only items. We need to inject a readable
        // name when:
        //   (a) The item has no ItemMeta display name at all, or
        //   (b) The item's display name is a TranslatableComponent whose key
        //       Bedrock cannot resolve — Java-side lang resolution returns
        //       readable text, but the Bedrock client only knows its own
        //       built-in translation keys, so the key would surface as-is.
        // Items with a literal Text display name are left alone — their NBT
        // already carries the right label and Geyser forwards it correctly.
        //
        // Scope: CMD items OR PDC-bearing items (the latter catches
        // external-plugin custom items that don't use CUSTOM_MODEL_DATA).
        // Vanilla items with no PDC fall through and keep their localised
        // default rendering.
        boolean wantDisplayNameFallback = (possiblyCmd || hasPdcIdentity)
            && !hasResolvedServerSideDisplayName(item);

        boolean hasInjectedLeftover = containsInjectedLoreMarker(item);
        if (tooltipLines.isEmpty() && !wantDisplayNameFallback && !hasInjectedLeftover) {
            return null;
        }

        // Clone to avoid mutating the original server-side item
        ItemStack cloned = item.clone();
        ItemMeta clonedMeta = cloned.getItemMeta();
        if (clonedMeta == null) {
            return null;
        }

        boolean changed = false;

        if (!tooltipLines.isEmpty() || hasInjectedLeftover) {
            // Strip any previously-injected lines first so the tooltip can't
            // accumulate across re-broadcast SET_SLOT packets. Lines that
            // start with INJECTED_LORE_MARKER were written by an earlier
            // pass; everything else is the operator's / plugin's lore and
            // must survive untouched.
            List<Component> existingLore = clonedMeta.lore();
            List<Component> newLore = new ArrayList<>(stripInjectedLore(existingLore));
            for (Component line : tooltipLines) {
                newLore.add(prependInjectedMarker(line));
            }
            clonedMeta.lore(newLore.isEmpty() ? null : newLore);
            changed = true;
        }

        if (wantDisplayNameFallback) {
            // Fallback chain for items whose server-side ItemStack lacks a
            // Bedrock-renderable display name. Walked in order of preference:
            //   1. Lang-resolved TranslatableComponent: if the ItemMeta name
            //      is a translation key and the operator's Java pack lang
            //      file has it, use the resolved text directly. This is the
            //      authoritative source — it matches exactly what a Java
            //      client would render for the same item.
            //   2. PDC display-name keys: external plugins (ItemsAdder /
            //      Oraxen / MMOItems variants) often store the player-facing
            //      name in PDC under "displayname" / "display_name" / "title"
            //      / "label". This is what catches CMD-less PDC-only items
            //      whose ItemMeta carries no displayName Component.
            //   3. ItemMappingRegistry: prior runtime scans (recipe results
            //      and PrepareItemCraftEvent observations) populate this with
            //      the literal text or earlier lang-resolved name for the
            //      same (baseItem, CMD). Catches the case where the current
            //      ItemStack instance has no ItemMeta but a previous instance
            //      did.
            //   4. Prettified base material name: last-ditch so a raw
            //      Geyser identifier never reaches the player.
            String fallback = resolveTranslatableDisplayName(item);
            if (fallback == null) {
                fallback = lookupPdcDisplayName(item);
            }
            if (fallback == null) {
                fallback = lookupRegistryDisplayName(item);
            }
            if (fallback == null) {
                fallback = prettifyMaterialName(item.getType());
            }
            // Italic-disabled to match vanilla style (display names are non-italic;
            // only the default lore-derived "renamed in anvil" italic case applies).
            clonedMeta.displayName(Component.text(fallback)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            changed = true;
        }

        if (!changed) {
            return null;
        }

        cloned.setItemMeta(clonedMeta);
        return cloned;
    }

    /**
     * Whether the item has the {@code custom_model_data} component set.
     * Wrapped in a try/catch so a Paper API mismatch with the runtime never
     * disables the lore-injection path entirely.
     */
    private static boolean hasCustomModelData(ItemStack item) {
        try {
            return item.hasData(DataComponentTypes.CUSTOM_MODEL_DATA);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether the item carries a server-side display name that Bedrock can
     * render correctly without further help. Returns {@code false} whenever
     * <em>any</em> node in the Adventure Component tree is a
     * {@link net.kyori.adventure.text.TranslatableComponent}, so the
     * packet-side injection has a chance to resolve it through the Java pack
     * lang reader.
     *
     * <p>Why walk the tree: a name like
     * {@code Component.text("Sacred ").append(Component.translatable("item.mymod.sword"))}
     * has a {@link net.kyori.adventure.text.TextComponent} at the root and a
     * translation key buried in a child. The previous instanceof-only check
     * passed it through unchanged, leaking the raw key
     * ({@code item.mymod.sword}) onto the Bedrock client.</p>
     */
    private static boolean hasResolvedServerSideDisplayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            net.kyori.adventure.text.Component name = meta.displayName();
            return name != null && !containsTranslatableComponent(name);
        }
        try {
            return item.hasData(DataComponentTypes.CUSTOM_NAME)
                || item.hasData(DataComponentTypes.ITEM_NAME);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns {@code true} when {@code node} or any of its descendants is a
     * {@link net.kyori.adventure.text.TranslatableComponent}.
     *
     * <p>The walk is intentionally exhaustive — Bedrock can't resolve a
     * translation key in any position, so even a deeply-nested one means the
     * server-side serialization is not safe to forward verbatim.</p>
     */
    private static boolean containsTranslatableComponent(net.kyori.adventure.text.Component node) {
        if (node == null) {
            return false;
        }
        if (node instanceof net.kyori.adventure.text.TranslatableComponent) {
            return true;
        }
        for (net.kyori.adventure.text.Component child : node.children()) {
            if (containsTranslatableComponent(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves any {@link net.kyori.adventure.text.TranslatableComponent}
     * nodes inside the item's display name against the operator-supplied Java
     * pack lang files and returns the serialized plain-text form.
     *
     * <p>Handles three Component shapes:
     * <ol>
     *   <li>Pure TranslatableComponent root — resolves directly.</li>
     *   <li>Wrapper TranslatableComponent + literal children (or vice versa,
     *       i.e. a TextComponent with a TranslatableComponent child) —
     *       resolves each TranslatableComponent in the tree and serializes the
     *       whole as plain text. Without this branch, a mixed component would
     *       fall through to {@code lookupRegistryDisplayName} and lose the
     *       literal portions (or, before the fix, leak the raw key).</li>
     *   <li>Anything without a TranslatableComponent — returns null so the
     *       caller falls through to {@code lookupRegistryDisplayName}.</li>
     * </ol>
     * </p>
     *
     * <p>Returns {@code null} when the ItemMeta name is absent or no
     * resolution could be performed.</p>
     */
    private String resolveTranslatableDisplayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return null;
        }
        net.kyori.adventure.text.Component name = meta.displayName();
        if (name == null || !containsTranslatableComponent(name)) {
            return null;
        }
        JavaPackLangReader langReader;
        try {
            langReader = plugin.getJavaPackLangReader();
        } catch (Throwable t) {
            return null;
        }
        net.kyori.adventure.text.Component resolved = resolveTranslatablesInTree(name, langReader);
        try {
            String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(resolved);
            return text != null && !text.isBlank() ? text : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Returns a deep copy of {@code node} in which every
     * {@link net.kyori.adventure.text.TranslatableComponent} whose key is
     * known to {@code langReader} has been replaced with a literal
     * {@link net.kyori.adventure.text.TextComponent} carrying the resolved
     * text. Unresolvable keys fall back to the translation key itself — that
     * preserves enough information for the caller to notice resolution failed
     * without erasing the literal portions that surround it.
     */
    private static net.kyori.adventure.text.Component resolveTranslatablesInTree(
        net.kyori.adventure.text.Component node,
        JavaPackLangReader langReader
    ) {
        if (node == null) {
            return net.kyori.adventure.text.Component.empty();
        }
        net.kyori.adventure.text.Component current = node;
        if (node instanceof net.kyori.adventure.text.TranslatableComponent translatable) {
            String resolved = null;
            if (langReader != null) {
                try {
                    resolved = langReader.resolve(translatable.key());
                } catch (Throwable ignored) {
                    // resolver failures degrade to the key fallback below
                }
            }
            if (resolved == null || resolved.isBlank()) {
                resolved = translatable.fallback() != null
                    ? translatable.fallback()
                    : translatable.key();
            }
            // Substitute Minecraft-style placeholders (%s, %1$s) in the
            // resolved template with the recursively-resolved arguments.
            // Without this step, translations that reference another
            // value — e.g. "death.attack.player" = "%1$s was slain by %2$s"
            // — would render as the bare template string with the
            // placeholders intact.
            List<String> argsAsText = collectArgumentsAsText(translatable, langReader);
            if (!argsAsText.isEmpty()) {
                resolved = applyMinecraftPlaceholders(resolved, argsAsText);
            }
            current = net.kyori.adventure.text.Component.text(resolved).style(translatable.style());
        }
        List<net.kyori.adventure.text.Component> originalChildren = node.children();
        if (originalChildren.isEmpty()) {
            return current;
        }
        List<net.kyori.adventure.text.Component> newChildren = new ArrayList<>(originalChildren.size());
        for (net.kyori.adventure.text.Component child : originalChildren) {
            newChildren.add(resolveTranslatablesInTree(child, langReader));
        }
        return current.children(newChildren);
    }

    /**
     * Serialises each {@link net.kyori.adventure.text.TranslatableComponent}
     * argument to plain text, recursively resolving nested TranslatableComponents
     * so a "%s" placeholder can never leak another translation key.
     *
     * <p>Adventure 4.15 changed {@code arguments()} from {@code List<Component>}
     * to {@code List<TranslationArgument>}. Both shapes are handled
     * defensively so the class compiles against either era and degrades
     * to an empty list rather than throwing on an API mismatch.</p>
     */
    private static List<String> collectArgumentsAsText(
        net.kyori.adventure.text.TranslatableComponent translatable,
        JavaPackLangReader langReader
    ) {
        List<String> out = new ArrayList<>();
        try {
            for (Object arg : translatable.arguments()) {
                net.kyori.adventure.text.Component argComponent;
                if (arg instanceof net.kyori.adventure.text.Component c) {
                    argComponent = c;
                } else if (arg instanceof net.kyori.adventure.translation.Translatable t) {
                    argComponent = net.kyori.adventure.text.Component.translatable(t);
                } else if (arg != null) {
                    // Adventure 4.15+ TranslationArgument: probe asComponent()
                    // reflectively so this code keeps compiling on older
                    // Adventure builds that returned List<Component> directly.
                    try {
                        Object result = arg.getClass().getMethod("asComponent").invoke(arg);
                        if (result instanceof net.kyori.adventure.text.Component c2) {
                            argComponent = c2;
                        } else {
                            argComponent = net.kyori.adventure.text.Component.text(String.valueOf(arg));
                        }
                    } catch (Throwable inner) {
                        argComponent = net.kyori.adventure.text.Component.text(String.valueOf(arg));
                    }
                } else {
                    argComponent = net.kyori.adventure.text.Component.empty();
                }
                net.kyori.adventure.text.Component resolvedArg =
                    resolveTranslatablesInTree(argComponent, langReader);
                String plain = net.kyori.adventure.text.serializer.plain
                    .PlainTextComponentSerializer.plainText().serialize(resolvedArg);
                out.add(plain != null ? plain : "");
            }
        } catch (Throwable ignored) {
            // Adventure API quirk → return whatever was collected so far.
        }
        return out;
    }

    /**
     * Substitutes Minecraft-style {@code %s} / {@code %N$s} placeholders in
     * {@code template} with the supplied arguments. Positional {@code %s}
     * consumes arguments in declaration order; numbered {@code %1$s},
     * {@code %2$s}, … look up the argument by 1-based index. {@code %%} is
     * an escape for a literal percent. Out-of-range placeholders are left
     * intact so a malformed template surfaces visibly rather than dropping
     * silently.
     */
    private static String applyMinecraftPlaceholders(String template, List<String> args) {
        if (template == null || template.isEmpty() || args.isEmpty()) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length() + 16);
        int positional = 0;
        int i = 0;
        int length = template.length();
        while (i < length) {
            char c = template.charAt(i);
            if (c == '%' && i + 1 < length) {
                char next = template.charAt(i + 1);
                if (next == '%') {
                    out.append('%');
                    i += 2;
                    continue;
                }
                if (next == 's') {
                    if (positional < args.size()) {
                        out.append(args.get(positional++));
                    } else {
                        out.append("%s");
                    }
                    i += 2;
                    continue;
                }
                if (Character.isDigit(next)) {
                    int j = i + 1;
                    int num = 0;
                    while (j < length && Character.isDigit(template.charAt(j))) {
                        num = num * 10 + (template.charAt(j) - '0');
                        j++;
                    }
                    if (j + 1 < length && template.charAt(j) == '$' && template.charAt(j + 1) == 's') {
                        int idx = num - 1;
                        if (idx >= 0 && idx < args.size()) {
                            out.append(args.get(idx));
                        } else {
                            out.append(template, i, j + 2);
                        }
                        i = j + 2;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Looks up the item's {@code (baseItem, CMD)} pair in the registry and
     * returns its stored display name, or {@code null} when no entry exists
     * or the entry has no name.
     *
     * <p>Why: the runtime scanner records the literal {@code ItemMeta.displayName}
     * the first time it sees an instance of a CMD item, so even when later
     * ItemStacks of the same identity arrive without ItemMeta (recipe-result
     * snapshots, programmatic give operations, etc.) the registry remembers
     * the human-readable name. Falling back through this map makes the
     * Bedrock-side display match the Java preview without having to parse
     * Java pack lang files.</p>
     */
    private String lookupRegistryDisplayName(ItemStack item) {
        if (item == null) {
            return null;
        }
        try {
            if (!item.hasData(DataComponentTypes.CUSTOM_MODEL_DATA)) {
                return null;
            }
            CustomModelData cmd = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
            if (cmd == null || cmd.floats() == null || cmd.floats().isEmpty()) {
                return null;
            }
            int cmdValue = cmd.floats().get(0).intValue();
            if (cmdValue <= 0) {
                return null;
            }
            String baseItem = item.getType().getKey().toString();
            CustomItemMapping mapping = plugin.getItemMappingRegistry()
                .getByCustomModelData(baseItem, cmdValue)
                .orElse(null);
            if (mapping == null) {
                return null;
            }
            String name = mapping.displayName();
            return name != null && !name.isBlank() ? name : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether the item carries a non-empty {@link org.bukkit.persistence.PersistentDataContainer}.
     * Used as a cheap proxy for "this is probably a custom item from another
     * plugin" because external plugins almost always stamp at least one PDC
     * key onto their custom items (identifier, version marker, behaviour
     * flags). Vanilla items leave PDC empty unless an operator explicitly
     * mutates it, so this predicate avoids force-renaming vanilla items.
     */
    private static boolean hasPdcMeta(ItemStack item) {
        if (item == null) return false;
        try {
            if (!item.hasItemMeta()) return false;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return false;
            return !meta.getPersistentDataContainer().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Common PDC keys plugins use to store a player-facing display name.
     * Mirrors the list in {@code CustomItemScanner.PDC_DISPLAY_NAME_KEYS}
     * so the packet-time and scanner-time fallbacks look at the same set.
     */
    private static final String[] PDC_DISPLAY_NAME_KEYS = {
        "displayname", "display_name", "title", "label"
    };

    /**
     * Probes the item's PersistentDataContainer for a display-name string
     * stored under one of the {@link #PDC_DISPLAY_NAME_KEYS}. Returns
     * {@code null} when no recognised key carries one. Used as a fallback
     * when the runtime ItemStack has no ItemMeta displayName Component — a
     * common shape for external plugins (ItemsAdder / Oraxen / MMOItems
     * variants) that keep the player-facing name in PDC.
     */
    private static String lookupPdcDisplayName(ItemStack item) {
        if (item == null) return null;
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return null;
            org.bukkit.persistence.PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (pdc.isEmpty()) return null;
            for (NamespacedKey key : pdc.getKeys()) {
                String lower = key.getKey().toLowerCase(java.util.Locale.ROOT);
                for (String candidate : PDC_DISPLAY_NAME_KEYS) {
                    if (!lower.contains(candidate)) continue;
                    try {
                        String value = pdc.get(key, org.bukkit.persistence.PersistentDataType.STRING);
                        if (value != null && !value.isBlank()) {
                            return value;
                        }
                    } catch (Exception ignored) {
                        // non-string PDC value at this key → keep searching
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
            // ItemMeta API mismatch → no PDC display name available
        }
        return null;
    }

    /**
     * Returns {@code true} when any line of the item's lore starts with the
     * {@link #INJECTED_LORE_MARKER}. Used to decide whether the slow path
     * has to run purely to strip a stale injection (e.g. the lore-toggle was
     * just turned off and a previously-injected line should disappear).
     */
    private static boolean containsInjectedLoreMarker(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        List<Component> lore = meta.lore();
        if (lore == null || lore.isEmpty()) {
            return false;
        }
        for (Component line : lore) {
            if (startsWithInjectedMarker(line)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a copy of {@code lore} with every line that begins with
     * {@link #INJECTED_LORE_MARKER} removed. Returns an empty list when the
     * input is null. This is what guarantees the tooltip can't accumulate
     * across re-broadcast packets: every pass starts from the operator's /
     * plugin's lore alone.
     */
    private static List<Component> stripInjectedLore(List<Component> lore) {
        if (lore == null || lore.isEmpty()) {
            return new ArrayList<>();
        }
        List<Component> filtered = new ArrayList<>(lore.size());
        for (Component line : lore) {
            if (!startsWithInjectedMarker(line)) {
                filtered.add(line);
            }
        }
        return filtered;
    }

    /** Prepends the invisible marker character to a tooltip line. */
    private static Component prependInjectedMarker(Component line) {
        return Component.text(INJECTED_LORE_MARKER).append(line);
    }

    /**
     * Tests whether a Component's plain-text serialization begins with the
     * injected-lore marker. The serializer call is cheap (no allocation
     * beyond a small StringBuilder) and the marker is a single character,
     * so the check is constant-time on small components.
     */
    private static boolean startsWithInjectedMarker(Component component) {
        if (component == null) {
            return false;
        }
        try {
            String plain = net.kyori.adventure.text.serializer.plain
                .PlainTextComponentSerializer.plainText().serialize(component);
            return plain != null && plain.startsWith(INJECTED_LORE_MARKER);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Builds the same vanilla-style display name fallback the Paper-side
     * registration uses, so a Bedrock player sees identical text in both
     * the registry-derived and the packet-injected paths.
     *
     * <p>Example: {@code Material.DIAMOND_SWORD} -> {@code "Diamond Sword"}.</p>
     */
    private static String prettifyMaterialName(Material material) {
        if (material == null) {
            return "Unknown";
        }
        String raw = material.getKey().getKey();  // "diamond_sword"
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

    /**
     * Builds lore lines for enchantments that should be displayed to Bedrock players.
     *
     * @param enchantments the enchantments on the item
     * @param config       the enchantment configuration
     * @return list of Component lines to append to lore (may be empty)
     */
    private List<Component> buildEnchantmentLoreLines(
        Map<Enchantment, Integer> enchantments,
        EnchantmentConfig config
    ) {
        List<Component> lines = new ArrayList<>();

        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            Enchantment enchantment = entry.getKey();
            int level = entry.getValue();

            if (level <= 0) {
                continue;
            }

            if (shouldDisplayEnchantment(enchantment, level, config)) {
                Component line = formatEnchantmentLine(enchantment, level);
                lines.add(line);
            }
        }

        return List.copyOf(lines);
    }

    /**
     * Determines whether an enchantment should be displayed in lore.
     *
     * @param enchantment the enchantment
     * @param level       the enchantment level
     * @param config      the enchantment configuration
     * @return true if this enchantment should appear in lore
     */
    private boolean shouldDisplayEnchantment(
        Enchantment enchantment,
        int level,
        EnchantmentConfig config
    ) {
        NamespacedKey key = enchantment.getKey();

        // Custom enchantments: Geyser already displays custom enchantment names
        // for Bedrock players, so lore injection would cause duplicate display.
        // Only inject lore for over-enchantments (vanilla enchants above max level).

        // Over-enchantments: level exceeds vanilla max (guard against maxLevel <= 0)
        int maxLevel = enchantment.getMaxLevel();
        if (config.showOverEnchantments() && maxLevel > 0 && level > maxLevel) {
            return true;
        }

        return false;
    }

    /**
     * Formats a single enchantment line as an Adventure Component.
     *
     * <p>Curses are displayed in red; normal enchantments in gray.
     * Italic decoration is explicitly disabled to match vanilla lore style.</p>
     *
     * @param enchantment the enchantment
     * @param level       the enchantment level
     * @return the formatted component
     */
    private Component formatEnchantmentLine(Enchantment enchantment, int level) {
        String displayText = EnchantmentNameMapper.formatEnchantment(enchantment, level);
        boolean isCurse = EnchantmentNameMapper.isCurse(enchantment);

        NamedTextColor color = isCurse ? NamedTextColor.RED : NamedTextColor.GRAY;

        return Component.text(displayText)
            .color(color)
            .decoration(TextDecoration.ITALIC, false);
    }

    // ========================================================================
    // Enchantment Extraction Helper
    // ========================================================================

    /**
     * Extracts enchantments from an item, handling both regular items
     * and enchanted books (EnchantmentStorageMeta).
     *
     * @param item the item to extract enchantments from
     * @return a mutable map of enchantments (empty map if item is null or has none)
     */
    private Map<Enchantment, Integer> getEnchantments(ItemStack item) {
        if (item == null) {
            return Map.of();
        }

        if (item.getType() == Material.ENCHANTED_BOOK
            && item.getItemMeta() instanceof EnchantmentStorageMeta bookMeta) {
            return new HashMap<>(bookMeta.getStoredEnchants());
        }

        return new HashMap<>(item.getEnchantments());
    }

    // ========================================================================
    // Anvil Protection (migrated from AnvilRecipeHandler)
    // ========================================================================

    /**
     * Registers a custom anvil recipe.
     *
     * <p>Thread-safe: customRecipes uses ConcurrentHashMap.</p>
     *
     * @param id     the unique recipe identifier
     * @param recipe the recipe implementation
     */
    public void registerRecipe(String id, AnvilRecipe recipe) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(recipe, "recipe must not be null");
        customRecipes.put(id, recipe);
    }

    /**
     * Unregisters a custom anvil recipe by its identifier.
     *
     * @param id the recipe identifier to remove
     */
    public void unregisterRecipe(String id) {
        Objects.requireNonNull(id, "id must not be null");
        customRecipes.remove(id);
    }

    /**
     * Handles custom anvil recipe matching.
     *
     * <p>Priority HIGH: runs before the HIGHEST-priority cache handler so that
     * custom recipes are applied first, and the cache handler sees the final result.</p>
     *
     * @param event the prepare anvil event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareAnvilCustomRecipe(PrepareAnvilEvent event) {
        if (!enabled) {
            return;
        }

        AnvilInventory inventory = event.getInventory();
        ItemStack firstItem = inventory.getFirstItem();
        ItemStack secondItem = inventory.getSecondItem();

        if (firstItem == null) {
            return;
        }

        // Why AnvilView rather than inventory.setRepairCost: Paper marked
        // AnvilInventory#setRepairCost/getRepairCost for removal and routed
        // the canonical cost-modification through AnvilView. PrepareAnvilEvent
        // always exposes the view as an AnvilView, so the cast is safe.
        AnvilView view = (AnvilView) event.getView();
        for (AnvilRecipe recipe : customRecipes.values()) {
            if (recipe.matches(firstItem, secondItem)) {
                ItemStack result = recipe.getResult(firstItem, secondItem);
                if (result != null) {
                    event.setResult(result);
                    int cost = recipe.getCost(firstItem, secondItem);
                    if (cost > 0) {
                        view.setRepairCost(cost);
                    }
                    break;
                }
            }
        }
    }

    /**
     * Caches the correct anvil result for Bedrock players when over-enchantments are involved.
     *
     * <p>Priority HIGHEST: runs after most other handlers have set the result,
     * so we can capture the final state and correct it for over-enchantment preservation.
     * Uses HIGHEST instead of MONITOR to comply with Bukkit event contract
     * (MONITOR should not modify event state).</p>
     *
     * @param event the prepare anvil event
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvilCacheResult(PrepareAnvilEvent event) {
        if (!enabled) {
            return;
        }

        EnchantmentConfig config = plugin.getGeyserExtraConfig().enchantment();
        if (!config.overEnchantmentProtectionEnabled()) {
            return;
        }

        AnvilInventory inventory = event.getInventory();
        ItemStack firstItem = inventory.getFirstItem();
        ItemStack secondItem = inventory.getSecondItem();
        ItemStack result = event.getResult();

        if (firstItem == null) {
            return;
        }

        for (var viewer : event.getViewers()) {
            if (!(viewer instanceof Player player) || !isBedrockPlayer(player)) {
                continue;
            }

            boolean hasOverEnchant = hasOverEnchantments(firstItem)
                || hasOverEnchantments(secondItem);
            if (!hasOverEnchant || result == null) {
                continue;
            }

            ItemStack correctResult = calculateCorrectResult(firstItem, secondItem, result);
            if (correctResult == null) {
                continue;
            }

            // Same Paper migration rationale as onPrepareAnvilCustomRecipe:
            // AnvilInventory#getRepairCost is deprecated for removal; AnvilView
            // is the supported accessor.
            int cost = ((AnvilView) event.getView()).getRepairCost();
            bedrockAnvilCache.put(player.getUniqueId(), new CachedAnvilResult(
                firstItem.clone(),
                secondItem != null ? secondItem.clone() : null,
                correctResult,
                cost
            ));

            // Update preview display on next tick to avoid modifying during event
            final ItemStack finalResult = correctResult;
            final ItemStack cachedFirst = firstItem.clone();
            final ItemStack cachedSecond = secondItem != null ? secondItem.clone() : null;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!(player.getOpenInventory().getTopInventory() instanceof AnvilInventory anvilInv)) {
                    return;
                }
                // Re-validate that anvil contents haven't changed during the tick
                if (!itemsMatch(cachedFirst, anvilInv.getFirstItem())
                    || !itemsMatch(cachedSecond, anvilInv.getSecondItem())) {
                    return;
                }
                anvilInv.setResult(finalResult);
                player.updateInventory();
            }, 1L);
        }
    }

    /**
     * Intercepts anvil result slot clicks for Bedrock players to apply cached correct results.
     *
     * <p>Priority HIGH: intercepts before other handlers to ensure the correct
     * over-enchantment result is applied instead of the Bedrock-downgraded version.</p>
     *
     * @param event the inventory click event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled || event.getInventory().getType() != InventoryType.ANVIL) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player) || !isBedrockPlayer(player)) {
            return;
        }

        // Only handle result slot (slot 2)
        if (event.getRawSlot() != 2) {
            return;
        }

        // Guard: skip if result slot is empty (prevents item creation from nothing)
        ItemStack currentResult = event.getCurrentItem();
        if (currentResult == null || currentResult.getType() == Material.AIR) {
            return;
        }

        CachedAnvilResult cached = bedrockAnvilCache.remove(player.getUniqueId());
        if (cached == null) {
            // No over-enchantment cache — let vanilla anvil handle normally.
            // Do NOT call updateInventory() here as it races with vanilla processing
            // and reverts the result (book combining would appear to fail).
            return;
        }

        AnvilInventory anvil = (AnvilInventory) event.getInventory();
        if (!itemsMatch(cached.firstItem(), anvil.getFirstItem())
            || !itemsMatch(cached.secondItem(), anvil.getSecondItem())) {
            return;
        }

        if (player.getLevel() < cached.cost() && player.getGameMode() != GameMode.CREATIVE) {
            return;
        }

        event.setCancelled(true);
        applyAnvilResult(player, anvil, cached);
    }

    /**
     * Cleans up anvil cache when a player closes an inventory.
     *
     * <p>Why: Without cleanup, cache entries for players who open an anvil but
     * never click the result slot would accumulate indefinitely (memory leak).</p>
     *
     * @param event the inventory close event
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getInventory().getType() == InventoryType.ANVIL
            && event.getPlayer() instanceof Player player) {
            bedrockAnvilCache.remove(player.getUniqueId());
        }
    }

    /**
     * Cleans up anvil cache when a player disconnects.
     *
     * <p>Why: Failsafe cleanup in case InventoryCloseEvent was not fired
     * (e.g., network disconnect without proper close sequence).</p>
     *
     * @param event the player quit event
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!enabled) {
            return;
        }
        bedrockAnvilCache.remove(event.getPlayer().getUniqueId());
    }

    // ========================================================================
    // Anvil Calculation Helpers
    // ========================================================================

    /**
     * Checks whether an item has any enchantments exceeding vanilla max levels.
     *
     * @param item the item to check
     * @return true if any enchantment level exceeds its vanilla maximum
     */
    private boolean hasOverEnchantments(ItemStack item) {
        if (item == null) {
            return false;
        }

        for (var entry : getEnchantments(item).entrySet()) {
            int maxLevel = entry.getKey().getMaxLevel();
            if (maxLevel > 0 && entry.getValue() > maxLevel) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calculates the correct anvil result preserving over-enchantment levels.
     *
     * <p>Why: The vanilla anvil result may have levels capped at vanilla maximums
     * by the Bedrock client. This method recalculates the result using server-side
     * enchantment data to ensure over-enchantments are preserved correctly.</p>
     *
     * @param firstItem     the first anvil input
     * @param secondItem    the second anvil input
     * @param vanillaResult the vanilla-computed result
     * @return the corrected result, or null if combination should be blocked
     */
    private ItemStack calculateCorrectResult(
        ItemStack firstItem,
        ItemStack secondItem,
        ItemStack vanillaResult
    ) {
        if (vanillaResult == null) {
            return null;
        }

        ItemStack result = vanillaResult.clone();
        ItemMeta resultMeta = result.getItemMeta();
        if (resultMeta == null) {
            return vanillaResult;
        }

        Map<Enchantment, Integer> firstEnchants = getEnchantments(firstItem);
        Map<Enchantment, Integer> secondEnchants = getEnchantments(secondItem);
        Map<Enchantment, Integer> vanillaEnchants = getEnchantments(vanillaResult);
        Map<Enchantment, Integer> combinedEnchants = new HashMap<>(firstEnchants);

        boolean levelUpEnabled = plugin.getGeyserExtraConfig()
            .enchantment()
            .overEnchantmentLevelUpEnabled();

        // Merge second item's enchantments with conflict checking
        for (var entry : secondEnchants.entrySet()) {
            Enchantment enchant = entry.getKey();
            int secondLevel = entry.getValue();
            int currentLevel = combinedEnchants.getOrDefault(enchant, 0);

            // Check conflicts in both directions for custom enchantment compatibility
            boolean hasConflict = combinedEnchants.keySet().stream()
                .anyMatch(e -> !e.equals(enchant)
                    && (enchant.conflictsWith(e) || e.conflictsWith(enchant)));

            if (!hasConflict) {
                if (secondLevel > currentLevel) {
                    combinedEnchants.put(enchant, secondLevel);
                } else if (secondLevel == currentLevel && currentLevel > 0) {
                    int maxLevel = enchant.getMaxLevel();
                    boolean isOverEnchant = maxLevel > 0 && currentLevel >= maxLevel;
                    boolean canLevelUp = !isOverEnchant || levelUpEnabled;
                    if (canLevelUp && currentLevel < 255) {
                        combinedEnchants.put(enchant, currentLevel + 1);
                    }
                    // else: preserve currentLevel (already seeded from firstEnchants).
                    // Why not return null here: the caller would fall through to the
                    // vanilla anvil result, which caps same-level combining at maxLevel
                    // (e.g. Fortune V + V -> III on vanilla) and destroys the player's
                    // over-enchant. Keeping the existing currentLevel in combinedEnchants
                    // means the override still fires and the result shows the preserved
                    // over-enchant level, matching the simulator path's stance that
                    // level-up beyond max requires explicit opt-in.
                }
            }
        }

        // Preserve any vanilla-result enchantments not already in combined set
        for (var entry : vanillaEnchants.entrySet()) {
            combinedEnchants.putIfAbsent(entry.getKey(), entry.getValue());
        }

        // Apply combined enchantments to result
        applyEnchantmentsToMeta(result, resultMeta, combinedEnchants);
        result.setItemMeta(resultMeta);
        return result;
    }

    /**
     * Applies enchantments to item meta, handling both regular items and enchanted books.
     *
     * <p>Copies the keySet before iteration to avoid ConcurrentModificationException,
     * since removeStoredEnchant/removeEnchant may modify the underlying map.</p>
     *
     * @param item              the item (used to check type)
     * @param meta              the item meta to modify
     * @param enchantments      the enchantments to apply
     */
    private void applyEnchantmentsToMeta(
        ItemStack item,
        ItemMeta meta,
        Map<Enchantment, Integer> enchantments
    ) {
        boolean isBook = item.getType() == Material.ENCHANTED_BOOK;

        if (isBook && meta instanceof EnchantmentStorageMeta bookMeta) {
            // Copy keySet to avoid ConcurrentModificationException
            new ArrayList<>(bookMeta.getStoredEnchants().keySet())
                .forEach(bookMeta::removeStoredEnchant);
            enchantments.forEach((e, l) -> bookMeta.addStoredEnchant(e, l, true));
        } else {
            // Copy keySet to avoid ConcurrentModificationException
            new ArrayList<>(meta.getEnchants().keySet())
                .forEach(meta::removeEnchant);
            enchantments.forEach((e, l) -> meta.addEnchant(e, l, true));
        }
    }

    /**
     * Applies the cached anvil result: clears anvil slots, deducts XP, and gives item.
     *
     * <p>XP deduction is guarded with Math.max(0, ...) to prevent negative levels
     * in case of TOCTOU race between level check and deduction.</p>
     *
     * <p>Any inventory overflow is dropped naturally at the player's location.</p>
     *
     * @param player the player
     * @param anvil  the anvil inventory
     * @param cached the cached result to apply
     */
    private void applyAnvilResult(Player player, AnvilInventory anvil, CachedAnvilResult cached) {
        anvil.setFirstItem(null);

        // Consume only 1 from secondItem (e.g., repair material may be stacked)
        // Clone to avoid mutating the server's internal ItemStack reference
        ItemStack secondItem = anvil.getSecondItem();
        if (secondItem != null && secondItem.getAmount() > 1) {
            ItemStack remaining = secondItem.clone();
            remaining.setAmount(remaining.getAmount() - 1);
            anvil.setSecondItem(remaining);
        } else {
            anvil.setSecondItem(null);
        }

        anvil.setResult(null);

        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setLevel(Math.max(0, player.getLevel() - cached.cost()));
        }

        HashMap<Integer, ItemStack> overflow = player.getInventory()
            .addItem(cached.result().clone());
        overflow.values().forEach(
            overflowItem -> player.getWorld().dropItemNaturally(player.getLocation(), overflowItem)
        );

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.closeInventory();
                player.updateInventory();
            }
        });
    }

    /**
     * Checks whether two items match for anvil cache validation.
     *
     * <p>Compares type, amount, display name, and enchantments to prevent
     * cache misuse when items are swapped between cache creation and click.</p>
     *
     * @param cached  the cached item
     * @param current the current item in the anvil
     * @return true if the items are considered matching
     */
    private boolean itemsMatch(ItemStack cached, ItemStack current) {
        if (cached == null && current == null) {
            return true;
        }
        if (cached == null || current == null) {
            return false;
        }
        if (cached.getType() != current.getType()) {
            return false;
        }
        if (cached.getAmount() != current.getAmount()) {
            return false;
        }

        // Compare enchantments (covers both regular items and enchanted books)
        if (!getEnchantments(cached).equals(getEnchantments(current))) {
            return false;
        }

        ItemMeta cachedMeta = cached.getItemMeta();
        ItemMeta currentMeta = current.getItemMeta();
        if (cachedMeta != null && currentMeta != null) {
            if (cachedMeta.hasDisplayName() != currentMeta.hasDisplayName()) {
                return false;
            }
            if (cachedMeta.hasDisplayName()
                && !Objects.equals(cachedMeta.displayName(), currentMeta.displayName())) {
                return false;
            }
        }
        return true;
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Safely retrieves the player's UUID, returning null for temporary players.
     *
     * <p>During the login process, Paper creates temporary player objects that
     * throw UnsupportedOperationException when getUniqueId() is called.
     * ProtocolLib packet listeners can fire during this phase, so we must
     * handle this gracefully.</p>
     *
     * @param player the player to get UUID from
     * @return the player's UUID, or null if unavailable (temporary player)
     */
    private UUID getPlayerUuidSafely(Player player) {
        try {
            return player.getUniqueId();
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    /**
     * Checks whether a player is a Bedrock player via FloodgateApi.
     *
     * <p>Returns false for temporary players whose UUID cannot be retrieved.</p>
     *
     * @param player the player to check
     * @return true if the player is connected via Bedrock Edition
     */
    private boolean isBedrockPlayer(Player player) {
        UUID uuid = getPlayerUuidSafely(player);
        return uuid != null && floodgateApi != null && floodgateApi.isFloodgatePlayer(uuid);
    }

    /**
     * Whether the given Bedrock player has opted in to receiving the lore-tooltip
     * injection (durability / over-enchant lines). Defaults to true when the
     * settings manager is unavailable or the player has no recorded preference
     * yet, matching the pre-toggle behaviour.
     */
    private boolean isLoreTooltipEnabledFor(UUID playerId) {
        if (playerId == null) {
            return true;
        }
        com.geyserextra.paper.settings.PlayerSettingsManager manager = plugin.getPlayerSettingsManager();
        if (manager == null) {
            return true;
        }
        com.geyserextra.paper.settings.PlayerSettings settings;
        try {
            settings = manager.getSettings(playerId);
        } catch (Throwable t) {
            return true;
        }
        return settings == null || settings.isLoreTooltipEnabled();
    }

    // ========================================================================
    // Inner Types
    // ========================================================================

    /**
     * Represents a custom anvil recipe that can be registered with this handler.
     */
    public interface AnvilRecipe {
        boolean matches(ItemStack firstItem, ItemStack secondItem);
        ItemStack getResult(ItemStack firstItem, ItemStack secondItem);
        default int getCost(ItemStack firstItem, ItemStack secondItem) { return 0; }
    }

    /**
     * A simple anvil recipe implementation that matches by material type.
     */
    public static class SimpleAnvilRecipe implements AnvilRecipe {

        private final Material firstMaterial;
        private final Material secondMaterial;
        private final BiFunction<ItemStack, ItemStack, ItemStack> resultFunction;
        private final int cost;

        public SimpleAnvilRecipe(
            Material first,
            Material second,
            BiFunction<ItemStack, ItemStack, ItemStack> resultFn,
            int cost
        ) {
            this.firstMaterial = Objects.requireNonNull(first, "first material must not be null");
            this.secondMaterial = second;
            this.resultFunction = Objects.requireNonNull(resultFn, "resultFn must not be null");
            this.cost = cost;
        }

        @Override
        public boolean matches(ItemStack first, ItemStack second) {
            if (first == null || first.getType() != firstMaterial) {
                return false;
            }
            if (secondMaterial == null) {
                return second == null || second.getType() == Material.AIR;
            }
            return second != null && second.getType() == secondMaterial;
        }

        @Override
        public ItemStack getResult(ItemStack first, ItemStack second) {
            return resultFunction.apply(first, second);
        }

        @Override
        public int getCost(ItemStack first, ItemStack second) {
            return cost;
        }
    }

    /**
     * Cached anvil result for a Bedrock player, used to preserve over-enchantments.
     */
    private record CachedAnvilResult(
        ItemStack firstItem,
        ItemStack secondItem,
        ItemStack result,
        int cost
    ) {}
}
