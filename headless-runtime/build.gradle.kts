plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutinesVersion")}")
    implementation("io.modelcontextprotocol:kotlin-sdk-server:${property("mcpSdkVersion")}")

    testImplementation(kotlin("test"))
}
