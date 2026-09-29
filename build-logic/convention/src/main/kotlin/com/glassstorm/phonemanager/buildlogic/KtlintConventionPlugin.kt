package com.glassstorm.phonemanager.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileTreeElement
import org.gradle.api.specs.Spec
import org.gradle.kotlin.dsl.configure
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * Applies ktlint and its SHARED exclusions to a module.
 *
 * The exclusions were previously configured once in the root `build.gradle.kts`
 * `subprojects { plugins.withId("org.jlleitschuh.gradle.ktlint") { ... } }` block.
 * They move here verbatim, so a module that applies `phonemanager.ktlint` (which
 * every Kotlin module does, transitively through the library/app plugins) gets
 * exactly the same filter behaviour.
 *
 * Two things are excluded, and only these two:
 *
 *  1. Generated protobuf/grpc sources under a module's `build/generated`
 *     directory. Machine output, not authored code. Ant-style globs that contain
 *     a `generated` segment do NOT match them (the plugin's file tree is
 *     multi-root and patterns are root-relative), so the absolute
 *     `/build/generated/` path fragment is matched instead.
 *  2. The vendored Lumo UI kit under `app/.../ui/` — generated third-party code;
 *     reformatting it would fork it from its generator. Hand-written screens and
 *     `*ViewModel.kt` / `*State.kt` files stay IN scope and are enforced.
 *
 * The `.editorconfig` is untouched by this plugin: every standard rule,
 * including the naming rules, stays enabled.
 */
class KtlintConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")

            extensions.configure<KtlintExtension> {
                filter {
                    exclude(
                        Spec<FileTreeElement> {
                            it.file.path.contains("/build/generated/")
                        },
                    )
                    exclude(
                        "**/ui/Theme.kt",
                        "**/ui/Color.kt",
                        "**/ui/Typography.kt",
                        "**/ui/components/**",
                        "**/ui/foundation/**",
                    )
                }
            }
        }
    }
}
