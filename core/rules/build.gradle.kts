plugins {
    id("knightlink.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // TelemetrySnapshot is the engine's input and Provenance/SignalCatalog are part of SignalTrust's API.
            api(projects.core.telemetry)
            api(projects.core.signals)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            // The end-to-end tests run the real session against the simulator, and replay the committed real-car recording through the real decoders.
            implementation(projects.core.sim)
            implementation(projects.core.runtime)
            implementation(projects.core.adapter)
            implementation(projects.core.scheduler)
            implementation(projects.core.vehicle)
            implementation(projects.core.protocol)
            implementation(projects.core.transport)
            implementation(projects.core.recorder)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

tasks.withType<Test>().configureEach {
    // The convention plugin tracks only *.json data files; the committed recordings are *.jsonl, and editing one must rerun the tests that replay it.
    inputs
        .files(fileTree(rootDir) { include("fixtures/recordings/**/*.jsonl", "fixtures/raw/**/*.jsonl") })
        .withPropertyName("recordingFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
