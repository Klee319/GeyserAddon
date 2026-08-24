package com.geyserextra.core.config;

import com.geyserextra.core.util.JsonUtil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the default of {@code general.bedrockSkinFixEnabled} across the one path
 * that does not run a constructor: Gson deserialization.
 *
 * <p>This config is read by plain Gson, which allocates the object without
 * calling any constructor and leaves an absent field at its zero value. Had the
 * flag been a primitive {@code boolean}, every server whose {@code config.json}
 * predates the option — that is, every existing install — would have loaded it
 * as {@code false} and silently kept Bedrock players skinless, with a default of
 * {@code true} sitting right there in the source to mislead whoever went
 * looking.</p>
 */
@DisplayName("general.bedrockSkinFixEnabled default")
class GeneralConfigBedrockSkinFixTest {

    @Test
    @DisplayName("a config.json written before the option existed still enables it")
    void absentFieldMeansEnabled() {
        String legacy = "{\"general\":{\"enabled\":true,\"debugMode\":false,"
            + "\"workerThreads\":2,\"tooltipDefaultEnabled\":false,"
            + "\"sneakDropOffhandSwapEnabled\":true}}";

        GeyserExtraConfig config = JsonUtil.fromJson(legacy, GeyserExtraConfig.class);

        assertThat(config.general().bedrockSkinFixEnabled()).isTrue();
    }

    @Test
    @DisplayName("an explicit false is honoured")
    void explicitFalseDisablesIt() {
        String json = "{\"general\":{\"enabled\":true,\"bedrockSkinFixEnabled\":false}}";

        GeyserExtraConfig config = JsonUtil.fromJson(json, GeyserExtraConfig.class);

        assertThat(config.general().bedrockSkinFixEnabled()).isFalse();
    }

    @Test
    @DisplayName("an explicit true is honoured")
    void explicitTrueEnablesIt() {
        String json = "{\"general\":{\"enabled\":true,\"bedrockSkinFixEnabled\":true}}";

        GeyserExtraConfig config = JsonUtil.fromJson(json, GeyserExtraConfig.class);

        assertThat(config.general().bedrockSkinFixEnabled()).isTrue();
    }

    @Test
    @DisplayName("skinFixOnlyMode stays off for a config that never heard of it")
    void skinFixOnlyModeDefaultsOff() {
        // The opposite polarity to the flag above, and deliberately so: an
        // existing backend must keep the whole plugin. Gson leaving an absent
        // primitive at false is the correct default here, not a hazard.
        String legacy = "{\"general\":{\"enabled\":true}}";

        assertThat(JsonUtil.fromJson(legacy, GeyserExtraConfig.class)
            .general().skinFixOnlyMode()).isFalse();
        assertThat(new GeyserExtraConfig().general().skinFixOnlyMode()).isFalse();
    }

    @Test
    @DisplayName("skinFixOnlyMode is honoured when set")
    void skinFixOnlyModeIsHonoured() {
        String json = "{\"general\":{\"enabled\":true,\"skinFixOnlyMode\":true}}";

        GeyserExtraConfig config = JsonUtil.fromJson(json, GeyserExtraConfig.class);

        assertThat(config.general().skinFixOnlyMode()).isTrue();
        // The two flags are independent: skin-only mode still needs the repair
        // itself switched on, and it is, by default.
        assertThat(config.general().bedrockSkinFixEnabled()).isTrue();
    }

    @Test
    @DisplayName("the durability bar fix is on for a config that predates it")
    void durabilityBarFixDefaultsOn() {
        // Same hazard as the skin fix: a primitive here would have silently
        // switched the rescale off on every existing install.
        assertThat(JsonUtil.fromJson("{\"general\":{\"enabled\":true}}", GeyserExtraConfig.class)
            .general().bedrockDurabilityBarFixEnabled()).isTrue();
        assertThat(JsonUtil.fromJson(
            "{\"general\":{\"bedrockDurabilityBarFixEnabled\":false}}", GeyserExtraConfig.class)
            .general().bedrockDurabilityBarFixEnabled()).isFalse();
    }

    @Test
    @DisplayName("the smithing CMD strip is on for a config that predates it")
    void smithingCmdStripDefaultsOn() {
        // Third flag with the same hazard. Left primitive, every server whose
        // config.json predates the netherite-upgrade fix would have loaded it as
        // false and kept the upgrade broken, with `= true` in the constructor
        // sitting there to mislead whoever went looking.
        assertThat(JsonUtil.fromJson("{\"general\":{\"enabled\":true}}", GeyserExtraConfig.class)
            .general().bedrockSmithingCmdStripEnabled()).isTrue();
        assertThat(JsonUtil.fromJson(
            "{\"general\":{\"bedrockSmithingCmdStripEnabled\":false}}", GeyserExtraConfig.class)
            .general().bedrockSmithingCmdStripEnabled()).isFalse();
        assertThat(new GeyserExtraConfig().general().bedrockSmithingCmdStripEnabled()).isTrue();
    }

    @Test
    @DisplayName("the nether-sky repair is on for a config that predates it")
    void netherSkyFixDefaultsOn() {
        // Fourth flag with the same hazard: a primitive would have loaded as
        // false on every existing install and left Bedrock players' skies
        // stuck with no automatic repair and /fixsky answering "disabled".
        assertThat(JsonUtil.fromJson("{\"general\":{\"enabled\":true}}", GeyserExtraConfig.class)
            .general().bedrockNetherSkyFixEnabled()).isTrue();
        assertThat(JsonUtil.fromJson(
            "{\"general\":{\"bedrockNetherSkyFixEnabled\":false}}", GeyserExtraConfig.class)
            .general().bedrockNetherSkyFixEnabled()).isFalse();
        assertThat(new GeyserExtraConfig().general().bedrockNetherSkyFixEnabled()).isTrue();
    }

    @Test
    @DisplayName("the constructed default is enabled and survives a save/load round trip")
    void defaultRoundTripsThroughJson() {
        // The written config must state the value explicitly, otherwise the
        // operator has no way to discover the option exists.
        String json = JsonUtil.toPrettyJson(new GeyserExtraConfig());

        assertThat(json).contains("bedrockSkinFixEnabled");
        assertThat(JsonUtil.fromJson(json, GeyserExtraConfig.class)
            .general().bedrockSkinFixEnabled()).isTrue();
    }
}
