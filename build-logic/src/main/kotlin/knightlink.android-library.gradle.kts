// Convention for Android library modules that hold platform code behind interfaces declared in the shared core
// (ADR 0001). Like the app it consumes the JVM artefacts of the core modules. No Compose: these modules have no UI.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
}

kotlin {
    explicitApi()
    jvmToolchain(21)
}

android {
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}
