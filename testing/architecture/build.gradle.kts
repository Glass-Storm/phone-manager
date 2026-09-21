plugins {
    alias(libs.plugins.phonemanager.kotlin.library)
    alias(libs.plugins.phonemanager.ktlint)
}

// The architecture gate. Unlike every other module this one does not depend on
// the code it tests: Konsist PARSES the other modules' Kotlin sources from disk,
// using the repository layout rather than a Gradle project edge. That is exactly
// why the boundary laws can be enforced here — a rule like "nobody imports
// `app`" has no Gradle edge to express it.
dependencies {
    testImplementation(libs.konsist)
}

// Konsist reads the OTHER modules' `src` trees from disk, so Gradle's normal
// input tracking does not know they affect this task. Without this the boundary
// gate would report UP-TO-DATE after someone edits an unrelated module — a
// violation could sit undetected until CI. Declaring the governed source trees
// as inputs makes the gate re-run exactly when a boundary could have changed.
val governedSources =
    listOf(
        "app",
        "contract",
        "core/model",
        "core/domain",
        "core/service",
        "transport/grpc",
        "adapter/jvm",
        "adapter/android",
        "testing/testkit",
        "testing/architecture",
    ).map { rootProject.file("$it/src") }

tasks.withType<Test>().configureEach {
    inputs.files(governedSources).withPropertyName("governedModuleSources")
}
