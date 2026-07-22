package com.geyserextra.paper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PluginLoadOrderTest {

    @Test
    void loadsBeforeGeyserWithoutAConflictingSoftDependency() throws IOException {
        String pluginYaml;
        try (InputStream input = getClass().getResourceAsStream("/plugin.yml")) {
            assertThat(input).isNotNull();
            pluginYaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(pluginYaml).contains("loadbefore: [Geyser-Spigot]");
        assertThat(pluginYaml.lines()
            .filter(line -> line.stripLeading().startsWith("softdepend:")))
            .noneMatch(line -> line.contains("Geyser-Spigot"));
    }
}
