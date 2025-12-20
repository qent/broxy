pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    val kotlinVersion: String by settings
    val composePluginVersion: String by settings
    val shadowVersion: String by settings
    val detektVersion: String by settings
    val ktlintVersion: String by settings
    val koverVersion: String by settings
    plugins {
        kotlin("multiplatform") version kotlinVersion
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.serialization") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.compose") version kotlinVersion
        id("org.jetbrains.compose") version composePluginVersion
        id("com.github.johnrengelman.shadow") version shadowVersion
        id("io.gitlab.arturbosch.detekt") version detektVersion
        id("org.jlleitschuh.gradle.ktlint") version ktlintVersion
        id("org.jetbrains.kotlinx.kover") version koverVersion
    }
}

rootProject.name = "broxy"

include(
    "core",
    "ui-adapter",
    "ui",
    "cli",
    "test-mcp-server"
)

val broCloudEnabled = providers.gradleProperty("broCloudEnabled").orNull?.toBoolean() ?: true
val broCloudUseLocal = providers.gradleProperty("broCloudUseLocal").orNull?.toBoolean() ?: false

if (broCloudEnabled && broCloudUseLocal) {
    val broCloudDir = rootDir.resolve("bro-cloud")
    if (broCloudDir.exists()) {
        includeBuild("bro-cloud")
    } else {
        logger.warn("bro-cloud local build requested but directory not found: $broCloudDir")
    }
}
