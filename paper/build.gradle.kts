plugins {
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Why 2.10.0-SNAPSHOT: matches the extension module so both halves of the plugin
    // compile against the same Geyser API surface. The v2 custom item types
    // (CustomItemDefinition + range_dispatch predicates) are @since 2.9.3, so the
    // runtime Geyser must also be 2.9.3+ regardless of which SNAPSHOT we compile against.
    compileOnly("org.geysermc.geyser:api:2.10.0-SNAPSHOT")
    compileOnly("org.geysermc.floodgate:api:2.2.4-SNAPSHOT")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    compileOnly("com.discordsrv:discordsrv:1.30.0")
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
