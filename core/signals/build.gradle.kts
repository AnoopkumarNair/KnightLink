plugins {
    id("knightlink.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.common)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            // Golden-frame tests go from raw adapter lines to signal values.
            implementation(projects.core.protocol)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
