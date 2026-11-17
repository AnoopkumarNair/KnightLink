plugins {
    id("knightlink.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The scan talks to the adapter through the protocol's DiagnosticLink and reports with the probe's report types.
            api(projects.core.protocol)
            api(projects.core.vehicle)
            api(projects.core.common)
            api(projects.core.signals)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
