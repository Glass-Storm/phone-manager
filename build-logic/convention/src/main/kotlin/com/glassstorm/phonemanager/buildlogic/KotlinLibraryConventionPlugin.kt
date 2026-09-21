package com.glassstorm.phonemanager.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Convention for pure Kotlin/JVM library modules (`:domain`, `:service`, `:testkit`).
 *
 * Replaces the five-copy boilerplate that used to live in every JVM module:
 * `kotlin("jvm")` + `java-library`, Java 17 source/target, the Kotlin JVM target
 * 17, and the shared JUnit/Truth/Turbine/coroutines-test test battery.
 *
 * Deliberately does NOT add `kotlinx-coroutines-core`: that is a MAIN dependency
 * and `:testkit` is intentionally dependency-free (it is consumed by the Android
 * app's test classpath, so a runtime edge there must not appear by accident).
 * `:domain` and `:service` declare it themselves — one line each.
 *
 * JVM 17 is configured WITHOUT toolchains, exactly as before: the launcher JVM is
 * already 21 and a toolchain would trigger a JDK download.
 */
class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("java-library")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }

            dependencies {
                add("testImplementation", libs.library("kotlin-test"))
                add("testImplementation", libs.library("junit"))
                add("testImplementation", libs.library("truth"))
                add("testImplementation", libs.library("turbine"))
                add("testImplementation", libs.library("kotlinx-coroutines-test"))
            }
        }
    }
}
