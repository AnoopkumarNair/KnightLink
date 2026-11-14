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
    ":tools:cli",
)
