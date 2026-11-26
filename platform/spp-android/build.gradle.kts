plugins {
    id("knightlink.android-library")
}

android {
    namespace = "knightlink.platform.spp"
}

dependencies {
    api(projects.core.transport)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
