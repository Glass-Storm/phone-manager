plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.glassstorm.phonemanager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.glassstorm.phonemanager"
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "1.0-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(libs.core.ktx)

    testImplementation(libs.junit)
}
