// Composite build that hosts this repository's Gradle convention plugins.
//
// It is included from the root `settings.gradle.kts` via
// `pluginManagement { includeBuild("build-logic") }`, so its plugins are
// resolved by id (`phonemanager.*`) without being published anywhere.
//
// It re-creates the ROOT version catalog here so plugin code and plugin build
// scripts read the same pins as the rest of the build — the catalog remains the
// single source of truth for every version (no version is duplicated).
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":convention")
