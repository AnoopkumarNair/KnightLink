plugins {
    id("knightlink.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.protocol)
            api(projects.core.signals)
            api(projects.core.scheduler)
        }
    }
}
