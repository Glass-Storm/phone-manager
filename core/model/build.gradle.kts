plugins {
    alias(libs.plugins.phonemanager.kotlin.library)
    alias(libs.plugins.phonemanager.ktlint)
}

// Pure Kotlin/JVM, zero dependencies: the hand-written domain DTOs are plain data
// types (data classes, enums, sealed interfaces) with no coroutines, Android,
// gRPC, or protobuf. Keeping this module dependency-free is the point — every
// layer can speak the shared model without inheriting a transitive runtime, and
// `:core:domain` re-exports it via `api` so port/DTO signatures resolve for consumers.
