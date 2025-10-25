plugins {
    kotlin("multiplatform")
    application
}

kotlin {
    jvm()

    jvmToolchain(17)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("com.github.ajalt.clikt:clikt:${property("cliktVersion")}")
            }
        }
    }
}

application {
    mainClass.set("io.qent.bro.cli.MainKt")
}
