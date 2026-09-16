pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "phone-manager"

include(":app")
include(":domain", ":service", ":adapter")
// Shared test-only JVM module: `:app` cannot see `:service`'s test sources, so the
// mockpeer process runner + the deterministic media generators live here and are
// consumed by both suites' `testImplementation`.
include(":testkit")
