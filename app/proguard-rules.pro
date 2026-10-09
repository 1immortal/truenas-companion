# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 1.7.1: TunnelManager also reads GoBackend's static service future by name as a readiness signal.
-keepclassmembers class com.wireguard.android.backend.GoBackend {
    static java.util.concurrent.CompletableFuture vpnService;
}

# 1.8.0 (security review): release builds drop verbose/debug/info logging (server names, routes, job details).
# Warnings and errors stay for crash reports the user chooses to share.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
