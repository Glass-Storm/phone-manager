plugins {
    alias(libs.plugins.phonemanager.android.library)
    alias(libs.plugins.phonemanager.ktlint)
}

android {
    // Path-derived namespace is `com.glassstorm.phonemanager.adapter`; declared
    // explicitly so the module keeps ownership of its own package identity.
    namespace = "com.glassstorm.phonemanager.adapter"
}

dependencies {
    // Adapters implement the domain ports; they depend on :core:domain and nothing else here.
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)

    // The gRPC transport and its R8 keep rules moved to `:transport:grpc` (T12).
    // `:adapter` is now pure Android adapters: SQLite, hotspot, NSD discovery, the
    // config store, and the STT engines.

    // The Speechmatics realtime adapter speaks WebSocket (JWT exchange over HTTPS,
    // then binary/text frames). OkHttp is the transport the reference client proved.
    implementation(libs.okhttp)
}
