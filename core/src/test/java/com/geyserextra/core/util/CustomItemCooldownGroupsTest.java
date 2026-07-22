package com.geyserextra.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CustomItemCooldownGroupsTest {

    @Test
    void differentMappingsOnTheSameMaterialReceiveDifferentGroups() {
        assertThat(CustomItemCooldownGroups.forMapping("golden_warhammer"))
            .isEqualTo("geyserextra:golden_warhammer");
        assertThat(CustomItemCooldownGroups.forMapping("golden_great_axe"))
            .isEqualTo("geyserextra:golden_great_axe");
    }

    @Test
    void normalizesNamesIntoValidIdentifierPathsDeterministically() {
        assertThat(CustomItemCooldownGroups.forMapping("ValhallaMMO:Golden Warhammer"))
            .isEqualTo("geyserextra:valhallammo_golden_warhammer");
    }

    @Test
    void returnsNullForMissingMappingNames() {
        assertThat(CustomItemCooldownGroups.forMapping(null)).isNull();
        assertThat(CustomItemCooldownGroups.forMapping("  ")).isNull();
    }

    @Test
    void recognizesOnlyItsOwnSyntheticGroups() {
        assertThat(CustomItemCooldownGroups.isSynthetic("geyserextra:golden_warhammer")).isTrue();
        assertThat(CustomItemCooldownGroups.isSynthetic("minecraft:golden_sword")).isFalse();
        assertThat(CustomItemCooldownGroups.isSynthetic(null)).isFalse();
    }
}
