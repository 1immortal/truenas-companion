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
