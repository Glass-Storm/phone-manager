plugins {
    alias(libs.plugins.phonemanager.android.library)
    alias(libs.plugins.phonemanager.ktlint)
}

android {
    // Path-derived namespace is `com.glassstorm.phonemanager.adapter`; declared
    // explicitly so the module keeps ownership of its own package identity.
    namespace = "com.glassstorm.phonemanager.adapter"

    defaultConfig {
        // R8 rules that MUST travel with this library into every consumer (the app).
        // Path is module-root relative: adapter/transport/grpc/r8-rules.pro.
        consumerProguardFiles("transport/grpc/r8-rules.pro")
    }
}

dependencies {
    // Adapters implement the domain ports; they depend on :core:domain and nothing else here.
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)

    // The hub transport adapter composes the `:service` hub implementation
    // (GrpcHubServer + the two gRPC services + AuthInterceptor). The dependency points
    // ONE WAY only: `:service` never sees `:adapter` (enforced by the build graph).
    implementation(project(":service"))

    // The netty-shaded NIO transport proven GO for Android in T6 (issues.md R1), plus
    // the gRPC/protobuf-lite runtime its generated services compile against. `:service`
    // declares these as `implementation`, so they are re-declared here for the adapter's
    // own compile classpath.
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.javalite)

    // Annotations referenced by the generated gRPC Java stubs.
    compileOnly(libs.annotations.api)

    // The Speechmatics realtime adapter speaks WebSocket (JWT exchange over HTTPS,
    // then binary/text frames). OkHttp is the transport the reference client proved.
    implementation(libs.okhttp)
}
