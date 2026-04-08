import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask
import java.net.HttpURLConnection
import java.net.URL

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val catalogRepoOwner = providers.gradleProperty("broxyCatalogRepoOwner").orNull ?: "qent"
val catalogRepoName = providers.gradleProperty("broxyCatalogRepoName").orNull ?: "broxy-registry"
val catalogRepoBranch = providers.gradleProperty("broxyCatalogRepoBranch").orNull ?: "main"

val bundledCatalogResourcesDir = layout.buildDirectory.dir("generated/mcp-catalog/commonMain/resources")
val catalogSeedFile = layout.projectDirectory.file("catalog-seed/catalog_bundle.json").asFile

val generateBundledCatalog by tasks.registering {
    inputs.property("catalogRepoOwner", catalogRepoOwner)
    inputs.property("catalogRepoName", catalogRepoName)
    inputs.property("catalogRepoBranch", catalogRepoBranch)
    inputs.file(catalogSeedFile)
    outputs.dir(bundledCatalogResourcesDir)
    doLast {
        val outputDir = bundledCatalogResourcesDir.get().asFile.resolve("catalog")
        outputDir.mkdirs()
        val outputFile = outputDir.resolve("catalog_bundle.json")
        val rawBaseUrl = "https://raw.githubusercontent.com/$catalogRepoOwner/$catalogRepoName/$catalogRepoBranch"
        val indexUrl = "$rawBaseUrl/index.json"
        val jsonSlurper = JsonSlurper()

        fun fetchText(url: String): String {
            val connection =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 20_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    instanceFollowRedirects = true
                }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                error("HTTP $responseCode for $url")
            }
            return connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        }

        val bundledText =
            runCatching {
                val indexRaw = fetchText(indexUrl)
                val index =
                    (jsonSlurper.parseText(indexRaw) as? Map<*, *>)
                        ?: error("Catalog index must be a JSON object")
                val serverRefs =
                    (index["servers"] as? List<*>)
                        ?.mapNotNull { it as? Map<*, *> }
                        .orEmpty()
                if (serverRefs.isEmpty()) {
                    error("Catalog index has no servers")
                }
                val serverObjects =
                    serverRefs.map { ref ->
                        val path =
                            ref["path"]
                                ?.toString()
                                ?.trim()
                                ?.removePrefix("/")
                                ?.takeIf { it.isNotEmpty() }
                                ?: error("Catalog index entry is missing non-empty path")
                        val serverRaw = fetchText("$rawBaseUrl/$path")
                        jsonSlurper.parseText(serverRaw)
                    }
                val bundle =
                    mapOf(
                        "source" to "$catalogRepoOwner/$catalogRepoName@$catalogRepoBranch",
                        "updatedAtEpochMillis" to System.currentTimeMillis(),
                        "servers" to serverObjects,
                    )
                JsonOutput.prettyPrint(JsonOutput.toJson(bundle)) + "\n"
            }.getOrElse { failure ->
                logger.warn(
                    "Failed to fetch catalog from $catalogRepoOwner/$catalogRepoName@$catalogRepoBranch: " +
                        "${failure.message}. Falling back to seed catalog.",
                )
                if (!catalogSeedFile.exists()) {
                    throw GradleException("Catalog seed file is missing at ${catalogSeedFile.path}", failure)
                }
                catalogSeedFile.readText(Charsets.UTF_8)
            }

        outputFile.writeText(bundledText, Charsets.UTF_8)
    }
}

kotlin {
    jvm()

    jvmToolchain(17)

    sourceSets {
        val commonMain by getting {
            resources.srcDir(bundledCatalogResourcesDir)
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serializationVersion")}")
            }
        }
        val jvmMain by getting
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${property("coroutinesVersion")}")
            }
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generateBundledCatalog)
}

tasks.withType<KtLintCheckTask>().configureEach {
    dependsOn(generateBundledCatalog)
}

tasks.matching { it.name.contains("ProcessResources", ignoreCase = true) }.configureEach {
    dependsOn(generateBundledCatalog)
}
