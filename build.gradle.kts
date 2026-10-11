// OpenTrack India v2 — root build (CI-1).
// Versions are the known-good pair recorded by the previous session's green CI setup steps
// (AGP 8.5.2 + Kotlin 1.9.24 + Gradle 8.7 + JDK 17). Nothing newer until a green run justifies it:
// a version bump is a variable, and CI-1 exists to produce ONE verdict with ONE variable set.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
