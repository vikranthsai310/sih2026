// Gradle provisions JDK 17 itself rather than trusting whatever is on the PATH.
// Six machines with three different JDKs cannot produce comparable benchmark
// figures, and AGP 8.7 rejects JDK 25 outright. See docs/SETUP.md section 1.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
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
