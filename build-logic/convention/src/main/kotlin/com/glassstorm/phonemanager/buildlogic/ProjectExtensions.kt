package com.glassstorm.phonemanager.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/**
 * The root `libs` version catalog, re-exposed to plugin source.
 *
 * Plugin classes cannot use the Gradle-generated `libs` accessors (those exist
 * only in build scripts), so the catalog is looked up by name at runtime and
 * dependency coordinates are pulled from it. This keeps `gradle/libs.versions.toml`
 * the single source of truth for versions inside the convention plugins too.
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Reads a library alias (e.g. `kotlin-test`) from the catalog, failing loudly if absent. */
internal fun VersionCatalog.library(alias: String) = findLibrary(alias).get()
