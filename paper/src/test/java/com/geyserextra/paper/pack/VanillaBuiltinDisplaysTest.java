package com.geyserextra.paper.pack;

import com.geyserextra.core.config.GeyserExtraConfig.AttachableGenerationConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("Vanilla builtin display injection for CMD handheld items")
class VanillaBuiltinDisplaysTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("parent item/handheld only resolves Mojang handheld + generated slots")
    void handheldParentInjectsBuiltinDisplay() throws Exception {
        Path model = tempDir.resolve(
            "assets/minecraft/models/item/custom_cmd_sword.json");
        Path texture = tempDir.resolve(
            "assets/minecraft/textures/item/custom_cmd_sword.png");
        Files.createDirectories(model.getParent());
        Files.createDirectories(texture.getParent());
        Files.writeString(model, """
            {"parent":"item/handheld","textures":{
            "layer0":"minecraft:item/custom_cmd_sword"}}
            """);
        Files.write(texture, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        JavaPackReader reader = new JavaPackReader(
            tempDir, "AUTO", Logger.getAnonymousLogger(), false);
        JavaModelDisplay display =
            reader.resolveDisplayFromModel("minecraft:item/custom_cmd_sword");

        assertThat(display).isNotNull();
        assertThat(display.hasAnyHandTransform()).isTrue();

        float[] tpRot = display.thirdpersonRighthand().rotation();
        float[] tpTrans = display.thirdpersonRighthand().translation();
        float[] tpScale = display.thirdpersonRighthand().scale();
        assertThat(tpRot[0]).isCloseTo(0f, within(0.001f));
        assertThat(tpRot[1]).isCloseTo(-90f, within(0.001f));
        assertThat(tpRot[2]).isCloseTo(55f, within(0.001f));
        assertThat(tpTrans[1]).isCloseTo(4f, within(0.001f));
        assertThat(tpTrans[2]).isCloseTo(0.5f, within(0.001f));
        assertThat(tpScale[0]).isCloseTo(0.85f, within(0.001f));

        // generated fills ground after handheld → generated walk
        assertThat(display.ground()).isNotNull();
        assertThat(display.ground().scale()[0]).isCloseTo(0.5f, within(0.001f));

        // The builtin merge has to carry the left-hand slots across as well.
        // Copying only the five right-hand/gui/ground/head slots left these
        // null, which reads as "mirror the right hand" and turned every
        // handheld-inherited tool backwards in the off hand.
        assertThat(display.thirdpersonLefthand())
            .as("thirdperson_lefthand inherited from item/handheld").isNotNull();
        assertThat(display.thirdpersonLefthand().rotation())
            .containsExactly(0f, 90f, -55f);
        assertThat(display.firstpersonLefthand())
            .as("firstperson_lefthand inherited from item/handheld").isNotNull();
        assertThat(display.firstpersonLefthand().rotation())
            .containsExactly(0f, 90f, -25f);
    }

    @Test
    @DisplayName("handheld builtin display produces third-person attachable animation")
    void handheldDisplayEmitsThirdPersonAttachable() {
        AttachableGenerationConfig config = new AttachableGenerationConfig(
            AttachableGenerationConfig.MODE_FULL, false);

        Map<String, String> artifacts = BedrockAttachableWriter.buildArtifacts(
            "custom_cmd_sword",
            VanillaBuiltinDisplays.HANDHELD,
            null,
            "textures/items/custom_cmd_sword",
            16, 16, config, null);

        assertThat(artifacts).isNotEmpty();
        String anim = artifacts.get(
            BedrockAttachableWriter.animationEntryPath("custom_cmd_sword"));
        assertThat(anim).contains("thirdperson_main_hand");
        // Java [0,-90,55] → Bedrock rot [0,90,55]; trans TP main (0,4,0.5)
        assertThat(anim).contains("90.0");
        assertThat(anim).contains("55.0");
        assertThat(anim).contains("4.0");
        assertThat(anim).contains("0.5");
        assertThat(anim).contains("0.85");
    }

    @Test
    @DisplayName("handheld declares its left-hand slots, so Java renders both hands alike")
    void handheldDeclaresLeftHandSlots() {
        // assets/minecraft/models/item/handheld.json declares
        //   thirdperson_lefthand rotation [0, 90, -55]
        //   firstperson_lefthand rotation [0, 90, -25]
        // Mojang negates rotation Y/Z for the left hand, so those come back out
        // as the right-hand values: an omitted slot would render mirrored, a
        // declared one renders identically. Pinning the declaration is what
        // stops the constant from silently reverting to "mirror the right hand".
        JavaModelDisplay.Transform tpLeft = VanillaBuiltinDisplays.HANDHELD.thirdpersonLefthand();
        JavaModelDisplay.Transform fpLeft = VanillaBuiltinDisplays.HANDHELD.firstpersonLefthand();
        assertThat(tpLeft).isNotNull();
        assertThat(fpLeft).isNotNull();
        assertThat(tpLeft.rotation()).containsExactly(0f, 90f, -55f);
        assertThat(fpLeft.rotation()).containsExactly(0f, 90f, -25f);
        assertThat(tpLeft.translation()).containsExactly(0f, 4f, 0.5f);
        assertThat(fpLeft.translation()).containsExactly(1.13f, 3.2f, 1.13f);

        // generated.json genuinely omits both, and mirroring is then correct.
        assertThat(VanillaBuiltinDisplays.GENERATED.thirdpersonLefthand()).isNull();
        assertThat(VanillaBuiltinDisplays.GENERATED.firstpersonLefthand()).isNull();
    }

    @Test
    @DisplayName("a handheld item's off hand comes out identical to its main hand")
    void handheldOffHandMatchesMainHand() {
        // The visible symptom of dropping handheld's left-hand slots: the off
        // hand came out y=-90/z=-55 against the main hand's y=+90/z=+55, i.e.
        // turned 180 degrees about Y with the roll reversed - blade backwards.
        // Third person is the clean comparison because handheld's translation X
        // is 0, so the two blocks must agree byte for byte.
        AttachableGenerationConfig config = new AttachableGenerationConfig(
            AttachableGenerationConfig.MODE_FULL, false);
        String anim = BedrockAttachableWriter.buildArtifacts(
            "custom_cmd_sword",
            VanillaBuiltinDisplays.HANDHELD,
            null,
            "textures/items/custom_cmd_sword",
            16, 16, config, null)
            .get(BedrockAttachableWriter.animationEntryPath("custom_cmd_sword"));

        String main = jsonBlockAfter(anim, "thirdperson_main_hand");
        String off = jsonBlockAfter(anim, "thirdperson_off_hand");
        assertThat(main).isNotNull();
        assertThat(off).as("off-hand third-person animation").isEqualTo(main);
    }

    @Nested
    @DisplayName("unresolved vanilla parent classification")
    class ParentClassification {

        @Test
        @DisplayName("tool suffixes reach handheld across every material tier")
        void toolSuffixesResolveToHandheld() {
            // Suffix rather than an enumerated tier list, so copper (1.21.9)
            // and any future material stay classified without an edit.
            for (String name : new String[]{
                "minecraft:item/netherite_sword", "item/copper_sword",
                "minecraft:item/diamond_pickaxe", "minecraft:item/wooden_hoe",
                "minecraft:item/netherite_axe", "minecraft:item/diamond_shovel"}) {
                assertThat(VanillaBuiltinDisplays.forUnresolvedParent(name))
                    .as(name).isSameAs(VanillaBuiltinDisplays.HANDHELD);
                assertThat(VanillaBuiltinDisplays.nextBuiltinParent(name))
                    .as(name).isEqualTo("item/generated");
            }
        }

        @Test
        @DisplayName("everything unmatched falls back to generated, not handheld")
        void unmatchedNamesFallBackToGenerated() {
            // This is the fix. The previous revision returned HANDHELD for any
            // item/* name, which put full armour sets, ingots and shards into a
            // sword grip. item/generated is what these actually inherit in the
            // client jar (verified against 1.21.11).
            for (String name : new String[]{
                "minecraft:item/netherite_helmet", "minecraft:item/diamond_boots",
                "minecraft:item/iron_ingot", "minecraft:item/emerald",
                "minecraft:item/string", "minecraft:item/leather",
                "minecraft:item/echo_shard", "minecraft:item/prismarine_shard",
                "minecraft:item/turtle_scute", "minecraft:item/iron_chain",
                "minecraft:item/netherite_scrap"}) {
                assertThat(VanillaBuiltinDisplays.forUnresolvedParent(name))
                    .as(name).isSameAs(VanillaBuiltinDisplays.GENERATED);
                assertThat(VanillaBuiltinDisplays.nextBuiltinParent(name))
                    .as(name).isNull();
            }
        }

        @Test
        @DisplayName("bow and its pulling frames carry bow's own asymmetric pose")
        void bowFamilyResolvesToBow() {
            for (String name : new String[]{
                "minecraft:item/bow", "minecraft:item/bow_pulling_0",
                "minecraft:item/bow_pulling_1", "minecraft:item/bow_pulling_2"}) {
                assertThat(VanillaBuiltinDisplays.forUnresolvedParent(name))
                    .as(name).isSameAs(VanillaBuiltinDisplays.BOW);
            }
            // The left hand is genuinely not a mirror here: Mojang negates Y/Z
            // for the off hand, so -280 comes back as +280 against the main
            // hand's +260. Twenty degrees apart, by declaration.
            assertThat(VanillaBuiltinDisplays.BOW.thirdpersonRighthand().rotation())
                .containsExactly(-80f, 260f, -40f);
            assertThat(VanillaBuiltinDisplays.BOW.thirdpersonLefthand().rotation())
                .containsExactly(-80f, -280f, 40f);
        }

        @Test
        @DisplayName("mace, trident and spear each get their own vanilla pose")
        void bespokeWeaponsAreNotLumpedIntoHandheld() {
            assertThat(VanillaBuiltinDisplays.forUnresolvedParent("minecraft:item/mace"))
                .isSameAs(VanillaBuiltinDisplays.HANDHELD_MACE);
            assertThat(VanillaBuiltinDisplays.nextBuiltinParent("minecraft:item/mace"))
                .isEqualTo("item/handheld");

            // trident.json / netherite_spear.json are plain generated: the held
            // pose lives in the separate *_in_hand model, and a pack model that
            // parents on the flat one inherits the flat display in Java too.
            assertThat(VanillaBuiltinDisplays.forUnresolvedParent("minecraft:item/trident"))
                .isSameAs(VanillaBuiltinDisplays.GENERATED);
            assertThat(VanillaBuiltinDisplays.forUnresolvedParent("minecraft:item/netherite_spear"))
                .isSameAs(VanillaBuiltinDisplays.GENERATED);

            assertThat(VanillaBuiltinDisplays.forUnresolvedParent("minecraft:item/trident_in_hand"))
                .isSameAs(VanillaBuiltinDisplays.TRIDENT_IN_HAND);
            assertThat(VanillaBuiltinDisplays.nextBuiltinParent("minecraft:item/trident_in_hand"))
                .as("trident_in_hand has no parent in the client jar").isNull();

            for (String name : new String[]{
                "minecraft:item/spear_in_hand", "minecraft:item/netherite_spear_in_hand",
                "minecraft:item/diamond_spear_in_hand"}) {
                assertThat(VanillaBuiltinDisplays.forUnresolvedParent(name))
                    .as(name).isSameAs(VanillaBuiltinDisplays.SPEAR_IN_HAND);
            }
        }

        @Test
        @DisplayName("spear_in_hand keeps its non-uniform scale")
        void spearScaleIsNotCollapsedToAScalar() {
            // 1.7/1.7/0.85 - the blade is stretched 2:1 against Z. A scalar
            // scale here renders every spear stubby, which is why the builtin
            // table needs a per-axis constructor at all.
            assertThat(VanillaBuiltinDisplays.SPEAR_IN_HAND.thirdpersonRighthand().scale())
                .containsExactly(1.7f, 1.7f, 0.85f);
            assertThat(VanillaBuiltinDisplays.SPEAR_IN_HAND.firstpersonRighthand().scale())
                .containsExactly(1.36f, 1.36f, 0.68f);
        }

        @Test
        @DisplayName("rod-shaped vanilla items keep the handheld grip they really inherit")
        void rodItemsAreNotDemotedToGenerated() {
            // Regression guard for the new generated default: these five carry
            // no tool suffix but do parent on item/handheld in the client jar,
            // so a plain "default to generated" rule would have quietly flipped
            // them from a grip to a flat hold. item/stick is referenced by the
            // reference pack.
            for (String name : new String[]{
                "minecraft:item/stick", "minecraft:item/debug_stick",
                "minecraft:item/blaze_rod", "minecraft:item/breeze_rod",
                "minecraft:item/bone", "minecraft:item/bamboo"}) {
                assertThat(VanillaBuiltinDisplays.forUnresolvedParent(name))
                    .as(name).isSameAs(VanillaBuiltinDisplays.HANDHELD);
            }
            assertThat(VanillaBuiltinDisplays.forUnresolvedParent("minecraft:item/fishing_rod"))
                .isSameAs(VanillaBuiltinDisplays.HANDHELD_ROD);
            assertThat(VanillaBuiltinDisplays.nextBuiltinParent("minecraft:item/fishing_rod"))
                .isEqualTo("item/handheld");
        }

        @Test
        @DisplayName("non-item refs are still declined so the parent walk can stop")
        void nonItemRefsReturnNull() {
            assertThat(VanillaBuiltinDisplays.forUnresolvedParent("block/cube_all")).isNull();
            assertThat(VanillaBuiltinDisplays.forUnresolvedParent("builtin/generated")).isNull();
            assertThat(VanillaBuiltinDisplays.forUnresolvedParent(null)).isNull();
            assertThat(VanillaBuiltinDisplays.forUnresolvedParent("")).isNull();
            assertThat(VanillaBuiltinDisplays.nextBuiltinParent("block/cube_all")).isNull();
        }

        @Test
        @DisplayName("display lookup and parent lookup never disagree")
        void displayAndNextParentStayInSync() {
            // The two entry points share one classifier precisely so they
            // cannot drift: a name that yields a display must also yield a
            // well-formed continuation, and a name that yields none must end
            // the walk. Drift here silently truncates the merge and leaves
            // ground/head unset on a whole family of items.
            for (String name : new String[]{
                "item/handheld", "item/generated", "item/handheld_rod",
                "item/handheld_mace", "item/mace", "item/bow", "item/bow_pulling_2",
                "item/crossbow", "item/crossbow_pulling_1", "item/shield",
                "item/trident_in_hand", "item/spear_in_hand", "item/netherite_sword",
                "item/emerald", "block/cube_all", "builtin/generated"}) {
                boolean hasDisplay = VanillaBuiltinDisplays.forUnresolvedParent(name) != null;
                String next = VanillaBuiltinDisplays.nextBuiltinParent(name);
                if (!hasDisplay) {
                    assertThat(next).as("%s yields no display", name).isNull();
                } else if (next != null) {
                    assertThat(VanillaBuiltinDisplays.forUnresolvedParent(next))
                        .as("%s continues to %s, which must itself resolve", name, next)
                        .isNotNull();
                }
            }
        }
    }

    @Test
    @DisplayName("a pack model parented on item/bow inherits bow's pose, not a sword grip")
    void bowParentResolvesThroughTheReader() throws Exception {
        // End-to-end through the parent walk: this is the path that was
        // returning HANDHELD for all 40 bow-family entries in the reference
        // pack, so the unit-level classification alone is not enough of a guard.
        Path model = tempDir.resolve("assets/minecraft/models/item/custom_bow.json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, """
            {"parent":"minecraft:item/bow_pulling_1","textures":{
            "layer0":"minecraft:item/custom_bow"}}
            """);

        JavaPackReader reader = new JavaPackReader(
            tempDir, "AUTO", Logger.getAnonymousLogger(), false);
        JavaModelDisplay display =
            reader.resolveDisplayFromModel("minecraft:item/custom_bow");

        assertThat(display).isNotNull();
        assertThat(display.thirdpersonRighthand().rotation())
            .containsExactly(-80f, 260f, -40f);
        assertThat(display.thirdpersonRighthand().translation())
            .containsExactly(-1f, -2f, 2.5f);
        // ground/head still arrive, because bow chains on to item/generated.
        assertThat(display.ground()).as("ground inherited via bow -> generated").isNotNull();
        assertThat(display.head()).as("head inherited via bow -> generated").isNotNull();
    }

    /**
     * Returns the balanced {@code { ... }} object that follows {@code key},
     * so two animation blocks can be compared as wholes instead of by
     * substring probes that cannot tell {@code 90.0} from {@code -90.0}.
     */
    private static String jsonBlockAfter(String json, String key) {
        int at = json.indexOf(key);
        if (at < 0) {
            return null;
        }
        int open = json.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return json.substring(open, i + 1);
            }
        }
        return null;
    }
}
