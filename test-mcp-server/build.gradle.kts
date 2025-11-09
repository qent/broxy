plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serializationVersion")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutinesVersion")}")
    implementation("io.modelcontextprotocol:kotlin-sdk-core-jvm:${property("mcpSdkVersion")}")
    implementation("io.modelcontextprotocol:kotlin-sdk-server-jvm:${property("mcpSdkVersion")}")
    implementation("io.ktor:ktor-server-core-jvm:${property("ktorVersion")}")
    implementation("io.ktor:ktor-server-call-logging-jvm:${property("ktorVersion")}")
    implementation("io.ktor:ktor-server-sse-jvm:${property("ktorVersion")}")
    implementation("io.ktor:ktor-server-netty-jvm:${property("ktorVersion")}")

    testImplementation(kotlin("test"))
    testImplementation(project(":core"))
}

application {
    mainClass.set("io.qent.broxy.testserver.SimpleTestMcpServerKt")
}

val testServerHome = layout.buildDirectory.dir("install/test-mcp-server")

tasks.test {
    dependsOn(tasks.named("installDist"))
    useJUnitPlatform()
    systemProperty("test.mcpServerHome", testServerHome.get().asFile.absolutePath)
}
