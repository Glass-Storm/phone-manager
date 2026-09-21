plugins {
    alias(libs.plugins.phonemanager.android.application)
    alias(libs.plugins.phonemanager.ktlint)
    alias(libs.plugins.lumo)
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
}
