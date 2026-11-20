pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net")
        maven("https://maven.ornithemc.net/releases")
        maven("https://maven.ornithemc.net/snapshots")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.7"
    id("dev.kikugie.loom-back-compat") version "0.4.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val supportedVersions = listOf(
    "1.8.9", "1.21.1", "1.21.4", "1.21.5", "1.21.8", "1.21.10", "1.21.11", "26.1", "26.2", "26.3"
)
val vcs = "26.3"
val selectedVersions = providers.gradleProperty("discordpresence.versions").orNull
    ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
    ?.takeUnless { it.isEmpty() || it == listOf("all") }
    ?.also { requested ->
        val unknown = requested - supportedVersions
        require(unknown.isEmpty()) {
            "discordpresence.versions: unsupported version(s) $unknown - choose from $supportedVersions"
        }
    }
    ?: supportedVersions

stonecutter {
    create(rootProject) {
        versions(*supportedVersions.filter { it in selectedVersions || it == vcs }.toTypedArray())
        vcsVersion = vcs
    }
}

gradle.extra["discordpresence.selectedVersions"] = selectedVersions
rootProject.name = "DiscordPresence"
