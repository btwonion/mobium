@file:Suppress("SpellCheckingInspection", "UnstableApiUsage", "RedundantNullableReturnType")

import me.modmuss50.mpp.platforms.modrinth.ModrinthEnvironment
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.api.fabricapi.FabricApiExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinx.serialization)

    alias(libs.plugins.moddev) apply false
    alias(libs.plugins.modstitch)

    alias(libs.plugins.mod.publish)

    `maven-publish`
}

val isFabric = modstitch.isLoom
val loader = if (isFabric) "fabric" else "neoforge"

val beta: Int = property("mod.beta").toString().toInt()
val majorVersion: String = property("mod.major-version").toString()
val mcVersion = property("vers.mcVersion").toString()
version = "$majorVersion${if (beta != 0) "-beta$beta" else ""}-$mcVersion+$loader" // Pattern is '1.0.0-beta1-1.20.6-pre.2+fabric'

val flk: String = "${libs.versions.fabric.language.kotlin.orNull}${libs.versions.kotlin.orNull}"
val fabricLoader = libs.versions.fabric.loader.get()
modstitch {
    minecraftVersion = mcVersion

    metadata {
        fun prop(property: String, block: (String) -> Unit) {
            prop(property, ifNull = {""}) { block(it) }
        }

        prop("mod.id") { modId = it }
        prop("mod.name") { modName = it }
        prop("mod.description") { modDescription = it }
        prop("mod.group") { modGroup = it }

        modVersion = project.version.toString()
        modLicense = "GNU Lesser General Public License v3.0"
        modAuthor = "btwonion"

        prop("mod.repo") { replacementProperties.put("repo", it) }
        prop("mod.icon") { replacementProperties.put("icon", it) }
        prop("vers.mcVersionRange") { replacementProperties.put("mc", it) }
        replacementProperties.put("fabric_loader", fabricLoader)
        replacementProperties.put("flk", flk)
        prop("vers.deps.fapi") { replacementProperties.put("fapi", it) }
        prop("vers.deps.modMenu") { replacementProperties.put("modmenu", it) }
    }

    loom {
        fabricLoaderVersion = fabricLoader

        configureLoom {
            runConfigs.all {
                ideConfigGenerated(false)
            }
        }
    }

    moddevgradle {
        prop("vers.deps.fml") { neoForgeVersion = it }

        configureNeoForge {
            runs {
                register("mainClient") {
                    client()
                    sourceSet = sourceSets.main.get()
                    gameDirectory = layout.projectDirectory.dir("../../run")
                    environment("WAYLAND_DISPLAY", "")
                    environment("XDG_SESSION_TYPE", "x11")
                }
            }

            mods {
                register("main") {
                    sourceSet(sourceSets.main.get())
                }
            }
        }
    }

    mixin {
        addMixinsToModManifest = true

        configs.register("mobium")
    }
}

