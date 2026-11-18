pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "knightlink"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":core:common",
    ":core:transport",
    ":core:protocol",
    ":core:signals",
    ":core:tpms",
    ":core:adapter",
    ":core:sim",
    ":core:scheduler",
    ":core:telemetry",
    ":core:vehicle",
    ":core:scan",
    ":core:runtime",
    ":core:recorder",
    ":voice:intents",
    ":tools:cli",
    ":tools:archtest",
)

// The Android app needs the Android SDK; everything else needs only a JDK. Including the app only when
// an SDK is configured keeps `./gradlew check` working for core-only work and on machines without one.
val androidSdkConfigured: Boolean =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.isFile && it.readLines().any { line -> line.trimStart().startsWith("sdk.dir") } }

if (androidSdkConfigured) {
    include(":app:android")
    include(":platform:ble-android")
} else {
    logger.lifecycle("Android SDK not configured (no ANDROID_HOME and no sdk.dir in local.properties): skipping :app:android and :platform:ble-android")
}
