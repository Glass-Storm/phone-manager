plugins {
    alias(libs.plugins.phonemanager.kotlin.library)
    alias(libs.plugins.phonemanager.ktlint)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
