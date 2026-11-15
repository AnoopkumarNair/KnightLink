plugins {
    id("knightlink.jvm-library")
}

tasks.test {
    // The tests read the repository itself, so those files must be declared inputs; otherwise Gradle
    // (and the build cache) would report the tests as up to date after a core source or data change.
    inputs
        .files(
            fileTree(rootDir) {
                include("core/*/build.gradle.kts", "core/*/src/**/*.kt", "definitions/**/*.json", "fixtures/**/*.json", "fixtures/**/*.jsonl")
            },
        ).withPropertyName("repositoryFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
