import org.gradle.api.tasks.testing.Test
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
    inputs.property("broCloudEnabled", broCloudEnabled)
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
                api(project(":server-registry"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutinesVersion")}")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serializationVersion")}")
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
                        implementation(broCloudDependency)
                    }
                } else {
                    val broCloudJar =
                        rootProject
                            .layout
                            .projectDirectory
                            .file("lib/bro-cloud-obfuscated.jar")
                            .asFile
                    if (!broCloudJar.exists()) {
                        throw GradleException(
                            "bro-cloud obfuscated jar not found at ${broCloudJar.path}. " +
                                "Set -PbroCloudUseLocal=true or provide the jar.",
                        )
                    }
                    val broCloudFiles = files(broCloudJar)
                    compileOnly(broCloudFiles)
                    if (broCloudEnabled) {
                        implementation(broCloudFiles)
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
                implementation("io.modelcontextprotocol:kotlin-sdk-core:${property("mcpSdkVersion")}")
            }
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generateBroCloudBuildConfig)
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}

tasks.withType<KtLintCheckTask>().configureEach {
    dependsOn(generateBroCloudBuildConfig)
}

val uiAdapterCoreAllowedPrefixes =
    listOf(
        "io.qent.broxy.core.mcp",
        "io.qent.broxy.core.models",
        "io.qent.broxy.core.proxy.runtime",
        "io.qent.broxy.core.repository",
        "io.qent.broxy.core.utils",
    )
val uiAdapterCoreAllowedByFile =
    mapOf(
        "src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/AppStore.kt" to
            listOf("io.qent.broxy.core.config"),
        "src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/AppStoreIntents.kt" to
            listOf("io.qent.broxy.core.config"),
        "src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/StoreConfigGateway.kt" to
            listOf("io.qent.broxy.core.config"),
        "src/jvmMain/kotlin/io/qent/broxy/ui/adapter/data/RepositoriesJvm.kt" to
            listOf("io.qent.broxy.core.config"),
        "src/jvmMain/kotlin/io/qent/broxy/ui/adapter/services/ToolServiceJvm.kt" to
            listOf("io.qent.broxy.core.config"),
    )

val checkUiAdapterCoreBoundary by tasks.registering {
    group = "verification"
    description = "Fails if ui-adapter imports core packages outside the allowlist."
    val sourceFiles = fileTree("src") { include("**/*.kt") }
    inputs.files(sourceFiles)
    doLast {
        val importRegex = Regex("^import\\s+(io\\.qent\\.broxy\\.core\\.[^\\s]+)")
        val violations = mutableListOf<String>()
        sourceFiles.files.sortedBy { it.path }.forEach { file ->
            val relativePath = file.relativeTo(projectDir).invariantSeparatorsPath
            val fileAllowedPrefixes =
                uiAdapterCoreAllowedPrefixes + (uiAdapterCoreAllowedByFile[relativePath] ?: emptyList())
            file.readLines().forEachIndexed { index, line ->
                val match = importRegex.find(line) ?: return@forEachIndexed
                val importName = match.groupValues[1]
                if (fileAllowedPrefixes.none { importName.startsWith(it) }) {
                    violations += "$relativePath:${index + 1}: $importName"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "ui-adapter imports core packages outside the allowlist:\n" +
                    violations.joinToString("\n"),
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkUiAdapterCoreBoundary)
}
