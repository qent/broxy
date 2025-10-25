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
                implementation("com.modelcontextprotocol:mcp-sdk:${property("mcpSdkVersion")}")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
            }
        }
    }
}
