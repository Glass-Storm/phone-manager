plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.lumo)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.glassstorm.phonemanager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.glassstorm.phonemanager"
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "1.0-dev"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by the gRPC/netty transport: it uses java.util.concurrent.Flow,
        // java.time and friends that only exist from API 33 up, while the app's
        // minSdk is 29.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            // The whole point of the T7 gate: the hub's runtime-discovered gRPC
            // transport must survive shrinking, and the adapter's consumer rules
            // travel into THIS build. Debug stays unminified so tests stay fast
            // and readable.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.core.ktx)

    // Composition root: :app owns the wiring, so it may see both the services and the adapters.
    implementation(project(":domain"))
    implementation(project(":service"))
    implementation(project(":adapter"))

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
    testImplementation(project(":testkit"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.compose.ui.test.junit4)
}
