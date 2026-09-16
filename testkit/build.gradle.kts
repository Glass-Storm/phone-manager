plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM: no Android, no runtime dependencies. It only carries the
// bounded child-process runner and the deterministic media generators shared by
// the `:service` JVM harness and the `:app` Robolectric hub suite. Keeping it
// dependency-free is what lets `:app` (an Android module) consume it as a plain
// `testImplementation` project without dragging a runtime onto the APK.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
