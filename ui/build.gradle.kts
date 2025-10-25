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
            // Compose Desktop installers require MAJOR > 0
            val rawVersion = project.version.toString()
            val parts = rawVersion.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val sanitizedVersion = if (major <= 0) {
                val tail = parts.drop(1).takeIf { it.isNotEmpty() }?.joinToString(".")
                if (tail.isNullOrBlank()) "1" else "1.$tail"
            } else rawVersion

            packageVersion = sanitizedVersion
            macOS {
                packageVersion = sanitizedVersion
                dmgPackageVersion = sanitizedVersion
            }
            windows {
                packageVersion = sanitizedVersion
            }
            linux {
                packageVersion = sanitizedVersion
            }
        }
    }
}
