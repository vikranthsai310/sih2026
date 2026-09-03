plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library)     apply false
    alias(libs.plugins.kotlin.android)      apply false
    alias(libs.plugins.kotlin.jvm)          apply false
    alias(libs.plugins.compose.compiler)    apply false
    alias(libs.plugins.ksp)                 apply false
    alias(libs.plugins.ktlint)              apply false
}

// Dependency rules from docs/ARCHITECTURE.md section 2 are a build failure,
// not a review comment. core-proto must stay free of Android so that the frame
// codec can be fuzzed on a laptop; nothing may depend upward on :app.
subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group.startsWith("com.android") && project.name == "core-proto") {
                throw GradleException(
                    "core-proto must not depend on Android (see ARCHITECTURE.md 2). " +
                    "Offending dependency: ${requested.group}:${requested.name}"
                )
            }
        }
    }
    afterEvaluate {
        configurations.findByName("implementation")?.dependencies?.forEach { d ->
            if (d is ProjectDependency && d.name == "app") {
                throw GradleException("No module may depend on :app (see ARCHITECTURE.md 2)")
            }
        }
    }
}
