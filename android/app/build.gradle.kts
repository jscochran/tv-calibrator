plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "tvcal.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "tvcal.android"
        minSdk = 29          // Android 10: Camera2 RAW + manual sensor on every phone we target
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // The profile server: this fork's server/ folder (index.json, profiles/*.json, patterns.json).
        buildConfigField("String", "PROFILE_SERVER", "\"https://raw.githubusercontent.com/jscochran/tv-calibrator/android/server/\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload-only: sign release builds with the debug key so they install without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // The lab build: pairs with the lab tool on a computer to train camera profiles.
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            resValue("string", "app_name", "TV Calibrator for Android")
            buildConfigField("boolean", "LAB_MODE", "false")
        }
        create("lab") {
            dimension = "edition"
            applicationIdSuffix = ".lab"
            versionNameSuffix = "-lab"
            resValue("string", "app_name", "TV Cal Lab for Android")
            buildConfigField("boolean", "LAB_MODE", "true")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":calibrationkit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
