package com.glassstorm.phonemanager.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Shared Android configuration for every Android module in this repository.
 *
 * The values here are the ones that were duplicated verbatim in `:adapter` and
 * `:app` before this refactor: compileSdk 36, minSdk 29, Java 17 source/target,
 * Kotlin JVM target 17, Android resources available to unit tests (Robolectric
 * needs them), and the `ExpiredTargetSdkVersion` lint waiver that the frozen
 * `targetSdk 29` requires (a documented, deliberate pin — see README/`.editorconfig`).
 *
 * Namespace is derived from the Gradle module path so a new module needs no
 * namespace line: `:adapter` -> `com.glassstorm.phonemanager.adapter`,
 * `:transport:grpc` -> `com.glassstorm.phonemanager.transport.grpc`. A module may
 * still override it in its own `android { }` block (the block runs after this
 * plugin applies), which `:app` does because its application id is not
 * path-derived.
 */
internal fun Project.configureAndroidCommon(extension: CommonExtension<*, *, *, *, *, *>) {
    extension.compileSdk = 36

    extension.defaultConfig {
        minSdk = 29
    }

    extension.compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    extension.namespace = namespaceForPath()

    extension.testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    extension.lint {
        // targetSdk 29 is a frozen, documented pin (the LocalOnlyHotspot API
        // surface the hub depends on). The warning is expected, not actionable.
        disable += "ExpiredTargetSdkVersion"
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

/** Shared test battery for the Android modules (JUnit4 + Truth + Turbine + Robolectric). */
internal fun Project.addAndroidTestDependencies() {
    dependencies {
        add("testImplementation", libs.library("junit"))
        add("testImplementation", libs.library("truth"))
        add("testImplementation", libs.library("turbine"))
        add("testImplementation", libs.library("robolectric"))
        add("testImplementation", libs.library("androidx-test-core"))
        add("testImplementation", libs.library("kotlinx-coroutines-test"))
    }
}

/**
 * `:adapter` -> `com.glassstorm.phonemanager.adapter`;
 * `:transport:grpc` -> `com.glassstorm.phonemanager.transport.grpc`.
 */
private fun Project.namespaceForPath(): String {
    val segments = path.split(':').filter { it.isNotEmpty() }
    return "com.glassstorm.phonemanager." + segments.joinToString(".")
}
