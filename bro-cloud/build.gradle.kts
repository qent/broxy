import proguard.gradle.ProGuardTask

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:${property("proguardVersion")}")
    }
}

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val projectGroup: String by project
val projectVersion: String by project

group = projectGroup
version = projectVersion

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutinesVersion")}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serializationVersion")}")
    implementation("io.ktor:ktor-client-core:${property("ktorVersion")}")
    implementation("io.ktor:ktor-client-cio:${property("ktorVersion")}")
    implementation("io.ktor:ktor-client-websockets:${property("ktorVersion")}")
    implementation("io.ktor:ktor-client-content-negotiation:${property("ktorVersion")}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${property("ktorVersion")}")
    implementation("io.modelcontextprotocol:kotlin-sdk-core:${property("mcpSdkVersion")}")

    testImplementation(kotlin("test"))
}

val obfuscatedJarPath = layout.projectDirectory.file("libs/bro-cloud-obfuscated.jar")

val obfuscateJar by tasks.registering(ProGuardTask::class) {
    dependsOn(tasks.named("jar"))
    doFirst {
        obfuscatedJarPath.asFile.parentFile.mkdirs()
    }
    injars(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    outjars(obfuscatedJarPath)
    configuration("proguard-rules.pro")

    val javaHome = System.getProperty("java.home")
    val jmods = file("$javaHome/jmods")
    if (jmods.exists()) {
        libraryjars(files(jmods).asFileTree.matching { include("**/*.jmod") })
    }
    libraryjars(configurations.runtimeClasspath)
}
