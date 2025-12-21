import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.util.jar.JarFile

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation("com.github.ajalt.clikt:clikt:${property("cliktVersion")}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serializationVersion")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutinesVersion")}")

    testImplementation(kotlin("test"))
}

val mainClassName = "io.qent.broxy.cli.MainKt"
val sourceSets = extensions.getByType(SourceSetContainer::class.java)
val mainSourceSet = sourceSets["main"]
val mergedServicesDir = layout.buildDirectory.dir("generated/merged-services")

val mergeServiceFiles =
    tasks.register("mergeServiceFiles") {
        inputs.files(configurations.runtimeClasspath)
        inputs.files(mainSourceSet.output)
        outputs.dir(mergedServicesDir)

        doLast {
            val outputRoot = mergedServicesDir.get().asFile
            if (outputRoot.exists()) {
                outputRoot.deleteRecursively()
            }
            outputRoot.mkdirs()

            val merged = linkedMapOf<String, LinkedHashSet<String>>()

            fun addLines(
                path: String,
                lines: List<String>,
            ) {
                val filtered =
                    lines
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                if (filtered.isEmpty()) {
                    return
                }
                val target = merged.getOrPut(path) { LinkedHashSet() }
                filtered.forEach { target.add(it) }
            }

            fun scanDirectory(root: File) {
                val servicesRoot = root.resolve("META-INF/services")
                if (!servicesRoot.exists()) {
                    return
                }
                servicesRoot
                    .walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val relative =
                            servicesRoot.toPath().relativize(file.toPath()).toString()
                                .replace(File.separatorChar, '/')
                        addLines("META-INF/services/$relative", file.readLines())
                    }
            }

            fun scanJar(jar: File) {
                JarFile(jar).use { jarFile ->
                    jarFile.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                        .forEach { entry ->
                            val lines = jarFile.getInputStream(entry).bufferedReader().readLines()
                            addLines(entry.name, lines)
                        }
                }
            }

            val inputsToScan = mutableListOf<File>()
            inputsToScan.addAll(mainSourceSet.output.files)
            inputsToScan.addAll(configurations.runtimeClasspath.get().files)

            inputsToScan.forEach { file ->
                when {
                    file.isDirectory -> scanDirectory(file)
                    file.isFile && file.extension == "jar" -> scanJar(file)
                }
            }

            merged.forEach { (path, providers) ->
                val outFile = File(outputRoot, path)
                outFile.parentFile.mkdirs()
                outFile.writeText(providers.joinToString("\n", postfix = "\n"))
            }
        }
    }

val shadowJarTask =
    tasks.register<Jar>("shadowJar") {
        archiveBaseName.set("broxy-cli")
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest {
            attributes["Main-Class"] = mainClassName
        }

        from(mainSourceSet.output) {
            exclude("META-INF/services/**")
        }
        from({
            configurations.runtimeClasspath.get().map { dependency ->
                if (dependency.isDirectory) {
                    dependency
                } else {
                    zipTree(dependency)
                }
            }
        }) {
            exclude("META-INF/services/**")
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        }

        dependsOn(mergeServiceFiles)
        from(mergedServicesDir) {
            include("META-INF/services/**")
        }
    }

tasks.named("build") {
    dependsOn(shadowJarTask)
}

val integrationTestSourceSet =
    sourceSets.create("integrationTest") {
        compileClasspath += mainSourceSet.output + configurations["testRuntimeClasspath"]
        runtimeClasspath += output + compileClasspath
    }

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

val testServerProject = project(":test-mcp-server")
val testServerHome = testServerProject.layout.buildDirectory.dir("install/test-mcp-server")

val integrationTest =
    tasks.register<Test>("integrationTest") {
        description = "Runs Broxy CLI jar integration tests (STDIO + HTTP Streamable)."
        group = "verification"
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        shouldRunAfter(tasks.named("test"))
        dependsOn(shadowJarTask, ":test-mcp-server:installDist")
        useJUnitPlatform()
        val jarFile = shadowJarTask.flatMap { it.archiveFile }
        systemProperty("broxy.cliJar", jarFile.get().asFile.absolutePath)
        systemProperty("broxy.testMcpServerHome", testServerHome.get().asFile.absolutePath)
    }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    val timeout =
        if (name == "integrationTest") {
            "60s"
        } else {
            "5s"
        }
    systemProperty("junit.jupiter.execution.timeout.default", timeout)
}
