plugins {
    alias(libs.plugins.phonemanager.kotlin.library)
    alias(libs.plugins.phonemanager.ktlint)
}

// `:transport:grpc` is the ONE module allowed to depend on the FROZEN `:contract`.
// It owns the gRPC service implementations, the bearer-token interceptor, the
// netty-backed `HubServer` implementation, and the DTO <-> proto mapping. Keeping
// the wire format confined here is what stops `ecosys.v1.*` leaking into the
// domain's language (`:core:model` / `:core:domain`) or the use cases
// (`:core:service`). It is a pure Kotlin/JVM module: no Android imports, because
// the transport is supplied as a `ServerBuilder` factory and only `:app` (via the
// netty-shaded runtime it declares) binds a real socket on-device.
dependencies {
    // The generated `ecosys.v1` types and the grpc/protobuf-lite runtime they
    // extend are this module's public surface, so `api` (the deliberate single
    // contract edge decided in D3).
    api(project(":contract"))

    // The domain ports/DTOs the services and interceptor speak in their public
    // signatures, and the `TokenVerifier` port the interceptor consumes. `api`
    // because consumers of this module need them on their own compile classpath.
    api(project(":core:domain"))

    // The use-case implementations (`PairingServiceImpl`, `StreamServiceImpl`)
    // resolved through the Context registry. `implementation`: consumers construct
    // them in the composition root, not through this module's surface.
    implementation(project(":core:service"))

    // Channels and coroutine scopes for the bidi relay service.
    implementation(libs.kotlinx.coroutines.core)

    // The gRPC services and the netty-shaded NIO transport (proven GO for Android
    // in the T6 spike, issues.md R1). `grpc-api` arrives transitively via the
    // contract's `api(libs.grpc.stub)`.
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.javalite)

    // Annotations referenced by the generated gRPC Java stubs.
    compileOnly(libs.annotations.api)

    // Shared E2E kit (mockpeer runner + deterministic media generators). `:app`
    // cannot see these test sources, so the plain-JVM harness in this module and
    // the Robolectric app harness both consume it as a `testImplementation`.
    testImplementation(project(":testkit"))
    // In-process transport for the auth/relay suites, and the gRPC testing
    // utilities. The netty-shaded NIO transport is already an `implementation`
    // dependency, so the real-socket E2E suites resolve it on the test classpath.
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.grpc.testing)
}
