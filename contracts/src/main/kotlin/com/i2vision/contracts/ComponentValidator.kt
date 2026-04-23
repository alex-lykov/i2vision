/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.contracts

import java.io.File

/**
 * Structure Layer Component Validator
 * 
 * Determines which directories are valid components/clusters for discovery.
 * This is the AUTHORITATIVE filter - only directories that pass this validation
 * will be discovered. Confidence scoring is used for quality metrics, not filtering.
 * 
 * Philosophy:
 * - Hard exclusions (cache, build artifacts) → NOT discovered at all
 * - Soft exclusions (tests, docs) → Discovered with quality tracking
 * - Source directories → Discovered normally
 */
class ComponentValidator(private val projectRoot: String) {

    /**
     * Component validation result with quality metrics
     */
    data class ValidationResult(
        val path: String,
        val isValid: Boolean,           // Should this be discovered?
        val confidence: Double,         // Quality score (0.0-1.0)
        val reason: String,             // Human-readable explanation
        val category: ComponentCategory // For metrics tracking
    )

    enum class ComponentCategory {
        SOURCE,           // Primary source code
        TEST,             // Test code
        DOCUMENTATION,    // Docs, examples
        BUILD_ARTIFACT,   // Generated/build output
        CACHE,            // Semantic cache, etc.
        CONFIG,           // Configuration files
        EMPTY             // No meaningful content
    }

    /**
     * Validate if a directory should be discovered as a component/cluster.
     * 
     * @param dirPath Relative path from project root (e.g., "core/orchestrator")
     * @return ValidationResult with isValid flag and quality metrics
     */
    fun validate(dirPath: String): ValidationResult {
        val dir = File(projectRoot, dirPath)
        val dirName = dir.name

        // 1. Hard exclusions - NEVER discover these
        val cacheAndConfig = setOf(".semantic-cache", ".vision-ai", ".git", ".idea", ".vscode")
        if (cacheAndConfig.contains(dirName)) {
            return ValidationResult(
                path = dirPath,
                isValid = false,
                confidence = 0.0,
                reason = "Cache/config directory - not a component",
                category = ComponentCategory.CACHE
            )
        }

        // 2. Build artifacts - architecture-aware exclusion
        val buildArtifacts = getBuildArtifactDirs()
        if (buildArtifacts.contains(dirName)) {
            return ValidationResult(
                path = dirPath,
                isValid = false,
                confidence = 0.0,
                reason = "Build artifact directory (${detectBuildSystem()}) - not source code",
                category = ComponentCategory.BUILD_ARTIFACT
            )
        }

        // 2b. buildSrc - special Gradle build helper directory
        if (dirName == "buildSrc" || dirPath == "buildSrc") {
            return ValidationResult(
                path = dirPath,
                isValid = false,
                confidence = 0.0,
                reason = "Gradle buildSrc - build-time code generator, not application code",
                category = ComponentCategory.BUILD_ARTIFACT
            )
        }

        // 3. Test directories - VALID but lower priority
        val testDirs = setOf("test", "tests", "testing")
        if (testDirs.contains(dirName.lowercase())) {
            return ValidationResult(
                path = dirPath,
                isValid = true,
                confidence = 0.5,
                reason = "Test directory - valid but lower priority",
                category = ComponentCategory.TEST
            )
        }

        // 4. Documentation - VALID but lowest priority
        val docDirs = setOf("docs", "documentation", "examples", "samples", "demo")
        if (docDirs.contains(dirName.lowercase())) {
            return ValidationResult(
                path = dirPath,
                isValid = true,
                confidence = 0.4,
                reason = "Documentation directory - valid for specs/constraints",
                category = ComponentCategory.DOCUMENTATION
            )
        }

        // 5. Check if directory contains RUNNABLE application source code
        if (!dir.exists()) {
            return ValidationResult(
                path = dirPath,
                isValid = false,
                confidence = 0.0,
                reason = "Directory does not exist",
                category = ComponentCategory.EMPTY
            )
        }

        val hasRunnableCode = detectRunnableCode(dir)

        if (!hasRunnableCode) {
            // No runnable application code - could be build scripts, schemas, etc.
            return ValidationResult(
                path = dirPath,
                isValid = false,
                confidence = 0.2,
                reason = "No runnable application code detected - likely build/config/schemas",
                category = ComponentCategory.CONFIG
            )
        }

        // 6. Normal source directory with runnable code - high confidence
        return ValidationResult(
            path = dirPath,
            isValid = true,
            confidence = 0.9,
            reason = "Source directory with runnable code",
            category = ComponentCategory.SOURCE
        )
    }

