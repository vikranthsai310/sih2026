plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.itantra.asr"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.kotlin.coroutines)
    implementation(project(":core-proto"))
    // TODO(W1.23): sherpa-onnx is NOT on Maven Central under the coordinates the
    //  catalog assumed, and not under com.k2fsa.sherpa.onnx:sherpa-onnx-android either.
    //  The upstream repo carries a jitpack.yml, so it is probably distributed through
    //  JitPack or as a downloaded .aar. Resolve the real channel before wiring the
    //  recogniser; nothing written so far needs it.
    // implementation(libs.sherpa.onnx)
    implementation(project(":core-audio"))
    // sherpa-onnx is a downloaded AAR, not a Maven artifact -- run tools/fetch_sherpa.sh
    // once. See docs/SETUP.md and open question Q3.
    //
    // compileOnly, not implementation: AGP refuses to bundle a local .aar into a library
    // AAR, because the result would silently omit its classes and native libraries. The
    // app module carries it instead, so it is packaged exactly once.
    compileOnly(files(rootProject.file("libs/sherpa-onnx-1.13.7.aar")))
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging { events("passed", "failed", "skipped") }
}
