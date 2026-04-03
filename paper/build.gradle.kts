plugins {
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.geysermc.geyser:api:2.9.0-SNAPSHOT")
    compileOnly("org.geysermc.floodgate:api:2.2.4-SNAPSHOT")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    compileOnly("com.discordsrv:discordsrv:1.30.0")
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
