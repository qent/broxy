# Suppress warnings for optional integrations not bundled in the UI distribution.
-dontwarn io.qent.broxy.cloud.**
-dontwarn ch.qos.logback.**
-dontwarn io.github.oshai.kotlinlogging.logback.**
-dontwarn org.conscrypt.**
-dontwarn io.netty.internal.tcnative.**
-dontwarn reactor.blockhound.integration.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.commons.logging.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.jcraft.jzlib.**
-dontwarn net.jpountz.**
-dontwarn com.ning.compress.**
-dontwarn lzma.sdk.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.osgi.annotation.**
-dontwarn com.oracle.svm.**
-dontwarn org.bouncycastle.**
-dontwarn java.lang.foreign.**
-dontwarn java.lang.invoke.**
-dontwarn sun.misc.**
-dontwarn io.netty.pkitesting.**
-dontwarn reactor.blockhound.**
-dontwarn io.netty.util.internal.logging.**
-dontwarn javax.validation.**

# Keep Kotlin serialization metadata and MCP SDK types for runtime decoding.
-keepattributes *Annotation*,InnerClasses
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class ** {
    public static ** serializer(...);
}
-keepclassmembers class **$Companion {
    public ** serializer(...);
}
-keep @kotlinx.serialization.Serializable class ** { *; }
-keep @kotlinx.serialization.Serializable class **$* { *; }
-keep class io.modelcontextprotocol.kotlin.sdk.** { *; }

# Ktor CIO engine is loaded via ServiceLoader by bro-cloud; keep it to avoid runtime failures.
-keep class io.ktor.client.engine.cio.** { *; }
# Ktor serialization provider is loaded via ServiceLoader by bro-cloud.
-keep class io.ktor.serialization.kotlinx.** { *; }
# Ktor embedded server config loading may use ServiceLoader-backed config loaders.
-keep class io.ktor.server.config.** { *; }
# LangChain4j JDK HTTP factory is loaded via ServiceLoader by OpenAI/Anthropic models.
-keep class dev.langchain4j.http.client.jdk.** { *; }
# LangChain4j agentic runtime uses ServiceLoader for parameter name resolution and is invoked by agents.
-keep class dev.langchain4j.agentic.** { *; }
-keep class io.qent.broxy.agents.** { *; }
# LangChain4j OpenAI/Anthropic internal DTO/builders are deserialized via Jackson reflection.
-keep class dev.langchain4j.model.openai.internal.** { *; }
-keep class dev.langchain4j.model.anthropic.internal.** { *; }

# Keep JNI bridge entry points for macOS native notifications.
-keep class io.qent.broxy.ui.MacOsNotificationNativeBridge { *; }

# Strip debug logging from release builds.
-assumenosideeffects class io.qent.broxy.core.utils.Logger {
    void debug(java.lang.String);
}
