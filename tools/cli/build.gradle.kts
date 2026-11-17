plugins {
    id("knightlink.jvm-app")
}

dependencies {
    implementation(projects.core.adapter)
    implementation(projects.core.recorder)
    implementation(projects.core.runtime)
    implementation(projects.core.scan)
    implementation(projects.core.scheduler)
    implementation(projects.core.signals)
    implementation(projects.core.sim)
    implementation(projects.core.telemetry)
    implementation(projects.core.tpms)
    implementation(projects.core.vehicle)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "knightlink.tools.cli.MainKt"
}

tasks.named<JavaExec>("run") {
    // Definitions and fixtures are addressed relative to the repository root.
    workingDir = rootDir
}

tasks.test {
    // The tool reads ./definitions, so its tests run from the repository root too.
    workingDir = rootDir
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