// Fabric client gametests (scene smoke tests and screenshots) live in src/gametest and run as part of `check`.
if (isFabric) {
    extensions.configure<FabricApiExtension>("fabricApi") {
        configureTests {
            createSourceSet = true
            modId = "mobium-test"
            eula = true
            enableGameTests = false
        }
    }

    // BenchmarkScenesGameTest installs the benchmark datapack straight from the repository. runBenchWorld runs the
    // gametest client once more to save a ready benchmark world (BenchWorldGameTest) instead of testing.
    extensions.configure<LoomGradleExtensionAPI>("loom") {
        runConfigs.named("clientGameTest") {
            property("mobium.benchmarkPack", rootDir.resolve("benchmark/mobium-bench").absolutePath)
        }
        runConfigs.create("benchWorld") {
            inherit(runConfigs.getByName("clientGameTest"))
            runDir("build/run/benchWorld")
            property("mobium.benchWorld", layout.buildDirectory.dir("bench-world").get().asFile.absolutePath)
        }
    }

    tasks.named("check") {
        dependsOn("runClientGameTest")
    }

    // Mods for the four benchmark setups of docs/benchmarks.md, for a normal launcher instance
    // (benchmark/prism_instances.py installs them into Prism Launcher).
    val benchKitBase by configurations.creating { isTransitive = false }
    val benchKitIris by configurations.creating { isTransitive = false }
    val benchKitEmf by configurations.creating { isTransitive = false }
    dependencies {
        prop("vers.deps.fapi") { benchKitBase("net.fabricmc.fabric-api:fabric-api:$it") }
        benchKitBase("net.fabricmc:fabric-language-kotlin:$flk")
        prop("vers.deps.sodium") { benchKitBase("maven.modrinth:sodium:$it") }
        prop("vers.deps.iris") { benchKitIris("maven.modrinth:iris:$it") }
        prop("vers.deps.emf") { benchKitEmf("maven.modrinth:entity-model-features:$it") }
        prop("vers.deps.etf") { benchKitEmf("maven.modrinth:entitytexturefeatures:$it") }
    }
    tasks.register<Sync>("benchKit") {
        group = "mobium"
        description = "Collects the mods of the four benchmark setups and the benchmark datapack in build/bench-kit"
        val mobiumJar = modstitch.finalJarTask.flatMap { it.archiveFile }
        val setups = mapOf(
            "A-sodium" to listOf(),
            "B-iris" to listOf(benchKitIris),
            "C-emf-etf" to listOf(benchKitEmf),
            "D-all" to listOf(benchKitIris, benchKitEmf)
        )
        for ((setup, extras) in setups) {
            into("$setup/mods") {
                from(benchKitBase)
                extras.forEach { from(it) }
                from(mobiumJar)
            }
        }
        into("datapacks/mobium-bench") { from(rootDir.resolve("benchmark/mobium-bench")) }
        into(layout.buildDirectory.dir("bench-kit"))
    }
}

base {
    archivesName.set(rootProject.name)
}

stonecutter {
    listOf("neoforge", "fabric").map { it to (loader == it) }
        .forEach { (name, isCurrent) -> constants[name] = isCurrent }
}

repositories {
    mavenCentral()
    maven("https://maven.terraformersmc.com")
    maven("https://repo.nyon.dev/releases")
    maven("https://maven.neoforged.net/releases/")
    maven("https://maven.caffeinemc.net/releases") {
        content {
            includeGroup("net.caffeinemc")
        }
    }
    maven("https://api.modrinth.com/maven") {
        content {
            includeGroup("maven.modrinth")
        }
    }
}

val fabricLanguageKotlin: String = "${libs.versions.fabric.language.kotlin.orNull}${libs.versions.kotlin.orNull}"
val kotlinLangForge: String = "${libs.versions.klf.orNull}-k${libs.versions.kotlin.orNull}"
dependencies {
    fun modDependency(
        artifact: String,
        compileOnly: Boolean = false,
        api: Boolean = false
    ) {
        val configuration = if (api) {
            if (compileOnly) "modstitchModCompileOnly" else "modstitchModApi"
        } else {
            if (compileOnly) "modstitchModCompileOnlyApi" else "modstitchModImplementation"
        }

        configuration(artifact)
    }

    fun propModDependency(
        id: String,
        artifactGetter: (String) -> String,
        compileOnly: Boolean = false,
        api: Boolean = false
    ) {
        prop("vers.deps.$id") { modVersion ->
            modDependency(
                artifactGetter(modVersion),
                compileOnly,
                api
            )
        }
    }

    // Mobium must load without these; they are compiled against and put on the dev runtime classpath only.
    fun compatDependency(id: String, artifactGetter: (String) -> String) {
        prop("vers.deps.$id") { modVersion ->
            "modstitchModCompileOnly"(artifactGetter(modVersion))
            "modstitchModLocalRuntime"(artifactGetter(modVersion))
        }
    }

    if (isFabric) {
        propModDependency("fapi", { "net.fabricmc.fabric-api:fabric-api:$it" }, api = true)
        modDependency("net.fabricmc:fabric-language-kotlin:$fabricLanguageKotlin")
        propModDependency("modMenu", { "com.terraformersmc:modmenu:$it" })
    } else {
        propModDependency("klf", { "dev.nyon:KotlinLangForge:$kotlinLangForge-$it+neoforge" }, api = true)
    }

    prop("vers.deps.sodiumApi") { "modstitchModCompileOnly"("net.caffeinemc:sodium-$loader-api:$it") }
    compatDependency("sodium") { "maven.modrinth:sodium:$it" }
    compatDependency("iris") { "maven.modrinth:iris:$it" }
    compatDependency("etf") { "maven.modrinth:entitytexturefeatures:$it" }
    compatDependency("emf") { "maven.modrinth:entity-model-features:$it" }

    modstitchApi(libs.konfig)
    modstitchJiJ(libs.konfig)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks {
    register("releaseMod") {
        description = "Release mod to GitHub, Modrinth, CurseForge and Maven"
        group = "publishing"

        dependsOn("publishMods")
        dependsOn("publish")
    }

    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget = modstitch.javaVersion.map { JvmTarget.fromTarget(it.toString()) }
        }

        dependsOn("stonecutterGenerate")
    }

    test {
        useJUnitPlatform()
    }
}

