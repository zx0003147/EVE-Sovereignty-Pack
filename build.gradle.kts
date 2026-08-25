import org.gradle.api.artifacts.ProjectDependency
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    compileOnly(project(":feature-api"))

    testImplementation(project(":feature-api"))
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.jar {
    manifest {
        attributes(
            "EVE-Feature-Pack-Id" to "sovereignty.pack",
            "EVE-Feature-Pack-Name" to "Sovereignty Pack",
            "EVE-Feature-Pack-Version" to "0.1.0",
            "EVE-Feature-Pack-Publisher" to "EVE Static Map Planner",
        )
    }
}

val packageExternalFeaturePack by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the Sovereignty Pack in the external Feature Pack directory layout."
    dependsOn(tasks.jar)
    from(tasks.jar.flatMap(Jar::getArchiveFile))
    into(layout.buildDirectory.dir("external-feature-pack/sovereignty.pack"))
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
    description = "Verifies that the Pack consumes only the public Feature API."
    doLast {
        val productionConfigurations = listOf("api", "implementation", "compileOnly", "runtimeOnly")
        val projectDependencies = productionConfigurations.flatMap { configurationName ->
            configurations.getByName(configurationName).dependencies.withType(ProjectDependency::class.java)
        }
        check(projectDependencies.map(ProjectDependency::getPath) == listOf(":feature-api")) {
            "sovereignty-pack production project dependencies must contain only feature-api: " +
                projectDependencies.map(ProjectDependency::getPath)
        }
        val externalDependencies = productionConfigurations.flatMap { configurationName ->
            configurations.getByName(configurationName).dependencies
        }.filterNot { it is ProjectDependency }
        check(externalDependencies.all { dependency ->
            dependency.group == "org.jetbrains.kotlin" && dependency.name == "kotlin-stdlib"
        }) {
            "sovereignty-pack has an unsupported production dependency: " +
                externalDependencies.joinToString { "${it.group}:${it.name}" }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.assemble {
    dependsOn(packageExternalFeaturePack)
}

tasks.check {
    dependsOn(verifySovereigntyPackDependencies)
}
