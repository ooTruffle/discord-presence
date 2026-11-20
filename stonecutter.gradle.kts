plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.3" /* [SC] DO NOT EDIT */

@Suppress("UNCHECKED_CAST")
val selectedVersions = gradle.extra["discordpresence.selectedVersions"] as List<String>

tasks.register("buildAll") {
    group = "build"
    description = "Builds all selected OneConfig-supported Minecraft versions (discordpresence.versions)."
    dependsOn(selectedVersions.map { ":$it:buildAndCollect" })
}
