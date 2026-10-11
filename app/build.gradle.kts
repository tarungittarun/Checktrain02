// OpenTrack India v2 — app module (CI-1 skeleton).
// Deliberately the smallest honest surface: one Activity, no deps beyond Kotlin stdlib,
// no Compose, no viewBinding yet. Size discipline is a hard constraint (brief 6.7).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "in.opentrack.india"
    compileSdk = 34

    defaultConfig {
        applicationId = "in.opentrack.india"
        // Brief [MINSDK] = 24.
        minSdk = 24
        targetSdk = 34
        // versionName single source of truth: 2.<milestone>.<k> (brief 11.3).
        versionCode = 2000001
        versionName = "2.0.1"
        // AGP 8.x Kotlin DSL exposes this as a property named resourceConfigurations;
        // resConfigs is a function there (run 38177642227 proved the difference).
        resourceConfigurations += listOf("en")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // No androidx, no coroutines yet: CI-1 proves the pipeline, not a dependency graph.
    // Every dependency added later is an ADR (brief 9.5).
}
