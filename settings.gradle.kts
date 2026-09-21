pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // The `phonemanager.*` convention plugins are provided by this composite build.
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "phone-manager"

include(":app")
// The adapters are split by ANDROID DEPENDENCE. `:adapter:jvm` is a plain Kotlin
// library (the STT engines, the discarding frame sink, the in-memory repository)
// with ZERO Android imports; it never depends on `:adapter:android`. `:adapter:android`
// holds the platform-backed adapters (SQLite, hotspot, NSD, the config store) and
// may depend on the domain ports only. `SttFactory` lives in `:adapter:jvm` and
// selects its engine through the `AppConfig` PORT, which is what makes the split
// possible without a backward edge.
include(":adapter:jvm")
include(":adapter:android")
// The hand-written domain DTOs (pure Kotlin data types, zero dependencies): the
// shared language spoken across every layer, extracted so consumers depend on
// the data alone rather than the whole `:core:domain` port module.
include(":core:model")
// The ports (interfaces), the two deterministic state machines, and the Context
// registry: the domain's language, pure Kotlin with zero implementation, zero
// framework annotations, and no dependency on the wire contract.
include(":core:domain")
// The use-case implementations (device, pairing, relay). Contract-free and
// gRPC-free by construction: it depends on `:core:domain` + `:core:model` +
// coroutines + the JDK crypto APIs, and on nothing else. The gRPC surface and
// the DTO<->proto mapping live in `:transport:grpc`, which is the only module
// allowed to see `ecosys.v1`.
include(":core:service")
// The gRPC boundary: the service implementations, the bearer-token interceptor,
// the netty-backed hub server, and the DTO <-> proto mapping. This is the ONE
// module allowed to depend on the FROZEN `:contract`, so the `ecosys.v1` wire
// format never leaks into the domain's language. Pure Kotlin/JVM (no Android).
include(":transport:grpc")
// The FROZEN `ecosys.v1` wire contract: its own module so the out-of-scope
// glasses app and Ubuntu daemon can consume/version/publish it independently of
// the hub implementation. It owns the .proto AND the protobuf/gRPC codegen.
include(":contract")
// Shared test-only JVM module: `:app` cannot see `:core:service`'s test sources, so
// the mockpeer process runner + the deterministic media generators live here and
// are consumed by both suites' `testImplementation`. It stays runtime-dependency-
// free so an Android module can consume it without dragging anything onto the APK.
include(":testing:testkit")
