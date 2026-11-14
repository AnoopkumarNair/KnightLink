// Convention for every shared (platform-independent) module.
// JVM is the fast test target; iOS targets keep the shared code portable and are built on macOS CI.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.kotlinx.kover")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "knightlink"

kotlin {
    explicitApi()
    jvmToolchain(21)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}

tasks.withType<Test>().configureEach {
    // JVM tests validate the data files in /definitions and /fixtures.
    systemProperty("knightlink.repoRoot", rootDir.absolutePath)
    // Declared as inputs so that editing only a data file reruns the tests instead of reusing a cached result.
    inputs
        .files(fileTree(rootDir) { include("definitions/**/*.json", "fixtures/**/*.json") })
        .withPropertyName("repositoryDataFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
