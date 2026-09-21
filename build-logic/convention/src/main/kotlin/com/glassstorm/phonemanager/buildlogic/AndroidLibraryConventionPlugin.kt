package com.glassstorm.phonemanager.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/**
 * Convention for Android library modules (`:adapter`, and every adapter/android
 * module the later modularization todos add).
 *
 * It bundles the shared Android config ([configureAndroidCommon]) + the shared
 * test battery ([addAndroidTestDependencies]), and applies the ktlint convention
 * so a Kotlin Android module is style-checked by default.
 *
 * The module keeps ownership of what is genuinely module-specific: its
 * `namespace` override (only if the path-derived default is wrong),
 * `consumerProguardFiles`, and its dependency list.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")
            pluginManager.apply("phonemanager.ktlint")

            configureAndroidCommon(extensions.getByType<LibraryExtension>())
            addAndroidTestDependencies()
        }
    }
}
