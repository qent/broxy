plugins {
    kotlin("multiplatform") apply false
    kotlin("jvm") apply false
    id("org.jetbrains.compose") apply false
    kotlin("plugin.serialization") apply false
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
