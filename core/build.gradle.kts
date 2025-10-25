plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()

    jvmToolchain(17)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutinesVersion")}")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serializationVersion")}")
                implementation("io.ktor:ktor-client-core:${property("ktorVersion")}")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
                // MCP Kotlin SDK and required Ktor engines for JVM
                implementation("io.modelcontextprotocol:kotlin-sdk:${property("mcpSdkVersion")}")
                implementation("io.ktor:ktor-client-cio:${property("ktorVersion")}")
                implementation("io.ktor:ktor-server-netty:${property("ktorVersion")}")
            }
        }
    }
}