    /**
     * Detect if directory contains runnable application source code.
     * Distinguishes application code from build scripts, schemas, and config.
     */
    private fun detectRunnableCode(dir: File): Boolean {
        // Check for standard source directories (src/main, src/test, etc.)
        val hasStandardSourceLayout = listOf(
            File(dir, "src/main/kotlin"),
            File(dir, "src/main/java"),
            File(dir, "src/main/ts"),
            File(dir, "src/main/python")
        ).any { it.exists() && it.isDirectory }

        if (hasStandardSourceLayout) {
            // Standard layout = runnable application code
            return true
        }

        // Check if files are in application source locations (not build scripts)
        val files = dir.walkTopDown()
            .take(100)
            .filter { it.isFile }
            .toList()

        if (files.isEmpty()) {
            return false
        }

        // Count application source files vs build/config files
        var appSourceCount = 0
        var buildConfigCount = 0

        files.forEach { file ->
            val relativePath = file.relativeTo(dir).path.replace('\\', '/')

            when {
                // Application source code locations
                relativePath.startsWith("src/main/") && file.extension in SOURCE_EXTENSIONS -> {
                    appSourceCount++
                }

                relativePath.startsWith("src/") && file.extension in SOURCE_EXTENSIONS -> {
                    appSourceCount++
                }
                // Direct source files (for simpler projects)
                file.parent == dir.absolutePath && file.extension in SOURCE_EXTENSIONS -> {
                    appSourceCount++
                }

                // Build/config files
                file.extension in setOf("gradle", "kts", "xml", "yaml", "yml", "json", "properties") -> {
                    buildConfigCount++
                }

                file.name in setOf("build.gradle", "build.gradle.kts", "pom.xml", "package.json", "Cargo.toml") -> {
                    buildConfigCount++
                }
            }
        }

        // Runnable code if we have application source files, even if we also have build files
        return appSourceCount > 0
    }

    /**
     * Get build artifact directories based on detected build system
     */
    private fun getBuildArtifactDirs(): Set<String> {
        return when (detectBuildSystem()) {
            "gradle" -> setOf("build", ".gradle", "out")
            "maven" -> setOf("target", ".m2")
            "npm" -> setOf("node_modules", "dist", ".next", "out")
            "cargo" -> setOf("target")
            "go" -> setOf("vendor")
            else -> setOf("build", "out", "target") // fallback
        }
    }

    /**
     * Detect build system for the project
     */
    private fun detectBuildSystem(): String {
        val root = File(projectRoot)
        return when {
            File(root, "build.gradle.kts").exists() || File(root, "settings.gradle.kts").exists() -> "gradle"
            File(root, "pom.xml").exists() -> "maven"
            File(root, "package.json").exists() -> "npm"
            File(root, "Cargo.toml").exists() -> "cargo"
            File(root, "go.mod").exists() -> "go"
            else -> "unknown"
        }
    }

    companion object {
        /**
         * Source code extensions - actual runnable/compilable code.
         * Excludes YAML/config files which are configuration, not source modules.
         */
        private val SOURCE_EXTENSIONS = setOf(
            "kt", "java",           // JVM
            "ts", "tsx", "js", "jsx", // JavaScript/TypeScript
            "py",                   // Python
            "go",                   // Go
            "rs",                   // Rust
            "c", "cpp", "h", "hpp", // C/C++
            "swift"                 // Swift
            // Note: YAML/JSON are config/schemas, not source code modules
        )
    }
}




