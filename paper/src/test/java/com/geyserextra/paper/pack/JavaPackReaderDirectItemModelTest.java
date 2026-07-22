package com.geyserextra.paper.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class JavaPackReaderDirectItemModelTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesNestedDirectItemModelAcrossNamespaces() throws Exception {
        Path itemDefinition = tempDir.resolve(
            "assets/trinityforge/items/gui/lightweapons.json");
        Path model = tempDir.resolve(
            "assets/minecraft/models/item/gui/skillicon_lightweapons.json");
        Path texture = tempDir.resolve(
            "assets/minecraft/textures/item/gui/skillicon_lightweapons.png");
        Files.createDirectories(itemDefinition.getParent());
        Files.createDirectories(model.getParent());
        Files.createDirectories(texture.getParent());
        Files.writeString(itemDefinition, """
            {"model":{"type":"minecraft:model",
            "model":"minecraft:item/gui/skillicon_lightweapons"}}
            """);
        Files.writeString(model, """
            {"parent":"item/generated","textures":{
            "layer0":"minecraft:item/gui/skillicon_lightweapons"}}
            """);
        Files.write(texture, new byte[]{1, 2, 3});

        JavaPackReader reader = new JavaPackReader(
            tempDir, "AUTO", Logger.getAnonymousLogger(), false);
        Map<String, JavaPackReader.JavaModelDefinition> direct =
            reader.scanDirectItemModels();

        assertThat(direct).containsKey("trinityforge:gui/lightweapons");
        assertThat(direct.get("trinityforge:gui/lightweapons").textureFile())
            .isEqualTo(texture);
    }
}
