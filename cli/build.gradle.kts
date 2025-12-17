import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.get

plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation("com.github.ajalt.clikt:clikt:${property("cliktVersion")}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serializationVersion")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutinesVersion")}")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.qent.broxy.cli.MainKt")
}

tasks.withType<ShadowJar>().configureEach {
    archiveBaseName.set("broxy-cli")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

val sourceSets = extensions.getByType(SourceSetContainer::class.java)
val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
    runtimeClasspath += output + compileClasspath
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

val shadowJarTask = tasks.named<ShadowJar>("shadowJar")

val testServerProject = project(":test-mcp-server")
val testServerHome = testServerProject.layout.buildDirectory.dir("install/test-mcp-server")

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs broxy CLI jar integration tests (STDIO + HTTP Streamable)."
    group = "verification"
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.named("test"))
    dependsOn(shadowJarTask, ":test-mcp-server:installDist")
    useJUnitPlatform()
    val jarFile = shadowJarTask.flatMap { it.archiveFile }
    systemProperty("broxy.cliJar", jarFile.get().asFile.absolutePath)
    systemProperty("broxy.testMcpServerHome", testServerHome.get().asFile.absolutePath)
    // Hard timeout per test to avoid hanging MCP e2e runs
    systemProperty("junit.jupiter.execution.timeout.default", "5s")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("junit.jupiter.execution.timeout.default", "5s")
}
