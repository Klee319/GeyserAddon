package com.geyserextra.paper.pack;

import com.geyserextra.core.api.CustomItemMapping;
import com.geyserextra.core.registry.ItemMappingRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconciler exists because the registry used to be append-only and two
 * real production failures became permanent: placeholder display names ("糸")
 * written while the pack was unreadable, and stale ghosts left behind when an
 * item kept its CMD but changed base material. Every test here mirrors one of
 * those shapes with the actual thread-item data from 2026-08-24.
 */
class PackRegistryReconcilerTest {

    private static final String STRING = "minecraft:string";
    private static final String TEMPLATE = "minecraft:raiser_armor_trim_smithing_template";

    /** Lang table the fake resolver serves; keyed by model ref. */
    private final Map<String, String> lang = new LinkedHashMap<>();

    private ItemMappingRegistry registry;

    private final BiFunction<JavaPackReader.CmdKey, JavaPackReader.JavaModelDefinition, String>
        langResolver = (key, def) -> lang.get(def.modelRef());

    private final Function<String, String> fallbackDisplay =
        base -> "Pretty " + base.substring(base.indexOf(':') + 1);

    private final Function<String, Set<String>> placeholderDisplays =
        base -> Set.of("糸", "String", "Pretty " + base.substring(base.indexOf(':') + 1));

    @BeforeEach
    void setUp() {
        registry = new ItemMappingRegistry();
        lang.clear();
    }

    private static Map<JavaPackReader.CmdKey, JavaPackReader.JavaModelDefinition> pack(
        Object... baseCmdModelTriples
    ) {
        Map<JavaPackReader.CmdKey, JavaPackReader.JavaModelDefinition> entries =
            new LinkedHashMap<>();
        for (int i = 0; i < baseCmdModelTriples.length; i += 3) {
            String base = (String) baseCmdModelTriples[i];
            int cmd = (Integer) baseCmdModelTriples[i + 1];
            String modelRef = (String) baseCmdModelTriples[i + 2];
            entries.put(new JavaPackReader.CmdKey(base, cmd),
                new JavaPackReader.JavaModelDefinition(base, cmd, modelRef, null, null));
        }
        return entries;
    }

    private PackRegistryReconciler.Result reconcile(
        Map<JavaPackReader.CmdKey, JavaPackReader.JavaModelDefinition> packEntries
    ) {
        return PackRegistryReconciler.reconcile(
            registry, packEntries, langResolver, fallbackDisplay, placeholderDisplays, line -> { });
    }

    @Test
    @DisplayName("pack entry with no registry entry is added, with the lang-resolved display name")
    void addsMissingEntry() {
        lang.put("trinityforge:item/thread_speed", "迅速のスレッド");

        PackRegistryReconciler.Result result =
            reconcile(pack(STRING, 300004, "trinityforge:item/thread_speed"));

        assertThat(result.added()).isEqualTo(1);
        CustomItemMapping added = registry.getByCustomModelData(STRING, 300004).orElseThrow();
        assertThat(added.name()).isEqualTo("custom_string_300004");
        assertThat(added.displayName()).isEqualTo("迅速のスレッド");
        assertThat(added.register()).isTrue();
    }

    @Test
    @DisplayName("auto-named entry stuck with the material-name placeholder is healed in place")
    void healsPlaceholderDisplayName() {
        // The production shape: registered while the pack was unreadable, so
        // the display name fell back to the base material's vanilla name.
        registry.register(new CustomItemMapping(
            "custom_string_300002", STRING, 300002, false, "糸", null,
            CustomItemMapping.CREATIVE_CATEGORY_ITEMS, null, true));
        lang.put("trinityforge:item/thread_mana_regen", "マナ回復速度上昇のスレッド");

        PackRegistryReconciler.Result result =
            reconcile(pack(STRING, 300002, "trinityforge:item/thread_mana_regen"));

        assertThat(result.healed()).isEqualTo(1);
        CustomItemMapping healed = registry.getByCustomModelData(STRING, 300002).orElseThrow();
        // The identifier must stay stable — only the display heals.
        assertThat(healed.name()).isEqualTo("custom_string_300002");
        assertThat(healed.displayName()).isEqualTo("マナ回復速度上昇のスレッド");
    }

    @Test
    @DisplayName("operator-curated display name on an auto-named entry is never overwritten")
    void leavesCuratedDisplayNameAlone() {
        registry.register(new CustomItemMapping(
            "custom_string_300002", STRING, 300002, false, "運営が付けた名前", null,
            CustomItemMapping.CREATIVE_CATEGORY_ITEMS, null, true));
        lang.put("trinityforge:item/thread_mana_regen", "マナ回復速度上昇のスレッド");

        PackRegistryReconciler.Result result =
            reconcile(pack(STRING, 300002, "trinityforge:item/thread_mana_regen"));

        assertThat(result.healed()).isZero();
        assertThat(registry.getByCustomModelData(STRING, 300002).orElseThrow().displayName())
            .isEqualTo("運営が付けた名前");
    }

