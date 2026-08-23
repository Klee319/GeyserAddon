package com.geyserextra.paper.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the link between the build-time bake and the pack builder.
 *
 * <p>If the baked icons stop shipping — a renamed output directory, a resource root that no longer
 * reaches the jar — nothing fails loudly. Block-based items quietly go back to having no icon,
 * which quietly un-registers them, which quietly makes every recipe naming them undeliverable to
 * Bedrock. That chain is exactly how 203 of 360 corrected recipes went missing unnoticed, so the
 * presence of the resources is asserted rather than assumed.
 */
@DisplayName("BakedBlockIcons")
class BakedBlockIconsTest {

    @Test
    @DisplayName("ships an icon for the ordinary cube blocks custom items are built on")
    void shipsIconsForCommonBlocks() {
        for (String block : new String[] {"stone", "oak_log", "amethyst_block", "deepslate",
            "hay_block", "sand", "dirt", "netherrack"}) {
            assertThat(BakedBlockIcons.iconFor(block))
                .as("baked icon for %s", block)
                .isNotNull()
                .isNotEmpty();
        }
    }

    @Test
    @DisplayName("ships an icon for blocks that are not cubes, so their items still register")
    void shipsIconsForNonCubeBlocks() {
        // decorated_pot is the source jar family's base. Leaving it out is what kept
        // arspaper:source_jar undeliverable even though both of its items existed.
        assertThat(BakedBlockIcons.iconFor("decorated_pot")).isNotNull().isNotEmpty();
        assertThat(BakedBlockIcons.iconFor("lectern")).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("ships an icon for blocks bedrock-samples files under an older name")
    void shipsIconsForRenamedBlocks() {
        // These three are absent from blocks.json under the id Geyser uses, so the generator has an
        // alias table. Asserting the icon rather than the table keeps the guard honest if the
        // lookup is rewritten: iron_chain is what left fnis_peccati_profundi uncraftable.
        assertThat(BakedBlockIcons.iconFor("iron_chain")).isNotNull().isNotEmpty();
        assertThat(BakedBlockIcons.iconFor("grass_block")).isNotNull().isNotEmpty();
        assertThat(BakedBlockIcons.iconFor("sea_lantern")).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("returns null rather than throwing for anything not baked")
    void unknownBlocksReturnNull() {
        assertThat(BakedBlockIcons.iconFor("definitely_not_a_block")).isNull();
        assertThat(BakedBlockIcons.iconFor(null)).isNull();
        assertThat(BakedBlockIcons.iconFor("  ")).isNull();
    }

    @Test
    @DisplayName("the bytes really are a PNG")
    void iconsArePngs() {
        byte[] png = BakedBlockIcons.iconFor("stone");
        assertThat(png).isNotNull();
        // PNG signature. A truncated or misencoded resource would otherwise reach Bedrock as a
        // broken texture, which renders as the magenta placeholder for every affected item.
        assertThat(new byte[] {png[0], png[1], png[2], png[3]})
            .containsExactly((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }

    @Test
    @DisplayName("base item ids are matched without their namespace")
    void baseIdIsStripped() {
        // The texture-path maps are keyed on the bare Java id; passing "minecraft:oak_log" through
        // unstripped matches nothing and silently reinstates the no-icon behaviour.
        assertThat(AutoBedrockPackBuilder.bareBaseId("minecraft:oak_log")).isEqualTo("oak_log");
        assertThat(AutoBedrockPackBuilder.bareBaseId("OAK_LOG")).isEqualTo("oak_log");
        assertThat(AutoBedrockPackBuilder.bareBaseId(null)).isNull();
    }
}
