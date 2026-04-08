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
    val enableDetektNoUnreferencedDeclarations =
        rootProject.providers
            .gradleProperty("enableDetektNoUnreferencedDeclarations")
            .map { value -> value.equals("true", ignoreCase = true) }
            .orElse(false)
    val detektConfig = rootProject.layout.projectDirectory.file("config/detekt/detekt.yml")
    val noFullyQualifiedConfig =
        rootProject
            .layout
            .projectDirectory
            .file("config/detekt/no_fully_qualified_names.yml")
    val noUnreferencedDeclarationsConfig =
        rootProject
            .layout
            .projectDirectory
            .file("config/detekt/no_unreferenced_declarations.yml")

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        if (name != "detekt-rules") {
            apply(plugin = "io.gitlab.arturbosch.detekt")
            apply(plugin = "org.jetbrains.kotlinx.kover")
        }
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        if (name != "detekt-rules") {
            apply(plugin = "io.gitlab.arturbosch.detekt")
            apply(plugin = "org.jetbrains.kotlinx.kover")
        }
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }

    plugins.withId("io.gitlab.arturbosch.detekt") {
        configure<DetektExtension> {
            buildUponDefaultConfig = true
            allRules = false
            config.setFrom(detektConfig)
            basePath = rootProject.projectDir.absolutePath
        }
        if (name != "detekt-rules") {
            dependencies {
                add("detektPlugins", project(":detekt-rules"))
            }
        }
        val detektNoFullyQualifiedNames by tasks.registering(Detekt::class) {
            group = "verification"
            description = "Runs NoFullyQualifiedNames across all Kotlin source files in this module."
            buildUponDefaultConfig = false
            allRules = false
            config.setFrom(noFullyQualifiedConfig)
            basePath = rootProject.projectDir.absolutePath
            setSource(files(project.projectDir.resolve("src")))
            include("**/*.kt")
            exclude(
                "**/build/**",
                "**/test/**",
                "**/androidTest/**",
                "**/commonTest/**",
                "**/jvmTest/**",
                "**/desktopTest/**",
                "**/integrationTest/**",
            )
            detektClasspath.from(configurations.getByName("detekt"))
            pluginClasspath.from(configurations.getByName("detektPlugins"))
            jvmTarget = "17"
            reports {
                html.required.set(false)
                xml.required.set(false)
                sarif.required.set(false)
                txt.required.set(false)
            }
        }
        val detektNoUnreferencedDeclarations by tasks.registering(Detekt::class) {
            group = "verification"
            description = "Runs UnreferencedDeclaration across all Kotlin source files in this module."
            buildUponDefaultConfig = false
            allRules = false
            config.setFrom(noUnreferencedDeclarationsConfig)
            basePath = rootProject.projectDir.absolutePath
            setSource(files(project.projectDir.resolve("src")))
            include("**/*.kt")
            exclude(
                "**/build/**",
                "**/test/**",
                "**/androidTest/**",
                "**/commonTest/**",
                "**/jvmTest/**",
                "**/desktopTest/**",
                "**/integrationTest/**",
            )
            detektClasspath.from(configurations.getByName("detekt"))
            pluginClasspath.from(configurations.getByName("detektPlugins"))
            jvmTarget = "17"
            reports {
                html.required.set(false)
                xml.required.set(false)
                sarif.required.set(false)
                txt.required.set(false)
            }
        }
        tasks.withType<Detekt>().configureEach {
            jvmTarget = "17"
            if (name == "detektNoFullyQualifiedNames" || name == "detektNoUnreferencedDeclarations") {
                reports {
                    html.required.set(false)
                    xml.required.set(false)
                    sarif.required.set(false)
                    txt.required.set(false)
                }
            } else {
                reports {
                    html.required.set(true)
                    xml.required.set(true)
                    sarif.required.set(true)
                    txt.required.set(false)
                }
            }
        }
        tasks.named("check") {
            dependsOn(detektNoFullyQualifiedNames)
            if (enableDetektNoUnreferencedDeclarations.get()) {
                dependsOn(detektNoUnreferencedDeclarations)
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
        ":agents:test",
        ":agents-codex:test",
        ":core:jvmTest",
        ":server-registry:jvmTest",
        ":headless-runtime:test",
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
