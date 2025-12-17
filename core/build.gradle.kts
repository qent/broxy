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
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
                // MCP Kotlin SDK and required Ktor engines for JVM
                implementation("io.modelcontextprotocol:kotlin-sdk:${property("mcpSdkVersion")}")
                implementation("io.modelcontextprotocol:kotlin-sdk-server-jvm:${property("mcpSdkVersion")}")
                implementation("io.ktor:ktor-client-cio:${property("ktorVersion")}")
                implementation("io.ktor:ktor-client-websockets:${property("ktorVersion")}")
                implementation("io.ktor:ktor-server-netty:${property("ktorVersion")}")
                implementation("io.ktor:ktor-server-websockets:${property("ktorVersion")}")
                implementation("io.ktor:ktor-server-content-negotiation:${property("ktorVersion")}")
                implementation("io.ktor:ktor-serialization-kotlinx-json:${property("ktorVersion")}")
                implementation("io.ktor:ktor-server-call-logging:${property("ktorVersion")}")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.mockito:mockito-core:5.14.1")
                implementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${property("coroutinesVersion")}")
            }
        }
    }
}
