// Convention for developer tools that run on the PC.

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    id("org.jlleitschuh.gradle.ktlint")
}

group = "knightlink"

kotlin {
    jvmToolchain(21)
}
