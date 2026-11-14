plugins {
    id("knightlink.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Both appear in the public API: ProbeLink returns ElmResponse, DefinitionCoverage takes a SignalCatalog.
            api(projects.core.protocol)
            api(projects.core.signals)
            implementation(projects.core.common)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
