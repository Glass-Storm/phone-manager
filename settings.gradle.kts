pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // The `phonemanager.*` convention plugins are provided by this composite build.
    includeBuild("build-logic")
}

// The ai-android engine checkout consumed as a pinned composite build (see below).
// Declared before `dependencyResolutionManagement` because its vendored sherpa-onnx
// AAR must be resolvable from this build's shared repositories too (see the flatDir).
val aiAndroidDir = file("../../ai-android")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ai-android's `:asr` / `:diarize` / `:tts` resolve their vendored sherpa-onnx
        // AAR from a flatDir repository declared in AI-ANDROID's settings. A composite
        // build does not carry that repository over to the CONSUMING build's dependency
        // resolution, so `:adapter:ai`'s runtime classpath cannot find it unless it is
        // re-declared here. Only the empty-group `sherpa-onnx-*` module resolves here; a
        // missing directory is harmless (and the checkout check below fails loudly first).
        flatDir { dirs(aiAndroidDir.resolve("libs")) }
    }
}

rootProject.name = "phone-manager"

// ---------------------------------------------------------------------------
// ai-android: the on-device inference engine (gesture / ASR / diarization / TTS).
//
// It ships NO stable tag, so there is no released artifact to depend on. It is
// consumed as a PINNED COMPOSITE BUILD: the local checkout is substituted for the
// `hk.edu.hkmu:*` coordinates, and the exact commit is enforced below so a build
// can never silently compile against a drifting engine.
//
// The ai-android engine uses FULL protobuf-java (MediaPipe) while the hub's
// `:contract` uses protobuf-lite. To keep that clash OFF the hub's default
// classpath, the adapter lives in the isolated `:adapter:ai` module, which the
// `:app` graph does NOT depend on by default (the JVM fakes are the default
// bindings). See `:adapter:ai`'s build file for the resolution.
// ---------------------------------------------------------------------------
val pinnedAiAndroidCommit = "c0644eada44c242604e07546ff93aef80d9563f8"
check(aiAndroidDir.isDirectory) {
    "ai-android composite build not found at ${aiAndroidDir.absolutePath}. " +
        "Clone the engine there (see README 'Provisioning the ai-android engine')."
}
val aiAndroidHead =
    providers.exec {
        commandLine("git", "-C", aiAndroidDir.absolutePath, "rev-parse", "HEAD")
    }.standardOutput.asText.get().trim()
check(aiAndroidHead == pinnedAiAndroidCommit) {
    "ai-android is pinned to $pinnedAiAndroidCommit but the checkout at " +
        "${aiAndroidDir.absolutePath} is at $aiAndroidHead. " +
        "Check out the pinned commit (git -C ${aiAndroidDir.absolutePath} checkout $pinnedAiAndroidCommit) " +
        "or bump `pinnedAiAndroidCommit` in settings.gradle.kts deliberately."
}
includeBuild(aiAndroidDir) {
    dependencySubstitution {
        substitute(module("hk.edu.hkmu:core")).using(project(":core"))
        substitute(module("hk.edu.hkmu:sign")).using(project(":sign"))
        substitute(module("hk.edu.hkmu:asr")).using(project(":asr"))
        substitute(module("hk.edu.hkmu:diarize")).using(project(":diarize"))
        substitute(module("hk.edu.hkmu:tts")).using(project(":tts"))
    }
}

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
// The ai-android INTEGRATION module: the on-device implementations of the gesture /
// speech+diarization / TTS ports backed by the pinned `hk.edu.hkmu:*` composite build
// above. It is a SEPARATE, ISOLATED module on purpose:
//
//  * ai-android (MediaPipe) uses FULL protobuf-java, while the hub's `:contract` and
//    `:transport:grpc` use protobuf-lite. Putting the engine on `:app`'s classpath would
//    put both on one runtime classpath and the duplicate `com.google.protobuf.*` classes
//    would fail dexing. Keeping the adapter in its own module, off the `:app` graph,
//    means the hub keeps building and the JVM fakes stay the default bindings.
//  * ai-android's gesture/diarization native is arm64-v8a ONLY, so a JVM/unit build can
//    never load it. `:app` therefore binds the `:adapter:jvm` fakes; adopting this module
//    on a device is a deliberate graph change (see README "Provisioning the ai-android
//    engine").
include(":adapter:ai")
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
// The architecture gate: Konsist rules that fail the build when one of the
// module-boundary laws is violated (contract isolation, gRPC-free use-cases,
// annotation-free domain, nobody importing `app`). Pure Kotlin/JVM — it reads
// the other modules' SOURCES from disk, so it needs no Android or project edge.
include(":testing:architecture")
