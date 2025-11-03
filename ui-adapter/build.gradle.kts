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
                // Do NOT expose core via API to UI
                implementation(project(":core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutinesVersion")}")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serializationVersion")}")
                implementation("io.modelcontextprotocol:kotlin-sdk-core:${property("mcpSdkVersion")}")
            }
        }
        val jvmMain by getting {
            dependencies {
                // no additional deps
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
