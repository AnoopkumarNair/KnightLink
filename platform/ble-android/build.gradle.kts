plugins {
    id("knightlink.android-library")
}

android {
    namespace = "knightlink.platform.ble"
}

dependencies {
    // Transport and profile types are part of this module's public API.
    api(projects.core.transport)
    // Only for the convenience overload that takes an adapter definition's BLE profile.
    implementation(projects.core.adapter)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
