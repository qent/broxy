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

// Convenience task to run tests across modules
tasks.register("testAll") {
    group = "verification"
    description = "Runs unit tests across modules (core JVM, CLI)."
    dependsOn(":core:jvmTest", ":cli:test")
}

// Alias for convenience (same as testAll)
tasks.register("allTests") {
    group = "verification"
    description = "Alias to testAll. Runs unit tests across modules (core JVM, CLI)."
    dependsOn("testAll")
}
