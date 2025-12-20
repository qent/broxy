import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val broCloudEnabled = providers.gradleProperty("broCloudEnabled").orNull?.toBoolean() ?: false
val broCloudUseLocal = providers.gradleProperty("broCloudUseLocal").orNull?.toBoolean() ?: false
val projectVersion: String by project

val broCloudGeneratedDir = layout.buildDirectory.dir("generated/bro-cloud")
val generateBroCloudBuildConfig by tasks.registering {
    outputs.dir(broCloudGeneratedDir)
    doLast {
        val targetDir = broCloudGeneratedDir.get().asFile.resolve("io/qent/broxy/ui/adapter/remote")
        targetDir.mkdirs()
        targetDir.resolve("BroCloudBuildConfig.kt").writeText(
            """
            package io.qent.broxy.ui.adapter.remote

            internal object BroCloudBuildConfig {
                const val ENABLED: Boolean = $broCloudEnabled
            }
            """.trimIndent() + "\n",
        )
    }
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
            kotlin.srcDir(broCloudGeneratedDir)
            dependencies {
                implementation("io.modelcontextprotocol:kotlin-sdk-server:${property("mcpSdkVersion")}")
                if (broCloudUseLocal) {
                    val broCloudDependency = "io.qent.broxy:bro-cloud:$projectVersion"
                    compileOnly(broCloudDependency)
                    if (broCloudEnabled) {
                        runtimeOnly(broCloudDependency)
                    }
                } else {
                    val broCloudJar =
                        rootProject.layout.projectDirectory.file("bro-cloud/libs/bro-cloud-obfuscated.jar").asFile
                    if (!broCloudJar.exists()) {
                        throw GradleException(
                            "bro-cloud obfuscated jar not found at ${broCloudJar.path}. " +
                                "Set -PbroCloudUseLocal=true or provide the jar.",
                        )
                    }
                    val broCloudFiles = files(broCloudJar)
                    compileOnly(broCloudFiles)
                    if (broCloudEnabled) {
                        runtimeOnly(broCloudFiles)
                        runtimeOnly("io.ktor:ktor-client-core:${property("ktorVersion")}")
                        runtimeOnly("io.ktor:ktor-client-cio:${property("ktorVersion")}")
                        runtimeOnly("io.ktor:ktor-client-websockets:${property("ktorVersion")}")
                        runtimeOnly("io.ktor:ktor-client-content-negotiation:${property("ktorVersion")}")
                        runtimeOnly("io.ktor:ktor-serialization-kotlinx-json:${property("ktorVersion")}")
                    }
                }
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.mockito:mockito-core:5.21.0")
                implementation("org.mockito.kotlin:mockito-kotlin:6.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${property("coroutinesVersion")}")
            }
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generateBroCloudBuildConfig)
}

tasks.withType<KtLintCheckTask>().configureEach {
    dependsOn(generateBroCloudBuildConfig)
}
