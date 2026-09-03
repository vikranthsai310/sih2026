plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "org.itantra.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.itantra"
        minSdk = 26            // AudioAttributes.USAGE_ALARM, Keystore, AudioRecord timestamps
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // arm64 only: halves the installer, and the target handset is arm64
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // aapt compresses assets by default, including .onnx weights, which then cannot
    // be memory-mapped and fail alignment at runtime. Models must ship uncompressed.
    androidResources {
        noCompress += listOf("onnx", "bin", "ort", "tflite")
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")
    sourceSets["androidTest"].java.srcDir("src/androidTest/kotlin")
}

dependencies {
    implementation(project(":core-audio"))
    implementation(project(":core-asr"))
    implementation(project(":core-tts"))
    implementation(project(":core-link"))
    implementation(project(":core-proto"))
    implementation(project(":core-models"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.datastore.prefs)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.zxing.core)
    implementation(libs.kotlin.coroutines)

    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
}
