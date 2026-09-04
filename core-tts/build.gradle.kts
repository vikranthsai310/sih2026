plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.itantra.tts"
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
    // sherpa-onnx is a downloaded AAR, not a Maven artifact -- run tools/fetch_sherpa.sh
    // once. See docs/SETUP.md and open question Q3.
    //
    // compileOnly, not implementation: AGP refuses to bundle a local .aar into a library
    // AAR, because the result would silently omit its classes and native libraries. The
    // app module carries it instead, so it is packaged exactly once.
    compileOnly(files(rootProject.file("libs/sherpa-onnx-1.13.7.aar")))
    implementation(libs.kotlin.coroutines)
    // normalise.<lang>.json is parsed on the JVM in the fixture suite as well as on
    // device, so the parser must not reach for android.jar: org.json returns stubs under
    // unit test and every fixture would then pass against nothing.
    implementation(libs.kotlinx.serialization)
    // TODO(W1.23): sherpa-onnx is NOT on Maven Central under the coordinates the
    //  catalog assumed, and not under com.k2fsa.sherpa.onnx:sherpa-onnx-android either.
    //  The upstream repo carries a jitpack.yml, so it is probably distributed through
    //  JitPack or as a downloaded .aar. Resolve the real channel before wiring the
    //  recogniser; nothing written so far needs it.
    // implementation(libs.sherpa.onnx)
    implementation(project(":core-audio"))
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging { events("passed", "failed", "skipped") }
    // Fixture regeneration is opt-in and has to reach the test JVM, which does not
    // inherit the Gradle daemon's system properties.
    systemProperty("fixtures.regenerate", System.getProperty("fixtures.regenerate") ?: "false")
}
