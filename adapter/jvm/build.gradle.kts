plugins {
    alias(libs.plugins.phonemanager.kotlin.library)
    alias(libs.plugins.phonemanager.ktlint)
    // Dagger annotation processing for JvmAdapterModule's providers.
    alias(libs.plugins.phonemanager.ksp)
}

// Plain-JVM adapters: the deterministic gesture/speech/TTS/enrollment fakes, the
// discarding frame sink, and the in-memory device repository. This module has
// ZERO Android imports and does NOT depend on `:adapter:android`, so it builds
// and tests on a bare JVM. The former cloud STT engines and their factory were
// retired with the cloud STT path; the hub runs on-device speech only.
dependencies {
    // The domain ports the adapters implement. `api` because this module's public
    // types expose them.
    api(project(":core:domain"))

    implementation(libs.kotlinx.coroutines.core)
}
