plugins {
    alias(libs.plugins.phonemanager.kotlin.library)
    alias(libs.plugins.phonemanager.ktlint)
}

dependencies {
    // `api`, not `implementation`: `:domain`'s public ports and Context registry
    // reference the model types, so any consumer of `:domain` needs them on its
    // own compile classpath transitively.
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
}
