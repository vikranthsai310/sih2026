plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kover) apply false
}

// The dependency rules in docs/ARCHITECTURE.md section 2 are build failures, not
// review comments.
//
// Note on core-proto: because it is a kotlin("jvm") project, Gradle's own variant
// matching already refuses to resolve an Android AAR into it -- that protection is
// stronger than anything written here, since it cannot be bypassed. What the check
// below adds is a legible message at configuration time, before the reader has to
// interpret a variant-resolution wall of text.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    afterEvaluate {
        val android = listOf("com.android", "androidx.", "com.google.android")

        configurations.matching { it.name.endsWith("implementation", ignoreCase = true) }
            .configureEach {
                dependencies.configureEach {
                    if (project.name == "core-proto" && android.any { group?.startsWith(it) == true }) {
                        throw GradleException(
                            "core-proto must stay free of Android so the frame codec can be " +
                                "fuzzed on a laptop in milliseconds (ARCHITECTURE.md 2). " +
                                "Offending dependency: $group:$name",
                        )
                    }
                    if (this is ProjectDependency && this.name == "app") {
                        throw GradleException(
                            "No module may depend on :app (ARCHITECTURE.md 2). " +
                                "Offending module: ${project.name}",
                        )
                    }
                }
            }
    }
}
