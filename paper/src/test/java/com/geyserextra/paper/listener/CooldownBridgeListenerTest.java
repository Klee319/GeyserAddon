package com.geyserextra.paper.listener;

import com.geyserextra.core.api.CustomItemMapping;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CooldownBridgeListenerTest {

    @Test
    void selectsHeldMappingWhoseBaseMatchesTheServerCooldownGroup() {
        CustomItemMapping axe = mapping("golden_great_axe", "minecraft:golden_sword", 1981827);
        CustomItemMapping pearl = mapping("blink_pearl", "minecraft:ender_pearl", 9);

        Optional<CustomItemMapping> selected = CooldownMappingSelector.select(
            "minecraft:ender_pearl", Optional.of(axe), Optional.of(pearl), Optional.empty());

        assertThat(selected).contains(pearl);
    }

    @Test
    void fallsBackToTheMostRecentlyUsedCustomItemAfterItsStackWasConsumed() {
        CustomItemMapping pearl = mapping("blink_pearl", "minecraft:ender_pearl", 9);

        Optional<CustomItemMapping> selected = CooldownMappingSelector.select(
            "minecraft:ender_pearl", Optional.empty(), Optional.empty(), Optional.of(pearl));

        assertThat(selected).contains(pearl);
    }

    @Test
    void doesNotMirrorAnUnrelatedMaterialCooldown() {
        CustomItemMapping axe = mapping("golden_great_axe", "minecraft:golden_sword", 1981827);

        Optional<CustomItemMapping> selected = CooldownMappingSelector.select(
            "minecraft:ender_pearl", Optional.of(axe), Optional.empty(), Optional.empty());

        assertThat(selected).isEmpty();
    }

    @Test
    void recentlyUsedOffhandItemWinsOverSameMaterialMainHandItem() {
        CustomItemMapping main = mapping("golden_warhammer", "minecraft:golden_sword", 1981825);
        CustomItemMapping usedOffhand =
            mapping("golden_great_axe", "minecraft:golden_sword", 1981827);

        Optional<CustomItemMapping> selected = CooldownMappingSelector.select(
            "minecraft:golden_sword",
            Optional.of(main),
            Optional.of(usedOffhand),
            Optional.of(usedOffhand));

        assertThat(selected).contains(usedOffhand);
    }

    @Test
    void selectsRecentMappingForCustomPluginCooldownGroup() {
        CustomItemMapping axe = mapping("golden_great_axe", "minecraft:golden_sword", 1981827);
        CustomItemMapping other = mapping("golden_warhammer", "minecraft:golden_sword", 1981825);

        Optional<CustomItemMapping> selected = CooldownMappingSelector.select(
            "valhallammo:dash", Optional.of(other), Optional.empty(), Optional.of(axe));

        assertThat(selected).contains(axe);
    }

    @Test
    void selectsMainHandForCustomPluginCooldownGroupWhenNoRecent() {
        CustomItemMapping axe = mapping("golden_great_axe", "minecraft:golden_sword", 1981827);

        Optional<CustomItemMapping> selected = CooldownMappingSelector.select(
            "valhallammo:dash", Optional.of(axe), Optional.empty(), Optional.empty());

        assertThat(selected).contains(axe);
    }

    @Test
    void doesNotSelectMappingForCustomPluginGroupWithNoHeldOrRecentItem() {
        Optional<CustomItemMapping> selected = CooldownMappingSelector.select(
            "valhallammo:dash", Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(selected).isEmpty();
    }

    @Test
    void normalizesBareMaterialKeyToMatchMappingBaseItem() {
        CustomItemMapping sword = mapping("custom_sword", "minecraft:golden_sword", 100);

        Optional<CustomItemMapping> selected = CooldownMappingSelector.select(
            "golden_sword", Optional.of(sword), Optional.empty(), Optional.empty());

        assertThat(selected).contains(sword);
    }

    private static CustomItemMapping mapping(String name, String base, int cmd) {
        return new CustomItemMapping(name, base, cmd);
    }
}
