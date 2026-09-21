plugins {
    alias(libs.plugins.phonemanager.android.library)
    alias(libs.plugins.phonemanager.ktlint)
}

android {
    // Path-derived namespace is `com.glassstorm.phonemanager.adapter.android`;
    // declared explicitly so the module keeps ownership of its own package identity.
    namespace = "com.glassstorm.phonemanager.adapter.android"
}

// Android-only adapters: the SQLite device repository, the runtime config store,
// NSD discovery, and the LocalOnlyHotspot family. They depend on the domain ports
// and nothing else here — no edge back to `:adapter:jvm`.
dependencies {
    // The domain ports these adapters implement (e.g. `RuntimeConfigStore` is an
    // `AppConfig`, `SqliteDeviceRepository` is a `DeviceRepository`).
    api(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)
}
