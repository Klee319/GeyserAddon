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
