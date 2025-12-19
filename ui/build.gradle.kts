import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
                implementation(project(":ui-adapter"))
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.mockito:mockito-core:5.14.1")
                implementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${property("coroutinesVersion")}")
                implementation(project(":core"))
                implementation(project(":ui-adapter"))
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.qent.broxy.ui.DesktopAppKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "broxy"
            vendor = "Qent"
            description = "broxy: manage and route MCP servers, tools and presets across clients."
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
                menuGroup = "broxy"
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
                debMaintainer = "Qent <support@broxy.example.com>"
                if (png.exists()) {
                    iconFile.set(png)
                }
            }
        }
    }
}
