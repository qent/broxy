pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    val kotlinVersion: String by settings
    val composePluginVersion: String by settings
    val shadowVersion: String by settings
    plugins {
        kotlin("multiplatform") version kotlinVersion
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.serialization") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.compose") version kotlinVersion
        id("org.jetbrains.compose") version composePluginVersion
        id("com.github.johnrengelman.shadow") version shadowVersion
    }
}

rootProject.name = "bro"

include(
    "core",
    "ui-adapter",
    "ui",
    "cli"
)
