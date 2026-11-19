plugins {
    id("knightlink.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The speaker's state is a StateFlow and the queued speaker takes a CoroutineScope, so both are part of the public API.
            api(libs.kotlinx.coroutines.core)
        }
    }
}
