plugins {
    alias(libs.plugins.phonemanager.kotlin.library)
    alias(libs.plugins.phonemanager.ktlint)
}

// Pure Kotlin/JVM: no Android, no runtime dependencies. It only carries the
// bounded child-process runner and the deterministic media generators shared by
// the `:service` JVM harness and the `:app` Robolectric hub suite. Keeping it
// dependency-free is what lets `:app` (an Android module) consume it as a plain
// `testImplementation` project without dragging a runtime onto the APK.
