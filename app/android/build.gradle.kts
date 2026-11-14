plugins {
    id("knightlink.android-app")
}

android {
    namespace = "knightlink.app"

    defaultConfig {
        applicationId = "in.byteheaven.knightlink"
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            // Unsigned on purpose: no keystore is created or committed.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    // Shared Kotlin Multiplatform modules, consumed as their JVM artefacts.
    implementation(projects.core.protocol)
    implementation(projects.core.signals)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    testImplementation(kotlin("test"))
}
