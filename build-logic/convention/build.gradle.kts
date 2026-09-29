// The convention-plugin project. `kotlin-dsl` compiles the plugin sources with
// Gradle's embedded Kotlin and wires the `java-gradle-plugin` machinery, so the
// plugins below get a generated marker and can be applied by id from the main
// build (`phonemanager.kotlin.library`, `phonemanager.android.library`, ...).
plugins {
    `kotlin-dsl`
}

group = "com.glassstorm.phonemanager.buildlogic"

// The plugin APIs the convention plugins compile against. `compileOnly` is
// deliberate: at RUNTIME these plugins are supplied by the main build's own
// plugin classpath (the root `build.gradle.kts` declares each of them
// `apply false`), so bundling them would double-load them.
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ktlint.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kotlinLibrary") {
            id = "phonemanager.kotlin.library"
            implementationClass = "com.glassstorm.phonemanager.buildlogic.KotlinLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "phonemanager.android.library"
            implementationClass = "com.glassstorm.phonemanager.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "phonemanager.android.application"
            implementationClass = "com.glassstorm.phonemanager.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("ktlint") {
            id = "phonemanager.ktlint"
            implementationClass = "com.glassstorm.phonemanager.buildlogic.KtlintConventionPlugin"
        }
        register("ksp") {
            id = "phonemanager.ksp"
            implementationClass = "com.glassstorm.phonemanager.buildlogic.KspConventionPlugin"
        }
    }
}
