// Convention for the root project: merges the coverage of every core module into one Kover report.

plugins {
    id("org.jetbrains.kotlinx.kover")
}

dependencies {
    rootProject.subprojects
        .filter { it.path.startsWith(":core:") }
        .forEach { kover(project(it.path)) }
}
