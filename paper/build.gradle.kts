import java.net.URI

plugins {
    id("com.gradleup.shadow")
}

val minecraftDataVersion = "1.21.11"
// Bedrock protocol version dir in minecraft-data carrying blocksJ2B.json
// (Java block id -> Bedrock block id renames like cobweb -> web).
val minecraftDataBedrockVersion = "1.21.111"
val bedrockSamplesBase =
    "https://raw.githubusercontent.com/Mojang/bedrock-samples/main/resource_pack"
val minecraftDataBase =
    "https://raw.githubusercontent.com/PrismarineJS/minecraft-data/master/data/pc/$minecraftDataVersion"
val minecraftDataBedrockBase =
    "https://raw.githubusercontent.com/PrismarineJS/minecraft-data/master/data/bedrock/$minecraftDataBedrockVersion"
val bedrockSamplesCacheDir = layout.buildDirectory.dir("bedrock-samples-cache")
val minecraftDataCacheDir = layout.buildDirectory.dir("minecraft-data-cache/$minecraftDataVersion")
val generatedResourcesDir = layout.buildDirectory.dir("generated/resources")
val generatedVanillaTexturePaths = generatedResourcesDir.map { it.file("bedrock/vanilla_texture_paths.json") }

sourceSets {
    create("generate") {
        java.srcDir("src/generate/java")
    }
}

