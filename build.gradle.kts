plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Lumo UI is a code-generator plugin resolved from Maven Central (not the Gradle Plugin Portal).
    alias(libs.plugins.lumo) apply false
}
