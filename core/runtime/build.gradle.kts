plugins {
    id("knightlink.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // All of these appear in the public API: the transport is a constructor argument, the catalog too, and the
            // state and flows expose probe, coverage, telemetry and poll result types.
            api(projects.core.transport)
            api(projects.core.signals)
            api(projects.core.scheduler)
            api(projects.core.telemetry)
            api(projects.core.vehicle)
            // The driver's configuration is part of the session's, and the event sink is a constructor argument.
            api(projects.core.adapter)
            api(projects.core.common)
            implementation(projects.core.protocol)
        }
    }
}
