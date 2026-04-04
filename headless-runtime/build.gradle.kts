plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutinesVersion")}")
    implementation("io.modelcontextprotocol:kotlin-sdk-server:${property("mcpSdkVersion")}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serializationVersion")}")

    testImplementation(kotlin("test"))
}
