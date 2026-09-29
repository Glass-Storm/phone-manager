plugins {
    alias(libs.plugins.phonemanager.kotlin.library)
    alias(libs.plugins.phonemanager.ktlint)
}

dependencies {
    // `api`, not `implementation`: the ports, the state machines, and the Context
    // registry reference the model types in their public signatures, so any
    // consumer of `:core:domain` needs them on its own compile classpath
    // transitively.
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
}
