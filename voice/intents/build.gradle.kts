plugins {
    id("knightlink.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Answers are built from the telemetry snapshot; the catalog's provenance decides which readings a safety answer may use.
            api(projects.core.telemetry)
            api(projects.core.signals)
        }
    }
}
