plugins {
    alias(libs.plugins.phonemanager.android.application)
    alias(libs.plugins.phonemanager.ktlint)
    alias(libs.plugins.lumo)
    // Dagger annotation processing: this module declares the @Component, so the
    // processor must run here to generate DaggerAppComponent.
    alias(libs.plugins.phonemanager.ksp)
}

android {
    namespace = "com.glassstorm.phonemanager"

    defaultConfig {
        applicationId = "com.glassstorm.phonemanager"
        versionCode = 1
        versionName = "1.0-dev"
    }

    compileOptions {
        // Required by the gRPC/netty transport: it uses java.util.concurrent.Flow,
        // java.time and friends that only exist from API 33 up, while the app's
        // minSdk is 29.
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        release {
            // The whole point of the T7 gate: the hub's runtime-discovered gRPC
            // transport must survive shrinking. The keep rules now live with the
            // transport they protect in `:transport:grpc`; because that module is a
            // pure Kotlin/JVM library it cannot use AGP's `consumerProguardFiles`,
            // so the app references the rule file explicitly here. Debug stays
            // unminified so tests stay fast and readable.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                rootProject.file("transport/grpc/r8-rules.pro"),
            )
        }
    }
}

dependencies {
    implementation(libs.core.ktx)

    // Composition root: :app owns the wiring, so it may see both the services and the adapters.
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:service"))
    implementation(project(":transport:grpc"))
    // The adapters are split by Android dependence: `:adapter:jvm` (STT engines,
    // memory repo, frame sink) and `:adapter:android` (SQLite, hotspot, NSD,
    // config store). The composition root wires both.
    implementation(project(":adapter:jvm"))
    implementation(project(":adapter:android"))

    // The GO gRPC transport (T6 spike verdict: netty-shaded, IPv4 explicit, plaintext).
    // Declared here as well because every dependency above is `implementation`, so
    // nothing reaches :app's compile classpath transitively.
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.javalite)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.ripple)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(composeBom)
    // Shared E2E kit (mockpeer runner + deterministic media generators), used by
    // FullE2eTest to drive the real Go peer against the Robolectric-hosted hub.
    testImplementation(project(":testing:testkit"))
    testImplementation(libs.compose.ui.test.junit4)
    // `org.json` is already on the `:app` test classpath via `:adapter:jvm`, but
    // the T14 harness reads the Pi token cache with it directly, so depend on it
    // explicitly rather than leaning on another module's transitive edge.
    testImplementation(libs.org.json)
}

// ---------------------------------------------------------------------------
// T14: drive the REAL Python `ecosys_pi` CLI against the REAL Robolectric app
// hub. This is deliberately OPT-IN and lives in its OWN task because it depends
// on host-only prerequisites (the sibling Pi repo, `uv`, CPython 3.11+) which
// the plain hub build must NEVER require:
//
//  * `testDebugUnitTest` (and therefore `./gradlew test` and `e2e`) EXCLUDES the
//    T14 class, so a host without `uv` still gets a fully green hub suite;
//  * `:app:ecosysPiE2e` runs ONLY that class, on the exact same compiled test
//    classes and classpath as `testDebugUnitTest` (one source set, so the two
//    can never drift).
//
// The class itself calls `Assumptions.assumeTrue` with a NAMED reason when the
// prerequisites are missing, so the dedicated task SKIPS loudly — it never
// silently passes and never fails the hub suite.
// ---------------------------------------------------------------------------
val ecosysPiE2eTestClass = "com.glassstorm.phonemanager.EcosysPiE2eTest"

// Every Test task EXCEPT the dedicated opt-in E2E excludes the Python test;
// ecosysPiE2e must run ONLY that class.
tasks.withType<Test>().configureEach {
    if (name != "ecosysPiE2e") {
        filter { excludeTestsMatching(ecosysPiE2eTestClass) }
    }
}

val ecosysPiE2e =
    tasks.register<Test>("ecosysPiE2e") {
        group = "verification"
        description = "T14 e2e: drive the Python ecosys_pi CLI against the real app hub (opt-in, skips loudly without uv)."
        filter {
            includeTestsMatching(ecosysPiE2eTestClass)
        }
        testLogging {
            // The raw CLI stdout/stderr per case IS the evidence, so surface it.
            showStandardStreams = true
        }
        reports {
            junitXml.required.set(true)
            html.required.set(true)
        }
    }

// AGP creates `testDebugUnitTest` only during final evaluation, so the inputs are
// copied in `projectsEvaluated`: the dedicated task then tests the SAME compiled
// classes and classpath as the hub suite (one source set, no drift). The all-tests
// task itself is never run — only its producer dependencies.
gradle.projectsEvaluated {
    val unitTest = tasks.named<Test>("testDebugUnitTest").get()
    ecosysPiE2e.configure {
        testClassesDirs = unitTest.testClassesDirs
        classpath = unitTest.classpath
        jvmArgs = unitTest.jvmArgs
        systemProperties = unitTest.systemProperties
        dependsOn(unitTest.taskDependencies.getDependencies(unitTest))
    }
}

// Attach the Python E2E to `check` only. It must stay OUT of `test`,
// `testDebugUnitTest` and `e2e` (those stay green on a host without the Pi repo),
// but leaving it attached to NOTHING let it rot; `check` is the one lifecycle task
// that may assume the full host, and the task itself skips loudly when it cannot.
tasks.matching { it.name == "check" }.configureEach { dependsOn(ecosysPiE2e) }
