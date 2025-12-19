import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    kotlin("multiplatform") apply false
    kotlin("jvm") apply false
    id("org.jetbrains.compose") apply false
    kotlin("plugin.serialization") apply false
    id("io.gitlab.arturbosch.detekt") apply false
    id("org.jlleitschuh.gradle.ktlint") apply false
    id("org.jetbrains.kotlinx.kover")
}

val projectGroup: String by project
val projectVersion: String by project

allprojects {
    group = projectGroup
    version = projectVersion
    repositories {
        mavenCentral()
        google()
    }
}

subprojects {
    val detektConfig = rootProject.layout.projectDirectory.file("config/detekt/detekt.yml")

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        apply(plugin = "io.gitlab.arturbosch.detekt")
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "org.jetbrains.kotlinx.kover")
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "io.gitlab.arturbosch.detekt")
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "org.jetbrains.kotlinx.kover")
    }

    plugins.withId("io.gitlab.arturbosch.detekt") {
        configure<DetektExtension> {
            buildUponDefaultConfig = true
            allRules = false
            config.setFrom(detektConfig)
            basePath = rootProject.projectDir.absolutePath
        }
        tasks.withType<Detekt>().configureEach {
            jvmTarget = "17"
            reports {
                html.required.set(true)
                xml.required.set(true)
                sarif.required.set(true)
                txt.required.set(false)
            }
        }
    }

    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        configure<KtlintExtension> {
            outputToConsole.set(true)
            ignoreFailures.set(false)
            filter {
                exclude("**/build/**")
            }
        }
    }
}

// Convenience task to run tests across modules
tasks.register("testAll") {
    group = "verification"
    description = "Runs all tests across modules (unit + integration)."
    dependsOn(
        ":core:jvmTest",
        ":ui-adapter:jvmTest",
        ":cli:test",
        ":cli:integrationTest",
        ":test-mcp-server:test"
    )
}

// Alias for convenience (same as testAll)
tasks.register("allTests") {
    group = "verification"
    description = "Alias to testAll. Runs all tests across modules (unit + integration)."
    dependsOn("testAll")
}
