plugins {
    id("knightlink.jvm-app")
}

dependencies {
    implementation(projects.core.adapter)
    implementation(projects.core.scheduler)
    implementation(projects.core.signals)
    implementation(projects.core.sim)
    implementation(projects.core.telemetry)
    implementation(projects.core.vehicle)
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass = "knightlink.tools.cli.MainKt"
}

tasks.named<JavaExec>("run") {
    // Definitions and fixtures are addressed relative to the repository root.
    workingDir = rootDir
}
