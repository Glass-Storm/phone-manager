plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Lumo UI is a code-generator plugin resolved from Maven Central (not the Gradle Plugin Portal).
    alias(libs.plugins.lumo) apply false
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