val changelogText = buildString {
    append("# v${project.version}\n")
    if (beta != 0) appendLine("### As this is still a beta version, this version can contain bugs. Feel free to report ANY misbehaviours and errors!")
    rootDir.resolve("changelog.md").readText().also(::append)
}

val supportedMcVersions: List<String> =
    property("vers.supportedMcVersions")!!.toString().split(',').map(String::trim).filter(String::isNotEmpty)

publishMods {
    displayName = "v${project.version}"
    file = modstitch.finalJarTask.flatMap { it.archiveFile }
    changelog = changelogText
    type = if (beta != 0) BETA else STABLE
    if (isFabric) modLoaders.addAll("fabric", "quilt") else modLoaders.add("neoforge")

    modrinth {
        projectId = "TODO" // TODO: set once the Modrinth project exists (PLAN.md Gate B)
        accessToken = providers.environmentVariable("MODRINTH_API_KEY")
        minecraftVersions.addAll(supportedMcVersions)
        environment = ModrinthEnvironment.CLIENT_ONLY

        if (isFabric) {
            requires { slug = "fabric-api" }
            requires { slug = "fabric-language-kotlin" }
            optional { slug = "modmenu" }
        } else {
            requires { slug = "kotlin-lang-forge" }
        }
    }

    curseforge {
        projectId = "TODO" // TODO: set once the CurseForge project exists (PLAN.md Gate B)
        accessToken = providers.environmentVariable("CURSEFORGE_API_KEY")
        minecraftVersions.addAll(supportedMcVersions.mapNotNull { if (it.contains('-')) null else it })
        client = true
        server = false

        if (isFabric) {
            requires { slug = "fabric-api" }
            requires { slug = "fabric-language-kotlin" }
            optional { slug = "modmenu" }
        } else {
            requires { slug = "kotlinlangforge" }
        }
    }

    github {
        repository = property("mod.repo").toString()
        accessToken = providers.environmentVariable("GITHUB_TOKEN")
        commitish = property("mod.main-branch").toString()
    }
}

publishing {
    repositories {
        maven {
            name = "nyon"
            url = uri("https://repo.nyon.dev/releases")
            credentials {
                username = providers.environmentVariable("NYON_USERNAME").orNull
                password = providers.environmentVariable("NYON_PASSWORD").orNull
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.nyon"
            artifactId = property("mod.name").toString()
            version = project.version.toString()
            from(components["java"])
        }
    }
}

java {
    withSourcesJar()
}

fun <T> prop(property: String, required: Boolean = false, ifNull: () -> String? = { null }, block: (String) -> T?): T? {
    return ((System.getenv(property) ?: findProperty(property)?.toString())
        ?.takeUnless { it.isBlank() }
        ?: ifNull())
        .let { if (required && it == null) error("Property $property is required") else it }
        ?.let(block)
}
