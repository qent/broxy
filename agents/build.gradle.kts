plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutinesVersion")}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serializationVersion")}")

    implementation("dev.langchain4j:langchain4j:${property("langchain4jVersion")}")
    implementation("dev.langchain4j:langchain4j-agentic:${property("langchain4jAgenticVersion")}")
    implementation("dev.langchain4j:langchain4j-open-ai:${property("langchain4jVersion")}")
    implementation("dev.langchain4j:langchain4j-anthropic:${property("langchain4jVersion")}")
    implementation("dev.langchain4j:langchain4j-http-client-jdk:${property("langchain4jVersion")}")

    implementation("com.cronutils:cron-utils:${property("cronUtilsVersion")}")
    implementation("org.yaml:snakeyaml:${property("snakeYamlVersion")}")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${property("coroutinesVersion")}")
    testImplementation("org.mockito:mockito-core:5.21.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.1.0")
}
