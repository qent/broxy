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

# Keep Kotlin serialization metadata and MCP SDK types for runtime decoding.
-keepattributes *Annotation*,InnerClasses
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
