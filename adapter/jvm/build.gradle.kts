plugins {
    alias(libs.plugins.phonemanager.kotlin.library)
    alias(libs.plugins.phonemanager.ktlint)
}

// Plain-JVM adapters: the STT engines (deterministic mock + Speechmatics), the
// discarding frame sink, and the in-memory device repository. This module has
// ZERO Android imports and does NOT depend on `:adapter:android`, so it builds
// and tests on a bare JVM.
//
// `SttFactory` selects the engine through the `AppConfig` PORT (branching on the
// domain `SttEngine`), which is exactly what keeps the backward edge to the
// Android config store out of this module.
dependencies {
    // The domain ports the adapters implement. `api` because this module's public
    // types (e.g. `SttFactory.createSttPort()` returning `SttPort`) expose them.
    api(project(":core:domain"))

    implementation(libs.kotlinx.coroutines.core)

    // The Speechmatics realtime adapter speaks WebSocket (JWT exchange over HTTPS,
    // then binary/text frames). OkHttp is the transport the reference client proved.
    implementation(libs.okhttp)

    // The Speechmatics wire format builds/parses JSON with `org.json`. On Android
    // that class ships in the platform; on a plain JVM it must come from the
    // artifact. Same API, so the wire code is unchanged.
    implementation(libs.org.json)
}
