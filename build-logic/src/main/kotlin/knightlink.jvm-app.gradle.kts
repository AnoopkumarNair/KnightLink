// Convention for developer tools that run on the PC.

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

group = "knightlink"

kotlin {
    jvmToolchain(21)
}
