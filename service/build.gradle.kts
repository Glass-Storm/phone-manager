plugins {
    alias(libs.plugins.phonemanager.kotlin.library)
    alias(libs.plugins.phonemanager.ktlint)
    // KSP2 + Dagger annotation processing. T15 introduces the bindings; this only
    // wires the toolchain, so the module compiles unchanged.
    alias(libs.plugins.phonemanager.ksp)
}

dependencies {
    // Hexagonal boundary: :service may see the domain ports/DTOs ONLY.
    // It MUST NEVER gain a dependency edge to :adapter (enforced by the build graph).
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)

    // The FROZEN wire contract and the codegen that owns it. `api`, not
    // `implementation`: the gRPC services here and their consumers must see the
    // generated `ecosys.v1` types AND the grpc/protobuf-lite runtime they extend
    // transitively. The protobuf codegen itself now lives in `:contract`.
    api(project(":contract"))

    // Shared E2E kit (mockpeer runner + deterministic media generators). `:app`
    // cannot see these test sources, so they live in a plain JVM module.
    testImplementation(project(":testkit"))
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.grpc.testing)
    // T6 transport spike: the REAL netty-shaded NIO transport. This is the
    // transport candidate for Android (issues.md R1) and the one the GO/NO-GO
    // verdict is about; it is test-scope only here because :app supplies its own
    // copy in T7.
    testImplementation(libs.grpc.netty.shaded)
}
