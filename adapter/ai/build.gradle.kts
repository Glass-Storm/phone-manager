plugins {
    alias(libs.plugins.phonemanager.android.library)
    alias(libs.plugins.phonemanager.ktlint)
}

// The ai-android INTEGRATION module. It holds the DEVICE implementations of the
// gesture / speech+diarization / TTS / enrollment ports, backed by the pinned
// `hk.edu.hkmu:*` composite build declared in settings.gradle.kts.
//
// ISOLATION IS THE POINT. ai-android (MediaPipe) uses FULL protobuf-java; the hub's
// `:contract`/`:transport:grpc` use protobuf-lite. If this module were a dependency of
// `:app`, both protobuf runtimes would land on one classpath and the duplicate
// `com.google.protobuf.*` classes would break dexing. Keeping it off the `:app` graph
// means:
//   * `:app:assembleDebug` stays green (the JVM fakes in `:adapter:jvm` are the default
//     bindings), and
//   * the engine is compiled and packaged ONLY for the arm64 on-device build.
// Adopting it on a device is a deliberate change: add the edge and swap the binding.
// See README "Provisioning the ai-android engine" and the T4 evidence file.
android {
    // Path-derived namespace would already be `...adapter.ai`; declared explicitly so the
    // module owns its package identity like `:adapter:android` does.
    namespace = "com.glassstorm.phonemanager.adapter.ai"

    defaultConfig {
        // ai-android's MediaPipe gesture/diarization native ships arm64-v8a ONLY. Filtering
        // here keeps a stray x86 build from producing an APK whose engine fails to load.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
}

dependencies {
    // The domain ports these adapters implement. `api` because the constructor signatures
    // and returned types are the domain's own (`GesturePort`, `SpeechPort`, ...).
    api(project(":core:domain"))

    // ai-android is consumed as a PINNED COMPOSITE BUILD (see settings.gradle.kts): the
    // local checkout at the pinned commit is substituted for these coordinates, so there is
    // no released artifact to depend on and no drifting engine. The versions are nominal —
    // the substitution matches group:name, not the version.
    implementation("hk.edu.hkmu:core:0.0.1-alpha.1")
    implementation("hk.edu.hkmu:sign:0.0.1-alpha.1")
    implementation("hk.edu.hkmu:asr:0.0.1-alpha.1")
    implementation("hk.edu.hkmu:diarize:0.0.1-alpha.1")
    implementation("hk.edu.hkmu:tts:0.0.1-alpha.1")

    implementation(libs.kotlinx.coroutines.android)
}
