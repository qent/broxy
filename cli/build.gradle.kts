import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

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
}

application {
    mainClass.set("io.qent.bro.cli.MainKt")
}

tasks.withType<ShadowJar>().configureEach {
    archiveBaseName.set("bro-cli")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}
