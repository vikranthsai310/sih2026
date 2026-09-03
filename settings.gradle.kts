pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\.android.*|com\.google.*|androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "itantra"

// core-proto is pure JVM by design: no Android plugin, so its tests run in
// milliseconds on a laptop. See docs/ARCHITECTURE.md section 2.
include(":core-proto")

include(":core-audio")
include(":core-asr")
include(":core-tts")
include(":core-link")
include(":core-models")
include(":bench")
include(":app")
