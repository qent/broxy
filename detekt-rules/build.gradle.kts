import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly("io.gitlab.arturbosch.detekt:detekt-api:${property("detektVersion")}")
    testImplementation("io.gitlab.arturbosch.detekt:detekt-test:${property("detektVersion")}")
    testImplementation("io.gitlab.arturbosch.detekt:detekt-test-utils:${property("detektVersion")}")
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
