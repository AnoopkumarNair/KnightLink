// Convention for JVM-only modules that are not applications (tooling and guard tests).

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

group = "knightlink"

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    // Tests read the repository (definitions, fixtures, build files) from this root.
    systemProperty("knightlink.repoRoot", rootDir.absolutePath)
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
