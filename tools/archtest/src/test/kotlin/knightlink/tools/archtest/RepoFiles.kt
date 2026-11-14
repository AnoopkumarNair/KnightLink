package knightlink.tools.archtest

import java.io.File

/** Locates the repository root handed over by the Gradle convention plugin (`knightlink.repoRoot`). */
object RepoFiles {
    val root: File =
        File(
            System.getProperty("knightlink.repoRoot")
                ?: error("System property knightlink.repoRoot is not set; run the tests through Gradle."),
        ).also { check(it.isDirectory) { "Repo root does not exist: $it" } }

    /** Every core module directory, e.g. `core/protocol`. */
    fun coreModules(): List<File> =
        File(root, "core")
            .listFiles { f -> f.isDirectory && File(f, "build.gradle.kts").isFile }
            .orEmpty()
            .sortedBy { it.name }

    /** All Kotlin files under `src/` of a module whose source-set directory name satisfies [sourceSet]. */
    fun kotlinFiles(
        module: File,
        sourceSet: (String) -> Boolean,
    ): List<File> =
        File(module, "src")
            .listFiles { f -> f.isDirectory && sourceSet(f.name) }
            .orEmpty()
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .sortedBy { it.path }

    /** Path relative to the repo root with forward slashes, for readable failure messages. */
    fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath
}
