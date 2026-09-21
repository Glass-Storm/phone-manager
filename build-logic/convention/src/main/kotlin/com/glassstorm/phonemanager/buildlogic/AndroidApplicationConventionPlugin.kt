package com.glassstorm.phonemanager.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/**
 * Convention for the Android application module (`:app`).
 *
 * Applies the shared Android config ([configureAndroidCommon], which includes the
 * frozen `targetSdk 29` lint waiver and the JVM 17 target), the shared test
 * battery, Compose support (`buildFeatures.compose = true` plus the Kotlin
 * Compose compiler plugin), and ktlint.
 *
 * Kept out of here on purpose, because they are `:app`-specific policy rather
 * than repo-wide convention: `applicationId` / `versionCode` / `versionName`, the
 * release build type (R8 minify + shrink + proguard files), core-library
 * desugaring (only the app packages the netty transport), and the Compose / Lumo
 * dependencies themselves.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.android")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("phonemanager.ktlint")

            configureAndroidCommon(extensions.getByType<ApplicationExtension>())

            extensions.configure<ApplicationExtension> {
                buildFeatures {
                    compose = true
                }
                defaultConfig {
                    targetSdk = 29
                }
            }

            addAndroidTestDependencies()
        }
    }
}
