// OpenTrack India v2 — project settings (CI-1).
// Repos pinned to Google + Maven Central only: auditable dependency graph (§9.5 spirit).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "OpenTrackIndia"
include(":app")
