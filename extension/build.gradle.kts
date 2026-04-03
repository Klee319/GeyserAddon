plugins {
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":core"))
    compileOnly("org.geysermc.geyser:api:2.9.0-SNAPSHOT")
    // Gson is provided by Geyser runtime, but needed for compilation
    compileOnly("com.google.code.gson:gson:2.10.1")
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
    }
}

tasks.processResources {
    dependsOn(zipInvisibleGlowFrames)
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
