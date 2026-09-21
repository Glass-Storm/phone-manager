plugins {
    alias(libs.plugins.phonemanager.kotlin.library)
    alias(libs.plugins.phonemanager.ktlint)
}

// Test support shared by the `:transport:grpc` JVM harness and the `:app`
// Robolectric hub suite: the bounded child-process runner, the deterministic
// media generators, and the in-memory `DeviceRepository` test double. Its only
// dependency is `:core:domain`, because `MemoryDeviceRepository` implements the
// `DeviceRepository` port; nothing here reaches a platform API, so `:app` can
// consume it as a plain `testImplementation` project without a runtime edge.
dependencies {
    implementation(project(":core:domain"))
}
