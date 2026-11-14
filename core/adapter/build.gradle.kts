plugins {
    id("knightlink.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.transport)
            api(projects.core.protocol)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(projects.core.signals)
        }
    }
}
