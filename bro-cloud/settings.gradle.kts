pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    val kotlinVersion: String by settings
    plugins {
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.serialization") version kotlinVersion
    }
}

rootProject.name = "bro-cloud"
