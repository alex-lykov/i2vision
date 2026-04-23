package com.i2vision.arch.detector

import com.i2vision.arch.signature.BuildSystem
import java.io.File

/**
 * Detects build system from project structure
 */
class BuildSystemDetector(private val projectRoot: String) {

    /**
     * Detect build system from project root
     */
    fun detect(): BuildSystemDetector.Result {
        val root = File(projectRoot)

        val buildFiles = root.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()

        return when {
            "build.gradle.kts" in buildFiles -> Result(BuildSystem.GRADLE_KTS, 0.95)
            "build.gradle" in buildFiles -> Result(BuildSystem.GRADLE_GROOVY, 0.95)
            "pom.xml" in buildFiles -> Result(BuildSystem.MAVEN, 0.95)
            "package.json" in buildFiles -> Result(BuildSystem.NPM, 0.95)
            "Cargo.toml" in buildFiles -> Result(BuildSystem.CARGO, 0.95)
            "go.mod" in buildFiles -> Result(BuildSystem.GO, 0.95)
            else -> Result(BuildSystem.UNKNOWN, 0.0)
        }
    }

    data class Result(
        val buildSystem: BuildSystem,
        val confidence: Double
    )
}
