import net.ornithemc.ploceus.api.PloceusGradleExtensionApi

plugins {
    id("dev.kikugie.loom-back-compat")
    id("net.fabricmc.fabric-loom-remap") version "1.19.4" apply false
    id("ploceus") version "1.17-SNAPSHOT" apply false
}

// 1.8.9 is the existing Ornithe build. All later supported OneConfig targets use Fabric.
val isLegacy = sc.current.version == "1.8.9"
val ploceus = if (isLegacy) {
    pluginManager.apply("net.fabricmc.fabric-loom-remap")
    pluginManager.apply("ploceus")
    extensions.getByType<PloceusGradleExtensionApi>().apply {
        setIntermediaryGeneration(2)
    }
} else null

val modId: String = sc.properties["mod.id"]
val modName: String = sc.properties["mod.name"]
val modVersion: String = sc.properties["mod.version"]
val modDescription: String = sc.properties["mod.description"]
val baseGroup: String = sc.properties["mod.group"]
val mcVersion: String = sc.current.version
val mcCompat: String = sc.properties["mod.mc_compat"]
val loaderVersion: String = sc.properties["deps.fabric_loader"]
val oneconfigVersion: String = sc.properties["deps.oneconfig"]
val loader = if (isLegacy) "ornithe" else "fabric"
val requiredJava: JavaVersion = when {
    // OneConfig's current Ornithe artifact is compiled for Java 25.
    isLegacy -> JavaVersion.VERSION_25
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    else -> JavaVersion.VERSION_21
}

group = baseGroup
version = "$modVersion+$mcVersion"
base.archivesName.set("$modId-$modVersion-$loader-$mcVersion")

repositories {
    mavenCentral()
    google()
    maven("https://repo.polyfrost.org/releases")
    maven("https://maven.cloverclient.com/releases")
    maven("https://repo.papermc.io/repository/maven-public/")
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") }
        filter { includeGroup("maven.modrinth") }
    }
    exclusiveContent {
        forRepository { maven("https://maven.terraformersmc.com/") }
        filter { includeGroup("com.terraformersmc") }
    }
    exclusiveContent {
        forRepository { maven("https://maven.deftu.dev/releases") }
        filter { includeGroup("dev.deftu") }
    }
}

sourceSets.main {
    val platform = if (isLegacy) "legacy" else "modern"
    java.srcDir(rootProject.file("src/common/java"))
    if (isLegacy) {
        java.setSrcDirs(listOf(rootProject.file("src/common/java"), rootProject.file("src/legacy/java")))
    }
    resources.setSrcDirs(listOf(rootProject.file("src/$platform/resources")))
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    if (isLegacy) {
        mappings(ploceus!!.mcpMappings("stable", mcVersion, sc.properties["deps.mcp_mappings"]))
        ploceus.dependOsl(sc.properties["deps.osl"])
    } else {
        loomx.applyMojangMappings()
    }

    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    if (!isLegacy) {
        val fabricApiVersion: String = sc.properties["deps.fabric_api"]
        modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
        val lwjglVersion = if (sc.current.parsed >= "26.1") "3.4.1" else "3.3.3"
        compileOnly("org.lwjgl:lwjgl-glfw:$lwjglVersion")
    }

    modImplementation("org.polyfrost.oneconfig:$mcVersion-$loader:$oneconfigVersion")
    if (isLegacy) {
        compileOnly("org.polyfrost.oneconfig:config:$oneconfigVersion")
        compileOnly("org.polyfrost.oneconfig:config-impl:$oneconfigVersion")
        val hypixelApiVersion: String = sc.properties["deps.hypixel_api"]
        modCompileOnly("org.polyfrost:mod-api-fabric:$hypixelApiVersion")
    } else {
        for (module in arrayOf("commands", "config", "config-impl", "poly-compose", "utils")) {
            implementation("org.polyfrost.oneconfig:$module:$oneconfigVersion")
        }
    }
}

loom {
    fabricModJsonPath = rootProject.file("src/${if (isLegacy) "legacy" else "modern"}/resources/fabric.mod.json")
    runConfigs.all { runDirectory = rootProject.file("run") }
    runConfigs.remove(runConfigs["server"])
}

tasks {
    processResources {
        val props = mapOf(
            "mod_id" to modId,
            "mod_name" to modName,
            "mod_version" to modVersion,
            "mod_description" to modDescription,
            "java_version" to requiredJava.majorVersion,
            "minecraft_version" to mcVersion,
            "minecraft_version_range" to mcCompat,
            "fabric_loader_version" to loaderVersion,
            "oneconfig_version" to oneconfigVersion
        )
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
    }

    withType<JavaCompile>().configureEach { options.release = requiredJava.majorVersion.toInt() }
    withType<AbstractArchiveTask>().configureEach { archiveVersion = "" }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds the mod and copies it to build/libs/$modVersion/."
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
    }
}

java {
    withSourcesJar()
    sourceCompatibility = requiredJava
    targetCompatibility = requiredJava
    toolchain.languageVersion.set(JavaLanguageVersion.of(requiredJava.majorVersion))
}
