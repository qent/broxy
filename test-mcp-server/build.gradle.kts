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
}

application {
    mainClass.set("io.qent.broxy.testserver.SimpleTestMcpServerKt")
}
