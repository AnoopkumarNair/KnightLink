import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

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

/**
 * Copies the repository's definitions and simulation scenarios into a directory that becomes part of the APK's assets
 * (assets/definitions/..., assets/sim/...), so there is exactly one copy of each in the repository.
 *
 * A sync, not a copy: a file deleted from the repository also disappears from the next APK. The inputs are the JSON files
 * themselves, so editing one reruns the task and nothing else does.
 */
abstract class BundleRepositoryData @Inject constructor(private val fileSystem: FileSystemOperations) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val definitions: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scenarios: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun bundle() {
        fileSystem.sync {
            into(output)
            from(definitions.asFileTree) { into("definitions") }
            from(scenarios.asFileTree) { into("sim") }
        }
    }
}

// One task per variant: the variant API wires a generated source directory to a task output, and gives each variant its own.
androidComponents {
    onVariants { variant ->
        val bundle = tasks.register<BundleRepositoryData>("bundle${variant.name.replaceFirstChar { it.uppercase() }}RepositoryData") {
            definitions.from(rootProject.fileTree("definitions") { include("**/*.json") })
            scenarios.from(rootProject.fileTree("fixtures/sim") { include("*.json") })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundle, BundleRepositoryData::output)
    }
}

dependencies {
    // Shared Kotlin Multiplatform modules, consumed as their JVM artefacts.
    implementation(projects.core.adapter)
    implementation(projects.core.protocol)
    implementation(projects.core.recorder)
    implementation(projects.core.runtime)
    implementation(projects.core.scan)
    implementation(projects.core.signals)
    implementation(projects.core.sim)
    implementation(projects.core.telemetry)
    implementation(projects.core.tpms)
    implementation(projects.core.transport)
    implementation(projects.core.vehicle)
    implementation(projects.voice.api)
    implementation(projects.platform.bleAndroid)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