    @Test
    @DisplayName("non-auto-named entries are never healed, even with a placeholder display")
    void leavesNonAutoNamesAlone() {
        registry.register(new CustomItemMapping(
            "operator_chosen_name", STRING, 300002, false, "糸", null,
            CustomItemMapping.CREATIVE_CATEGORY_ITEMS, null, true));
        lang.put("trinityforge:item/thread_mana_regen", "マナ回復速度上昇のスレッド");

        PackRegistryReconciler.Result result =
            reconcile(pack(STRING, 300002, "trinityforge:item/thread_mana_regen"));

        assertThat(result.healed()).isZero();
        assertThat(registry.getByCustomModelData(STRING, 300002).orElseThrow().displayName())
            .isEqualTo("糸");
    }

    @Test
    @DisplayName("unresolved lang never 'heals' a placeholder into another placeholder")
    void doesNotChurnWhenLangUnresolved() {
        registry.register(new CustomItemMapping(
            "custom_string_300002", STRING, 300002, false, "糸", null,
            CustomItemMapping.CREATIVE_CATEGORY_ITEMS, null, true));
        // No lang entry for the model ref → resolver returns null.

        PackRegistryReconciler.Result result =
            reconcile(pack(STRING, 300002, "trinityforge:item/thread_mana_regen"));

        assertThat(result.healed()).isZero();
        assertThat(registry.getByCustomModelData(STRING, 300002).orElseThrow().displayName())
            .isEqualTo("糸");
    }

    @Test
    @DisplayName("stale ghost of a re-based item (same CMD, same display, base gone from pack) is evicted")
    void evictsStaleRebasedGhost() {
        // The production shape: the thread once observed as an armor-trim
        // template, while today's pack defines the same item on string.
        registry.register(new CustomItemMapping(
            "thread_speed", TEMPLATE, 300004, false, "迅速のスレッド", null,
            CustomItemMapping.CREATIVE_CATEGORY_ITEMS, null, true));
        lang.put("trinityforge:item/thread_speed", "迅速のスレッド");

        PackRegistryReconciler.Result result =
            reconcile(pack(STRING, 300004, "trinityforge:item/thread_speed"));

        assertThat(result.evicted()).isEqualTo(1);
        assertThat(registry.getByCustomModelData(TEMPLATE, 300004)).isEmpty();
        // The live item was added in the same pass.
        assertThat(result.added()).isEqualTo(1);
        assertThat(registry.getByCustomModelData(STRING, 300004)).isPresent();
    }

    @Test
    @DisplayName("entry sharing only the CMD (different display) with a pack item is kept")
    void keepsCmdOnlyCoincidence() {
        registry.register(new CustomItemMapping(
            "other_plugin_item", "minecraft:paper", 300004, false, "別プラグインの品", null,
            CustomItemMapping.CREATIVE_CATEGORY_ITEMS, null, true));
        lang.put("trinityforge:item/thread_speed", "迅速のスレッド");

        PackRegistryReconciler.Result result =
            reconcile(pack(STRING, 300004, "trinityforge:item/thread_speed"));

        assertThat(result.evicted()).isZero();
        assertThat(registry.getByCustomModelData("minecraft:paper", 300004)).isPresent();
    }

    @Test
    @DisplayName("entry whose (base, cmd) is still in the pack is never evicted")
    void keepsLivePackEntries() {
        registry.register(new CustomItemMapping(
            "thread_gacha", STRING, 100024, true, "ガチャスレッド", null,
            CustomItemMapping.CREATIVE_CATEGORY_ITEMS, null, true));
        lang.put("trinityforge:item/thread_gacha", "ガチャスレッド");

        PackRegistryReconciler.Result result =
            reconcile(pack(STRING, 100024, "trinityforge:item/thread_gacha"));

        assertThat(result.evicted()).isZero();
        assertThat(registry.getByCustomModelData(STRING, 100024)).isPresent();
    }

    @Test
    @DisplayName("scanner entries from other plugins with no pack presence are kept")
    void keepsForeignScannerEntries() {
        registry.register(new CustomItemMapping(
            "custom_paper_42", "minecraft:paper", 42, false, "他プラグインの紙", null,
            CustomItemMapping.CREATIVE_CATEGORY_ITEMS, null, true));
        lang.put("trinityforge:item/thread_speed", "迅速のスレッド");

        PackRegistryReconciler.Result result =
            reconcile(pack(STRING, 300004, "trinityforge:item/thread_speed"));

        assertThat(result.evicted()).isZero();
        assertThat(registry.getByCustomModelData("minecraft:paper", 42)).isPresent();
    }

    @Test
    @DisplayName("lang failure on add falls back to the prettified base name (legacy behaviour)")
    void addFallsBackToPrettifiedName() {
        PackRegistryReconciler.Result result =
            reconcile(pack(STRING, 300010, "trinityforge:item/thread_hero_of_the_village"));

        assertThat(result.added()).isEqualTo(1);
        assertThat(registry.getByCustomModelData(STRING, 300010).orElseThrow().displayName())
            .isEqualTo("Pretty string");
    }

    @Test
    @DisplayName("CMD<=0 pack entries are ignored entirely")
    void ignoresNonPositiveCmd() {
        PackRegistryReconciler.Result result =
            reconcile(pack(STRING, 0, "trinityforge:item/whatever"));

        assertThat(result.added()).isZero();
        assertThat(registry.isEmpty()).isTrue();
    }
}
