plugins {
    alias(libs.plugins.phonemanager.kotlin.library)
    alias(libs.plugins.phonemanager.ktlint)
    // Dagger annotation processing for ServiceModule's `@Binds` declarations.
    alias(libs.plugins.phonemanager.ksp)
}

dependencies {
    // `api`, not `implementation`: the use-case implementations implement the
    // domain ports and reference the model DTOs in their public signatures, so a
    // consumer of `:core:service` needs both on its own compile classpath.
    api(project(":core:domain"))

    // Channels and coroutine scopes for the relay use-case. The convention plugin
    // deliberately does not add this (it is a MAIN dependency), so it is declared
    // here exactly once.
    implementation(libs.kotlinx.coroutines.core)

    // INTENTIONALLY ABSENT: `:contract`, `io.grpc`, protobuf, and every adapter.
    // This module is the use-case layer: it speaks DTOs only. The gRPC boundary
    // and the `ecosys.v1` <-> DTO mapping live in `:transport:grpc`, which is the
    // only module allowed to depend on the wire contract.
}
