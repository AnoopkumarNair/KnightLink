// Convention for the Android application. It consumes the JVM artefacts of the shared core modules
// (ADR 0001), so the core itself needs no Android target.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // The app deserialises its own config file; definitions and recordings are decoded by the core modules.
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

kotlin {
    jvmToolchain(21)
}

android {
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
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

tasks.withType<Test>().configureEach {
    // Unit tests load the real definitions, simulation scenarios and the app config from the repository.
    systemProperty("knightlink.repoRoot", rootDir.absolutePath)
    // Declared as inputs so that editing only a data file reruns the tests instead of reusing a cached result.
    inputs
        .files(
            fileTree(rootDir) {
                include("definitions/**/*.json", "fixtures/**/*.json", "fixtures/recordings/**/*.jsonl", "app/android/src/main/assets/**")
            },
        ).withPropertyName("repositoryDataFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
