plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta12" apply false
}

allprojects {
    group = property("group") as String
    version = property("version") as String

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.opencollab.dev/main/")
        maven("https://repo.opencollab.dev/maven-snapshots/")
        maven("https://repo.dmulloy2.net/repository/public/")
        maven("https://nexus.scarsz.me/content/groups/public/")
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}
