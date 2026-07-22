plugins {
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":core"))
    // Why 2.10.0-SNAPSHOT: the v2 custom item API (CustomItemDefinition,
    // ItemRangeDispatchPredicate, CustomItemBedrockOptions) is @since 2.9.3.
    // Runtime Geyser must be at least that version too.
    compileOnly("org.geysermc.geyser:api:2.10.0-SNAPSHOT")
    // Gson is provided by Geyser runtime, but needed for compilation
    compileOnly("com.google.code.gson:gson:2.10.1")
}

// Surface every deprecated / marked-for-removal API call at build time so
// Geyser/Adventure deprecation sweeps cannot accumulate silently. See the
// matching note in paper/build.gradle.kts.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
}

// Zip the invisible glow frames resource pack and include in JAR resources
// Why exclude *.py: Build scripts (create_images.py) are development tools,
// not part of the Bedrock resource pack. Including them may cause pack parsing issues.
val zipInvisibleGlowFrames by tasks.registering(Zip::class) {
    from(rootProject.file("resourcepack/invisible_glow_frames"))
    exclude("**/*.py")
    archiveFileName.set("invisible_glow_frames.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated-resources/packs"))
}

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated-resources"))
        // Bake the Paper module's generated bedrock/vanilla_texture_paths.json
        // into this jar too: on a fresh install the extension registers items
        // before Paper writes the block_icon_bases.json sidecar, and without
        // this classpath fallback block-base items would miss their 3D icon
        // until the second boot.
        resources.srcDir(project(":paper").layout.buildDirectory.dir("generated/resources"))
    }
}

tasks.processResources {
    dependsOn(zipInvisibleGlowFrames)
    dependsOn(":paper:generateVanillaTexturePaths")
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Relocate core classes to avoid classpath collision with Paper plugin's bundled copy
    relocate("com.geyserextra.core", "com.geyserextra.extension.shaded.core")
}

// Make build task depend on shadowJar instead of jar
tasks.build {
    dependsOn(tasks.shadowJar)
}

// Disable the standard jar task to avoid confusion
tasks.jar {
    enabled = false
}