dependencies {
    implementation(project(":core"))
    "generateImplementation"("com.google.code.gson:gson:2.10.1")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Why 2.10.0-SNAPSHOT: matches the extension module so both halves of the plugin
    // compile against the same Geyser API surface. The v2 custom item types
    // (CustomItemDefinition + range_dispatch predicates) are @since 2.9.3, so the
    // runtime Geyser must also be 2.9.3+ regardless of which SNAPSHOT we compile against.
    compileOnly("org.geysermc.geyser:api:2.10.0-SNAPSHOT")
    compileOnly("org.geysermc.floodgate:api:2.2.4-SNAPSHOT")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    compileOnly("com.discordsrv:discordsrv:1.30.0")

    // Unit-test dependencies (JUnit 5 + AssertJ). Used by the
    // BedrockGeometryConverter tests that lock in the per-face UV math so
    // a future refactor cannot silently regress rotation 0/180 output or
    // the 90/270 skip semantics that Codex round 2/3 review hardened.
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    // Paper API on the test classpath as well. Without it, a test that so much
    // as names a class implementing Listener cannot compile, and with only
    // testCompileOnly it compiles but dies at runtime with
    // NoClassDefFoundError: org/bukkit/event/Listener when the class is
    // loaded. Between them those two failures had pushed all listener logic
    // out of reach of tests. This puts the API on both classpaths; it does NOT
    // add a Bukkit harness, so tests still must not boot a server or touch
    // registry-backed types like ItemStack.
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks.test {
    useJUnitPlatform()
}

val refreshBedrockSamples = providers.gradleProperty("refreshBedrockSamples")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val refreshMinecraftData = providers.gradleProperty("refreshMinecraftData")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

val downloadBedrockSamples by tasks.registering {
    val itemTexture = bedrockSamplesCacheDir.map { it.file("item_texture.json") }
    val terrainTexture = bedrockSamplesCacheDir.map { it.file("terrain_texture.json") }
    val manifest = bedrockSamplesCacheDir.map { it.file("manifest.json") }
    // Bedrock's own vanilla item names. A registered custom item is named only
    // from the generated pack's texts/*.lang, so without this the client's
    // localised name ("木の剣") is replaced by a prettified English guess
    // ("Wooden Sword"). Taking the strings from Mojang's own pack means the
    // fallback name is exactly what the player would have seen anyway.
    val jaLang = bedrockSamplesCacheDir.map { it.file("ja_JP.lang") }
    outputs.files(itemTexture, terrainTexture, manifest, jaLang)

    doLast {
        val cache = bedrockSamplesCacheDir.get().asFile
        val forceRefresh = refreshBedrockSamples.get()
        cache.mkdirs()
        listOf(
            "textures/item_texture.json" to itemTexture.get().asFile,
            "textures/terrain_texture.json" to terrainTexture.get().asFile,
            "manifest.json" to manifest.get().asFile,
            "texts/ja_JP.lang" to jaLang.get().asFile
        ).forEach { (remotePath, localFile) ->
            if (forceRefresh || !localFile.exists()) {
                URI("$bedrockSamplesBase/$remotePath").toURL().openStream().use { input ->
                    localFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}

val downloadMinecraftData by tasks.registering {
    val itemsJson = minecraftDataCacheDir.map { it.file("items.json") }
    val blocksJson = minecraftDataCacheDir.map { it.file("blocks.json") }
    val blocksJ2BJson = minecraftDataCacheDir.map { it.file("blocksJ2B.json") }
    outputs.files(itemsJson, blocksJson, blocksJ2BJson)

    doLast {
        val cache = minecraftDataCacheDir.get().asFile
        val forceRefresh = refreshMinecraftData.get()
        cache.mkdirs()
        listOf(
            "$minecraftDataBase/items.json" to itemsJson.get().asFile,
            "$minecraftDataBase/blocks.json" to blocksJson.get().asFile,
            "$minecraftDataBedrockBase/blocksJ2B.json" to blocksJ2BJson.get().asFile
        ).forEach { (remoteUrl, localFile) ->
            if (forceRefresh || !localFile.exists()) {
                URI(remoteUrl).toURL().openStream().use { input ->
                    localFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}

val generateVanillaTexturePaths by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generate bedrock/vanilla_texture_paths.json from Mojang bedrock-samples"
    dependsOn("compileGenerateJava", downloadBedrockSamples, downloadMinecraftData)
    classpath = sourceSets["generate"].runtimeClasspath
    mainClass.set("com.geyserextra.paper.pack.generate.BedrockVanillaTextureMapGenerator")
    val itemTexture = bedrockSamplesCacheDir.map { it.file("item_texture.json") }
    val terrainTexture = bedrockSamplesCacheDir.map { it.file("terrain_texture.json") }
    val manifest = bedrockSamplesCacheDir.map { it.file("manifest.json") }
    val itemsJson = minecraftDataCacheDir.map { it.file("items.json") }
    val blocksJson = minecraftDataCacheDir.map { it.file("blocks.json") }
    val blocksJ2BJson = minecraftDataCacheDir.map { it.file("blocksJ2B.json") }
    val jaLang = bedrockSamplesCacheDir.map { it.file("ja_JP.lang") }
    args(
        itemTexture.get().asFile,
        terrainTexture.get().asFile,
        manifest.get().asFile,
        itemsJson.get().asFile,
        blocksJson.get().asFile,
        blocksJ2BJson.get().asFile,
        generatedVanillaTexturePaths.get().asFile,
        jaLang.get().asFile
    )
    inputs.files(
        itemTexture,
        terrainTexture,
        manifest,
        itemsJson,
        blocksJson,
        blocksJ2BJson,
        jaLang,
        sourceSets.named("generate").map { it.allJava }
    )
    inputs.property("minecraftDataVersion", minecraftDataVersion)
    inputs.property("minecraftDataBedrockVersion", minecraftDataBedrockVersion)
    outputs.file(generatedVanillaTexturePaths)
}

sourceSets.named("main") {
    resources {
        srcDir(generatedResourcesDir)
    }
}

tasks.named("compileJava") {
    dependsOn(generateVanillaTexturePaths)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateVanillaTexturePaths)
}

// Surface every deprecated / marked-for-removal API call at build time so
// the same Paper / Adventure / Geyser deprecation sweep that flagged
// AnvilInventory.setRepairCost and Enchantment.translationKey() does not
// silently re-accumulate. Without these flags, javac collapses the list to
// a "...some files use deprecated APIs" note and only the first couple of
// warnings ever surface, hiding the rest behind whichever pair javac picked.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("geyserExtra")
}

// Make build task depend on shadowJar instead of jar
tasks.build {
    dependsOn(tasks.shadowJar)
}

// Disable the standard jar task to avoid confusion
tasks.jar {
    enabled = false
}
