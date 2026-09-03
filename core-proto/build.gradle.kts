// PURE JVM. No Android plugin, ever.
// This is what lets the highest-risk code in the project -- the frame codec --
// be unit-tested and fuzzed in milliseconds on a desktop rather than minutes on
// a handset, and it is why the whole transport layer can be proven in week 2
// before any model exists. See docs/ARCHITECTURE.md section 2.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlin.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotest.property)     // property tests: pack/unpack round trip
    testImplementation(libs.kotest.assertions)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.allWarningsAsErrors.set(true)   // warnings are errors here only
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}
