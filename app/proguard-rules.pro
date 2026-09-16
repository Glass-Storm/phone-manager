# App-local R8 rules.
#
# The gRPC/transport keeps live in the `:adapter` library and reach this build
# automatically through `consumerProguardFiles` (adapter/transport/grpc/r8-rules.pro).
# Keep app-specific rules here so the transport rules stay owned by the module that
# owns the transport.

# The foreground service is referenced from the manifest by name; R8 keeps manifest
# entries implicitly, but the explicit keep documents the intent and pins the name.
-keep class com.glassstorm.phonemanager.HubForegroundService { *; }
