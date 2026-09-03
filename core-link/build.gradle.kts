plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.itantra.link"
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
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.withType<Test>().configureEach {
    testLogging { events("passed", "failed", "skipped") }
}
