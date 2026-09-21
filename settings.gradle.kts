pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // The `phonemanager.*` convention plugins are provided by this composite build.
    includeBuild("build-logic")
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
// The FROZEN `ecosys.v1` wire contract: its own module so the out-of-scope
// glasses app and Ubuntu daemon can consume/version/publish it independently of
// the hub implementation. It owns the .proto AND the protobuf/gRPC codegen.
include(":contract")
// Shared test-only JVM module: `:app` cannot see `:service`'s test sources, so the
// mockpeer process runner + the deterministic media generators live here and are
// consumed by both suites' `testImplementation`.
include(":testkit")
