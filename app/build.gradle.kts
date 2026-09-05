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
        minSdk = 26 // AudioAttributes.USAGE_ALARM, Keystore, AudioRecord timestamps
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // arm64 only: halves the installer, and the target handset is arm64
        ndk { abiFilters += "arm64-v8a" }
        // Without this the instrumented tests do not run at all -- the alert path
        // can only be proven on a real handset, so this is not optional.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // W8.7. The sherpa-onnx AAR ships four native libraries and this application loads
    // two of them.
    //
    // The evidence, rather than the assumption: the dynamic string table of
    // libsherpa-onnx-jni.so names libonnxruntime.so and the system libraries, and nothing
    // else. libsherpa-onnx-c-api.so is a standalone C entry point for native consumers,
    // and libsherpa-onnx-cxx-api.so is a wrapper over that one; the Kotlin binding reaches
    // the library through JNI and never touches either.
    //
    //   strings lib/arm64-v8a/libsherpa-onnx-jni.so | grep '\.so$'
    //
    // 4.7 MB, which is the whole distance between the 30.9 MB the release APK was and the
    // 30 MB installer target N2 asks for. Confirmed on device is a handset task; the
    // linkage is checkable here and is the reason this is safe to do now.
    packaging {
        jniLibs {
            excludes +=
                listOf(
                    "**/libsherpa-onnx-c-api.so",
                    "**/libsherpa-onnx-cxx-api.so",
                )
        }
    }

    // An App Bundle delivers per device, so a handset downloads only the resources it can
    // use. The ABI split is already moot -- abiFilters restricts the build to arm64-v8a --
    // but it is declared so that adding a second ABI later does not silently ship both to
    // every device.
    //
    // Locale splitting is deliberately NOT narrowed with localeFilters. Stripping the
    // AndroidX locale resources would save around a megabyte and would make every
    // framework-provided accessibility string speak English on a Hindi handset, which
    // undoes W7.23 to save space this project no longer needs.
    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = true }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDir("src/main/kotlin")
    // The deployment profile is copied into the APK from models/ rather than kept as a
    // second copy under app/. Two copies of a template table is exactly the drift
    // PROTOCOL.md section 5.2 calls a safety defect: byte 0x02 meaning one sentence on one
    // handset and another elsewhere. One file, copied at build time.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/assets"))
    sourceSets["test"].java.srcDir("src/test/kotlin")
    sourceSets["androidTest"].java.srcDir("src/androidTest/kotlin")
}

/**
 * Copies the deployment profile into the APK from `models/`.
 *
 * Every unit must hold the *same* table: byte 0x02 meaning "evacuate immediately" on one
 * handset and "position secure" on another is a safety defect, not a compatibility one —
 * `docs/PROTOCOL.md` section 5.2. Keeping a second copy under `app/` is how those two
 * tables drift apart, so there is one file and the build copies it.
 */
val copyDeploymentProfile by tasks.registering(Copy::class) {
    from(rootProject.file("models/templates/templates.json"))
    into(layout.buildDirectory.dir("generated/assets"))
}

/**
 * Copies the alert lexicons into the APK from `models/lexicon/`.
 *
 * Twenty files, eighty kilobytes in total — small enough to bundle, unlike the acoustic
 * models, and needed on every handset for every language rather than only the installed
 * ones. Copied from `models/` for the same reason the template profile is: one file, not
 * a second copy that drifts.
 */
val copyLexicons by tasks.registering(Copy::class) {
    from(rootProject.file("models/lexicon"))
    into(layout.buildDirectory.dir("generated/assets/lexicon"))
    include("*.txt")
}

tasks.named("preBuild") { dependsOn(copyDeploymentProfile, copyLexicons) }

dependencies {
    implementation(project(":core-audio"))
    implementation(project(":core-asr"))
    implementation(project(":core-tts"))
    implementation(project(":core-link"))
    implementation(project(":core-proto"))
    implementation(project(":core-models"))
    // The latency log writes on the live path, because the reporting rules ask for a
    // median and p95 over 100+ utterances — see docs/EVALUATION.md section 4.
    implementation(project(":bench"))
    // sherpa-onnx is a downloaded AAR, not a Maven artifact -- run tools/fetch_sherpa.sh
    // once. See docs/SETUP.md and open question Q3.
    implementation(files(rootProject.file("libs/sherpa-onnx-1.13.7.aar")))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation("androidx.compose.foundation:foundation")
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
