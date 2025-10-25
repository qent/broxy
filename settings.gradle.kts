pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    val kotlinVersion: String by settings
    val composePluginVersion: String by settings
    plugins {
        kotlin("multiplatform") version kotlinVersion
        kotlin("plugin.serialization") version kotlinVersion
        id("org.jetbrains.compose") version composePluginVersion
    }
}

rootProject.name = "bro"

include(
    "core",
    "ui",
    "cli"
)
