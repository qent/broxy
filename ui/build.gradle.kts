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
                implementation(project(":core"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.qent.bro.ui.DesktopAppKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "mcp-proxy"
            vendor = "Qent"
            description = "MCP Proxy: manage and route MCP servers, tools and presets across clients."
            // Compose Desktop installers require MAJOR > 0
            val rawVersion = project.version.toString()
            val parts = rawVersion.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val sanitizedVersion = if (major <= 0) {
                val tail = parts.drop(1).takeIf { it.isNotEmpty() }?.joinToString(".")
                if (tail.isNullOrBlank()) "1" else "1.$tail"
            } else rawVersion

            packageVersion = sanitizedVersion
            // Common icon files (optional, only set when present)
            val iconsDir = project.layout.projectDirectory.dir("src/desktopMain/resources/icons")
            val icns = iconsDir.file("mcp-proxy.icns").asFile
            val ico = iconsDir.file("mcp-proxy.ico").asFile
            val png = iconsDir.file("mcp-proxy.png").asFile

            macOS {
                packageVersion = sanitizedVersion
                dmgPackageVersion = sanitizedVersion
                bundleID = "io.qent.bro.mcpproxy"
                if (icns.exists()) {
                    iconFile.set(icns)
                }
            }
            windows {
                packageVersion = sanitizedVersion
                // Menu + shortcuts
                menuGroup = "MCP Proxy"
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
                debMaintainer = "Qent <support@mcp-proxy.example.com>"
                if (png.exists()) {
                    iconFile.set(png)
                }
            }
        }
    }
}
