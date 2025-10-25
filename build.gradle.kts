plugins {
    kotlin("multiplatform") apply false
    id("org.jetbrains.compose") apply false
    kotlin("plugin.serialization") apply false
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}
