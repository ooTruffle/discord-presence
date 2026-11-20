val modName = project.property("mod_name") as String
val modId = project.property("mod_id") as String
val modVersion = project.property("mod_version") as String
val modDescription = project.property("mod_description") as String
val baseGroup = project.property("base_group") as String

val javaVersion = project.property("java_version") as String
val minecraftVersion = project.property("minecraft_version") as String
val fabricLoaderVersion = project.property("fabric_loader_version") as String
val oslVersion = project.property("osl_version") as String

val oneconfigVersion = project.property("oneconfig_version") as String
val hypixelModApiVersion = project.property("hypixel_mod_api_version") as String

plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.19-SNAPSHOT"
    id("ploceus") version "1.19-SNAPSHOT"
}

group = baseGroup
version = modVersion

repositories {
    mavenCentral()
    google()

    // OneConfig's Compose/androidx dependency tree lives here.
    maven("https://maven.cloverclient.com/releases")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.polyfrost.org/releases")
    maven("https://repo.polyfrost.org/snapshots")
}

ploceus {
    setIntermediaryGeneration(2)
}

base {
    archivesName.set("$modId-$modVersion-ornithe-$minecraftVersion")
}

loom {
    runConfigs.remove(runConfigs["server"])
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(ploceus.mcpMappings("stable", "1.8.9", "22"))
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    ploceus.dependOsl(oslVersion)

    // Both optional at runtime: only touched when the mod is installed (see init()).
    modCompileOnly("org.polyfrost.oneconfig:$minecraftVersion-ornithe:$oneconfigVersion")
    compileOnly("org.polyfrost.oneconfig:config:$oneconfigVersion")
    compileOnly("org.polyfrost.oneconfig:config-impl:$oneconfigVersion")
    modCompileOnly("org.polyfrost:mod-api-fabric:$hypixelModApiVersion+build.2+mc$minecraftVersion")
}

tasks.processResources {
    val props = mapOf(
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_version" to modVersion,
        "mod_description" to modDescription,
        "java_version" to javaVersion,
        "minecraft_version" to minecraftVersion,
        "fabric_loader_version" to fabricLoaderVersion
    )

    inputs.properties(props)

    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion.toInt()
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.toVersion(javaVersion)
    targetCompatibility = JavaVersion.toVersion(javaVersion)

    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

tasks.jar {
    inputs.property("archivesName", base.archivesName)
}

// The archives name already carries the version: discordpresence-1.1.2-ornithe-1.8.9.jar
tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set("")
}
