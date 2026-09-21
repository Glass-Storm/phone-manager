plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Lumo UI is a code-generator plugin resolved from Maven Central (not the Gradle Plugin Portal).
    alias(libs.plugins.lumo) apply false
    // ktlint is declared here so the plugin lands on the build classpath once; it
    // is applied by the `phonemanager.ktlint` convention plugin (which every Kotlin
    // module applies, directly or transitively), together with its shared
    // generated-code / vendored-Lumo exclusions.
    alias(libs.plugins.ktlint) apply false
    // KSP (Kotlin Symbol Processing) drives Dagger's annotation processing. Declared
    // here so the plugin is on the build classpath once; modules enable it through
    // the `phonemanager.ksp` convention plugin.
    alias(libs.plugins.ksp) apply false

    // This repository's convention plugins (from the `build-logic` composite build).
    // Declared `apply false` so they are on the build classpath and their ids
    // resolve; each module applies the ones it needs.
    alias(libs.plugins.phonemanager.kotlin.library) apply false
    alias(libs.plugins.phonemanager.android.library) apply false
    alias(libs.plugins.phonemanager.android.application) apply false
    alias(libs.plugins.phonemanager.ktlint) apply false
}

// The T18 E2E gate: one entry point that runs BOTH full-scenario harnesses — the
// plain-JVM hub (`:service`) and the Robolectric-hosted app hub (`:app`). It is a
// thin alias, never a reimplementation, so `./gradlew e2e` and the individual test
// tasks can never diverge.
tasks.register("e2e") {
    group = "verification"
    description = "End-to-end mockpeer integration suites (JVM harness + Robolectric app hub)"
    dependsOn(":service:test", ":app:testDebugUnitTest")
}
