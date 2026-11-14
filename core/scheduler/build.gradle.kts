plugins {
    id("knightlink.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.protocol)
            api(libs.kotlinx.coroutines.core)
        }
    }
}
