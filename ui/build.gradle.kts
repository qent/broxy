import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.tasks.Copy
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvm("desktop")

    jvmToolchain(17)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":ui-adapter"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1-0.6.x-compat")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":headless-runtime"))
                implementation(project(":ui-adapter"))
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.mockito:mockito-core:5.21.0")
                implementation("org.mockito.kotlin:mockito-kotlin:6.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${property("coroutinesVersion")}")
                implementation(project(":core"))
                implementation(project(":ui-adapter"))
            }
        }
    }
}

val desktopCompilation =
    kotlin.targets
        .getByName("desktop")
        .compilations
        .getByName("main")

val javaToolchainService = extensions.getByType(JavaToolchainService::class.java)
val java17Launcher = javaToolchainService.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
val isMacOsHost = System.getProperty("os.name").contains("Mac", ignoreCase = true)
val macOsNativeArch =
    when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "arm64", "aarch64" -> "arm64"
        "x86_64", "amd64" -> "x86_64"
        else -> null
    }
val nativeBridgeSourceFile = layout.projectDirectory.file("src/desktopMain/native/macos/broxy_notifications_bridge.m")
val nativeBridgeResourcesDir = layout.buildDirectory.dir("generated/macosNotificationResources")
val nativeBridgeLibraryName = "libbroxy_notifications.dylib"

val buildMacOsNotificationBridge by
    tasks.registering {
        group = "build"
        description = "Builds macOS UserNotifications JNI bridge library."
        inputs.file(nativeBridgeSourceFile)
        outputs.dir(nativeBridgeResourcesDir)
        onlyIf {
            isMacOsHost && macOsNativeArch != null
        }

        doLast {
            val arch = requireNotNull(macOsNativeArch) { "Unsupported macOS arch: ${System.getProperty("os.arch")}" }
            val outputDir = nativeBridgeResourcesDir.get().dir("native/macos/$arch").asFile
            outputDir.mkdirs()

            val outputLibrary = outputDir.resolve(nativeBridgeLibraryName)
            val javaHome =
                java17Launcher
                    .get()
                    .metadata
                    .installationPath
                    .asFile
                    .absolutePath

            val execResult =
                providers.exec {
                    commandLine(
                        "xcrun",
                        "clang",
                        "-fobjc-arc",
                        "-dynamiclib",
                        "-mmacosx-version-min=10.14",
                        "-I$javaHome/include",
                        "-I$javaHome/include/darwin",
                        nativeBridgeSourceFile.asFile.absolutePath,
                        "-framework",
                        "Foundation",
                        "-framework",
                        "UserNotifications",
                        "-o",
                        outputLibrary.absolutePath,
                    )
                }
            execResult.result.get()
        }
    }

tasks.withType<Detekt>().configureEach {
    if (name == "detektMetadataMain") {
        classpath.setFrom(
            desktopCompilation.compileDependencyFiles,
            desktopCompilation.output.classesDirs,
        )
    }
}

tasks.named("detekt") {
    dependsOn("detektMetadataMain")
}

tasks.named<Copy>("processDesktopMainResources") {
    dependsOn(buildMacOsNotificationBridge)
    from(nativeBridgeResourcesDir)
}

tasks.named<Copy>("desktopProcessResources") {
    dependsOn(buildMacOsNotificationBridge)
    from(nativeBridgeResourcesDir)
}

compose.desktop {
    application {
        mainClass = "io.qent.broxy.ui.DesktopAppKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Broxy"
            vendor = "Qent"
            description = "Broxy: manage and route MCP servers, tools and presets across clients."
            includeAllModules = false
            modules(
                "java.instrument",
                "java.management",
                "java.net.http",
                "java.sql",
                "jdk.unsupported",
            )
            // Compose Desktop installers require MAJOR > 0
            val rawVersion = project.version.toString()
            val parts = rawVersion.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val sanitizedVersion =
                if (major <= 0) {
                    val tail = parts.drop(1).takeIf { it.isNotEmpty() }?.joinToString(".")
                    if (tail.isNullOrBlank()) "1" else "1.$tail"
                } else {
                    rawVersion
                }

            packageVersion = sanitizedVersion
            // Common icon files (optional, only set when present)
            val iconsDir = project.layout.projectDirectory.dir("src/desktopMain/resources/icons")
            val icns = iconsDir.file("broxy.icns").asFile
            val ico = iconsDir.file("broxy.ico").asFile
            val png = iconsDir.file("broxy.png").asFile

            macOS {
                packageVersion = sanitizedVersion
                dmgPackageVersion = sanitizedVersion
                bundleID = "io.qent.broxy"
                if (icns.exists()) {
                    iconFile.set(icns)
                }
            }
            windows {
                packageVersion = sanitizedVersion
                // Menu + shortcuts
                menuGroup = "Broxy"
                shortcut = true
                // Stable upgrade UUID for MSI upgrades
                upgradeUuid = "2b7e8e4c-0b20-4f7a-93b8-66f57d1f7f3a"
                if (ico.exists()) {
                    iconFile.set(ico)
                }
                // Windows signing can be configured later if needed
            }
            linux {
                packageVersion = sanitizedVersion
                // Maintainer/email for .deb control file
                debMaintainer = "Qent <support@broxy.run>"
                if (png.exists()) {
                    iconFile.set(png)
                }
            }
        }

        buildTypes {
            release {
                proguard {
                    optimize.set(false)
                    configurationFiles.from(
                        project.layout.projectDirectory.file("proguard-release.pro"),
                    )
                }
            }
        }
    }
}

tasks.withType<AbstractJLinkTask>().configureEach {
    freeArgs.add("--compress=2")
}
