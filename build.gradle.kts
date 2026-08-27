import org.gradle.api.artifacts.ProjectDependency
import org.gradle.jvm.tasks.Jar
import java.util.jar.JarFile

plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
}

group = "dev.evestaticmapplanner"

val packVersion = providers.gradleProperty("packVersion").get()
val featureApiArtifactVersion = providers.gradleProperty("featureApiArtifactVersion").get()
val featureApiCoordinate = "dev.evestaticmapplanner:feature-api:$featureApiArtifactVersion"
val featureApiContractVersion = "1"
val packId = "sovereignty.pack"
val packName = "Sovereignty Pack"
val packPublisher = "EVE Static Map Planner"
val packUserAgentProduct = "EVE-Sovereignty-Pack"

version = packVersion

dependencies {
    compileOnly(featureApiCoordinate)
    compileOnly(kotlin("stdlib"))

    testImplementation(featureApiCoordinate)
    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

val generatedPackMetadataDirectory = layout.buildDirectory.dir("generated/sources/pack-metadata/kotlin")
val generatedPackMetadataFile = generatedPackMetadataDirectory.map {
    it.file("dev/evestaticmapplanner/sovereignty/PackBuildMetadata.kt")
}

val generatePackMetadata by tasks.registering {
    group = "build setup"
    description = "Generates Pack-owned runtime version metadata from Gradle properties."
    inputs.property("packVersion", packVersion)
    inputs.property("packUserAgentProduct", packUserAgentProduct)
    outputs.file(generatedPackMetadataFile)

    doLast {
        val outputFile = generatedPackMetadataFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package dev.evestaticmapplanner.sovereignty

            internal object PackBuildMetadata {
                const val PACK_VERSION = "$packVersion"
                const val USER_AGENT = "$packUserAgentProduct/$packVersion"
            }
            """.trimIndent() + System.lineSeparator(),
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generatedPackMetadataDirectory)
}

tasks.named("compileKotlin") {
    dependsOn(generatePackMetadata)
}

tasks.jar {
    manifest {
        attributes(
            "EVE-Feature-Pack-Id" to packId,
            "EVE-Feature-Pack-Name" to packName,
            "EVE-Feature-Pack-Version" to packVersion,
            "EVE-Feature-Pack-Publisher" to packPublisher,
            "EVE-Feature-API-Version" to featureApiContractVersion,
        )
    }
}

val canonicalPackDirectory = layout.buildDirectory.dir("external-feature-pack/$packId")
val canonicalPackJar = canonicalPackDirectory.map { it.file("pack.jar") }

val packageExternalFeaturePack by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the standalone Sovereignty Pack in the canonical external Feature Pack layout."
    dependsOn(tasks.jar)
    from(tasks.jar.flatMap(Jar::getArchiveFile))
    into(canonicalPackDirectory)
    rename { "pack.jar" }
}

val sovereigntyPackElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(sovereigntyPackElements.name, tasks.jar)
}

val verifySovereigntyPackDependencies by tasks.registering {
    group = "verification"
    description = "Verifies the standalone Pack dependency boundary and Feature API usage."
    doLast {
        val projectDependencies = configurations.flatMap { configuration ->
            configuration.dependencies.withType(ProjectDependency::class.java).map { dependency ->
                "${configuration.name}:${dependency.path}"
            }
        }
        check(projectDependencies.isEmpty()) {
            "Sovereignty Pack must not have project dependencies: $projectDependencies"
        }

        val featureApiDeclarations = configurations.flatMap { configuration ->
            configuration.dependencies
                .filter { dependency ->
                    dependency.group == "dev.evestaticmapplanner" && dependency.name == "feature-api"
                }
                .map { dependency -> "${configuration.name}:${dependency.group}:${dependency.name}:${dependency.version}" }
        }.sorted()
        val expectedFeatureApiDeclarations = listOf(
            "compileOnly:$featureApiCoordinate",
            "testImplementation:$featureApiCoordinate",
        )
        check(featureApiDeclarations == expectedFeatureApiDeclarations) {
            "Feature API must be declared only as compileOnly and testImplementation: $featureApiDeclarations"
        }

        val productionConfigurations = setOf("api", "implementation", "compileOnly", "runtimeOnly")
        val unsupportedProductionDependencies = productionConfigurations.flatMap { configurationName ->
            configurations.getByName(configurationName).dependencies.mapNotNull { dependency ->
                val coordinate = "${dependency.group.orEmpty()}:${dependency.name}:${dependency.version.orEmpty()}"
                val allowed = configurationName == "compileOnly" && (
                    coordinate == featureApiCoordinate ||
                        (dependency.group == "org.jetbrains.kotlin" && dependency.name == "kotlin-stdlib")
                    )
                if (allowed) null else "$configurationName:$coordinate"
            }
        }
        check(unsupportedProductionDependencies.isEmpty()) {
            "Sovereignty Pack has unsupported production dependencies: $unsupportedProductionDependencies"
        }

        val forbiddenDependencyFragments = listOf("compose", "sqlite")
        val forbiddenStandaloneModules = setOf("app", "core", "data", "sde", "mcp", "control", "control-transport")
        val forbiddenDependencies = configurations.flatMap { configuration ->
            configuration.dependencies.mapNotNull { dependency ->
                val coordinate = "${dependency.group.orEmpty()}:${dependency.name}".lowercase()
                val isForbidden = forbiddenDependencyFragments.any(coordinate::contains) ||
                    (dependency.group == "dev.evestaticmapplanner" && dependency.name in forbiddenStandaloneModules)
                if (isForbidden) "${configuration.name}:$coordinate" else null
            }
        }
        check(forbiddenDependencies.isEmpty()) {
            "Sovereignty Pack has forbidden standalone dependencies: $forbiddenDependencies"
        }
    }
}

val verifyStandaloneIsolation by tasks.registering {
    group = "verification"
    description = "Rejects monorepo project dependencies and committed Core source paths."
    val checkedFiles = fileTree(layout.projectDirectory) {
        exclude(".git/**", ".gradle/**", "build/**", "out/**", ".idea/**")
        include("**/*.gradle.kts", "**/*.gradle", "**/*.properties", "**/*.md", "**/*.kt")
    }
    inputs.files(checkedFiles)

    doLast {
        val projectCall = "project" + "("
        val compositeCall = "include" + "Build"
        val absoluteWindowsUserPath = "C:" + "\\Users\\"
        val violations = checkedFiles.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val normalized = line.replace(" ", "")
                val isViolation = normalized.contains(projectCall) ||
                    normalized.contains(compositeCall) ||
                    line.contains(absoluteWindowsUserPath, ignoreCase = true)
                if (isViolation) "${file.relativeTo(projectDir)}:${index + 1}:$line" else null
            }
        }
        check(violations.isEmpty()) {
            "Standalone isolation violations found:\n${violations.joinToString("\n")}"
        }
    }
}

val verifyCanonicalSovereigntyPack by tasks.registering {
    group = "verification"
    description = "Verifies the canonical thin standalone Sovereignty Pack artifact."
    dependsOn(packageExternalFeaturePack)
    inputs.file(canonicalPackJar)

    doLast {
        val outputDirectory = canonicalPackDirectory.get().asFile
        val outputFiles = outputDirectory.listFiles()?.map { it.name }?.sorted().orEmpty()
        check(outputFiles == listOf("pack.jar")) {
            "Canonical Pack output must contain only pack.jar: $outputFiles"
        }

        val packJar = canonicalPackJar.get().asFile
        check(packJar.isFile) { "Canonical Pack JAR is missing: $packJar" }
        JarFile(packJar).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toList()
            val servicePath = "META-INF/services/dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint"
            check(entries.count { it == servicePath } == 1) {
                "Pack must contain exactly one FeaturePackEntrypoint ServiceLoader resource"
            }
            val serviceProviders = jar.getInputStream(checkNotNull(jar.getJarEntry(servicePath)))
                .bufferedReader()
                .useLines { lines ->
                    lines.map(String::trim)
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toList()
                }
            check(serviceProviders == listOf("dev.evestaticmapplanner.sovereignty.SovereigntyFeaturePack")) {
                "Pack must declare exactly the Sovereignty FeaturePackEntrypoint: $serviceProviders"
            }

            val manifest = checkNotNull(jar.manifest) { "Pack manifest is missing" }
            val attributes = manifest.mainAttributes
            val expectedManifest = mapOf(
                "EVE-Feature-Pack-Id" to packId,
                "EVE-Feature-Pack-Name" to packName,
                "EVE-Feature-Pack-Version" to packVersion,
                "EVE-Feature-Pack-Publisher" to packPublisher,
                "EVE-Feature-API-Version" to featureApiContractVersion,
            )
            val incorrectManifestFields = expectedManifest.filter { (name, expectedValue) ->
                attributes.getValue(name) != expectedValue
            }
            check(incorrectManifestFields.isEmpty()) {
                "Pack manifest does not match build authority: $incorrectManifestFields"
            }

            val expectedEntries = setOf(
                "dev/evestaticmapplanner/sovereignty/SovereigntyFeaturePack.class",
                "dev/evestaticmapplanner/sovereignty/PackBuildMetadata.class",
                "sovereignty.json",
            )
            check(entries.containsAll(expectedEntries)) {
                "Pack is missing expected implementation entries: ${expectedEntries - entries.toSet()}"
            }

            val forbiddenPrefixes = listOf(
                "dev/evestaticmapplanner/feature/api/",
                "dev/evestaticmapplanner/app/",
                "dev/evestaticmapplanner/core/",
                "dev/evestaticmapplanner/data/",
                "dev/evestaticmapplanner/sde/",
                "dev/evestaticmapplanner/mcp/",
                "dev/evestaticmapplanner/control/",
                "dev/evestaticmapplanner/control/transport/",
                "kotlin/",
                "kotlinx/",
                "androidx/compose/",
                "org/jetbrains/compose/",
                "org/sqlite/",
            )
            val forbiddenEntries = entries.filter { entry -> forbiddenPrefixes.any(entry::startsWith) }
            check(forbiddenEntries.isEmpty()) {
                "Pack bundles Host-owned or runtime dependency classes: $forbiddenEntries"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(packageExternalFeaturePack)
    systemProperty("canonical.pack.jar", canonicalPackJar.get().asFile.absolutePath)
}

tasks.assemble {
    dependsOn(packageExternalFeaturePack)
}

tasks.check {
    dependsOn(verifySovereigntyPackDependencies)
    dependsOn(verifyStandaloneIsolation)
    dependsOn(verifyCanonicalSovereigntyPack)
}
