-dontoptimize
-dontshrink
-dontnote
-dontwarn

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keep class io.qent.broxy.cloud.BroCloudRemoteConnectorFactory { *; }
-keep class io.qent.broxy.cloud.api.** { *; }
