plugins {
    id("knightlink.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.signals)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
