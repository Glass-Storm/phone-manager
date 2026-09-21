# R8 / ProGuard rules for the gRPC hub transport on Android.
#
# These travel into the consuming app through
# `android.defaultConfig.consumerProguardFiles(...)` in adapter/build.gradle.kts.
#
# WHY THEY ARE LOAD-BEARING (T6 spike verdict: GO, transport = netty-shaded):
# grpc-java picks its transport at runtime through a ServiceLoader lookup of
# `io.grpc.ServerProvider`, whose concrete implementation for this app is
# `io.grpc.netty.shaded.io.grpc.netty.NettyServerProvider` (listed in the shaded
# jar's `META-INF/services/io.grpc.ServerProvider`). R8 cannot see through a
# resource-file indirection, so without these keeps it renames/strips the
# provider and the hub dies at runtime with `ProviderNotFoundException`.
# The T7 evidence file records the break-then-restore proof.

# The shaded netty transport is reached only reflectively/ via ServiceLoader.
-keep class io.grpc.netty.shaded.** { *; }
-dontwarn io.grpc.netty.shaded.**

# gRPC core: providers, transport registry, and the ServerProvider SPI name.
-keep class io.grpc.** { *; }
-keepnames class io.grpc.ServerProvider

# Protobuf-lite generated messages are instantiated by generated parsers.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Optional dependencies the transport probes for but does not require on Android.
-dontwarn javax.naming.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn java.lang.invoke.**
