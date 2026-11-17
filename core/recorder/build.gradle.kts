plugins {
    id("knightlink.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // ByteStream and Transport appear in the public API of the recording and replay wrappers.
            api(projects.core.transport)
            // ElmLineParser and IsoTpReassembler let the scrubber read what an adapter printed instead of guessing at bytes.
            implementation(projects.core.protocol)
            // SessionEventSink is part of RecordingTransport's public API.
            api(projects.core.common)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            // The round-trip tests record the real driver, probe and scheduler talking to the simulator, then replay them.
            implementation(projects.core.sim)
            implementation(projects.core.adapter)
            implementation(projects.core.signals)
            implementation(projects.core.telemetry)
            implementation(projects.core.scheduler)
            implementation(projects.core.vehicle)
            // The hang-up test records a whole VehicleSession, which is what reacts to a link that ends.
            implementation(projects.core.runtime)
        }
    }
}

tasks.withType<Test>().configureEach {
    // The convention plugin tracks only *.json data files; the committed recordings are *.jsonl, and editing one
    // must rerun the tests that replay it instead of reusing a cached result.
    inputs
        .files(fileTree(rootDir) { include("fixtures/recordings/**/*.jsonl") })
        .withPropertyName("recordingFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
