plugins {
    id("knightlink.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.transport)
            api(projects.core.common)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            // The full-stack test runs the real driver, scheduler and definitions against the simulator.
            implementation(projects.core.adapter)
            implementation(projects.core.scheduler)
            implementation(projects.core.signals)
            implementation(projects.core.telemetry)
        }
    }
}
