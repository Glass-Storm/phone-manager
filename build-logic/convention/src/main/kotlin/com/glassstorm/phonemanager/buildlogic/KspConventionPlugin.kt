package com.glassstorm.phonemanager.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Wires the KSP2 + Dagger annotation-processing toolchain into a module.
 *
 * Applies the KSP plugin and adds the Dagger runtime as `implementation` plus the
 * Dagger processor on the `ksp` configuration. It deliberately adds NO bindings,
 * `@Module`, `@Inject`, or `@Component`: this plugin only makes the processors
 * available, so a module compiles identically before and after applying it.
 *
 * A module must apply its Kotlin plugin (JVM or Android) BEFORE this one, so the
 * `ksp` configuration exists and the KSP plugin sees a Kotlin target. Modules do
 * this by applying `phonemanager.ksp` last.
 *
 * Hilt is intentionally absent: its Gradle plugin hard-fails on `kotlin("jvm")`
 * modules, and `:core:domain`/`:service` are pure JVM. Dagger's annotations are enough.
 */
class KspConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")

            dependencies {
                add("implementation", libs.library("dagger"))
                add("ksp", libs.library("dagger-compiler"))
            }
        }
    }
}
