package com.i2vision.discovery

import java.io.File
import kotlin.math.ln

/**
 * Richer detection result used by VisionStructureContract.
 * Carries semantic architecture info rather than raw scoring numbers.
 */
data class DetectionDetails(
    val primaryLanguage: String,
    val secondaryLanguages: List<String>,
    val buildSystem: String,
    /** Raw hint: "clean" | "layered" | "modular" | "android" | "unknown" */
    val architectureStyleHint: String,
    val frameworks: List<String>,
    val evidence: List<String>
)

/**
 * Confidence assessment for a discovered directory/cluster.
 * Used for monitoring and filtering low-quality discoveries.
 */
data class DirectoryConfidence(
    val path: String,
    val confidence: Double, // 0.0 to 1.0
    val reason: String,     // Why this confidence score
    val shouldAnalyze: Boolean // Hard exclude vs soft warning
)

class HybridDiscoveryHeuristics {

    data class Scores(
        val fileTypeScore: Double,
        val layoutScore: Double,
        val templateSignalScore: Double,
        val mcpSignalScore: Double,
        val sizeComplexityScore: Double
    )

    fun score(
        projectRoot: String,
        templateSignal: Double = 0.0,
        mcpSignal: Double = 0.0,
        maxFilesToScan: Int = 2000
    ): Scores {
        val files = collectFiles(projectRoot, maxFilesToScan)
        val nTotal = files.size.coerceAtLeast(1)

        val nKotlin = files.count { it.endsWith(".kt") }
        val nJava = files.count { it.endsWith(".java") }
        val nSrc = files.count { it.contains("src${File.separator}main") }

        val fileTypeScore =
            ((0.7 * (nKotlin + nJava).toDouble() / nTotal) + (0.3 * nSrc.toDouble() / nTotal)).coerceIn(0.0, 1.0)

        val hasGradle = File(projectRoot, "build.gradle.kts").exists() || File(projectRoot, "build.gradle").exists()
        val hasPom = File(projectRoot, "pom.xml").exists()
        val hasSrcKotlin = File(projectRoot, "src/main/kotlin").exists()
        val layoutScore = listOf(hasGradle || hasPom, hasSrcKotlin).count { it }.toDouble() / 2.0

        val templateSignalScore = templateSignal.coerceIn(0.0, 1.0)
        val mcpSignalScore = mcpSignal.coerceIn(0.0, 1.0)

        val sizeComplexityScore = (1.0 - (ln(nTotal + 1.0) / ln(10.0) / 4.0)).coerceIn(0.0, 1.0)

        return Scores(fileTypeScore, layoutScore, templateSignalScore, mcpSignalScore, sizeComplexityScore)
    }

    /**
     * Get architecture-aware exclusions based on detected build system.
     * Always excludes cache/config dirs, but build artifacts are build-system specific.
     */
    private fun getExclusionDirs(projectRoot: String): Set<String> {
        val base = setOf(".semantic-cache", ".vision-ai", ".git", ".idea", ".vscode")

        // Detect build system to exclude appropriate build directories
        val buildSpecific = when {
            File(projectRoot, "build.gradle.kts").exists() ||
                    File(projectRoot, "build.gradle").exists() ->
                setOf("build", ".gradle", "out")

            File(projectRoot, "pom.xml").exists() ->
                setOf("target", ".m2")

            File(projectRoot, "package.json").exists() ->
                setOf("node_modules", "dist", ".next", "out")

            File(projectRoot, "Cargo.toml").exists() ->
                setOf("target")

            File(projectRoot, "go.mod").exists() ->
                setOf("vendor")

            else -> setOf("build", "out", "target") // fallback
        }

        return base + buildSpecific
    }

    private fun collectFiles(root: String, limit: Int): List<String> {
        val base = File(root)
        if (!base.exists()) return emptyList()
        val result = mutableListOf<String>()
        val excludedDirs = getExclusionDirs(root)

        base.walkTopDown().onEnter { dir ->
            !excludedDirs.contains(dir.name)
        }.forEach {
            if (it.isFile) {
                result.add(it.absolutePath)
                if (result.size >= limit) return result
            }
        }
        return result
    }

    fun aggregate(scores: Scores, weights: Map<String, Double>? = null): Double {
        val w = weights ?: mapOf(
            "fileTypeScore" to 0.30,
            "layoutScore" to 0.25,
            "templateSignalScore" to 0.20,
            "mcpSignalScore" to 0.15,
            "sizeComplexityScore" to 0.10
        )
        val weighted = w["fileTypeScore"]!! * scores.fileTypeScore +
                w["layoutScore"]!! * scores.layoutScore +
                w["templateSignalScore"]!! * scores.templateSignalScore +
                w["mcpSignalScore"]!! * scores.mcpSignalScore +
                w["sizeComplexityScore"]!! * scores.sizeComplexityScore

        val sumWeights = w.values.sum()
        return (weighted / sumWeights).coerceIn(0.0, 1.0)
    }

    /**
     * Semantic detection: returns language, build system, architecture style, and evidence.
     * Used by VisionStructureContract to build the ArchitectureAnalysis result.
     */
    fun detect(projectRoot: String, maxFilesToScan: Int = 2000): DetectionDetails {
        val files = collectFiles(projectRoot, maxFilesToScan)
        val root = File(projectRoot)
        val evidence = mutableListOf<String>()

        // ── Language counts ────────────────────────────────────────────────
        val langMap = mapOf(
            "kotlin" to files.count { it.endsWith(".kt") },
            "java" to files.count { it.endsWith(".java") },
            "typescript" to files.count { it.endsWith(".ts") || it.endsWith(".tsx") },
            "javascript" to files.count { it.endsWith(".js") || it.endsWith(".jsx") },
            "go" to files.count { it.endsWith(".go") },
            "rust" to files.count { it.endsWith(".rs") },
            "python" to files.count { it.endsWith(".py") },
            "swift" to files.count { it.endsWith(".swift") }
        )
        val sortedLangs = langMap.entries.filter { it.value > 0 }.sortedByDescending { it.value }
        val primaryLanguage = sortedLangs.firstOrNull()?.key ?: "unknown"
        val secondaryLanguages = sortedLangs.drop(1).take(3).map { it.key }
        if (primaryLanguage != "unknown") evidence.add("$primaryLanguage source (${sortedLangs.first().value} files)")
        secondaryLanguages.forEach { lang -> evidence.add("$lang source (${langMap[lang]} files)") }

        // ── Build system ──────────────────────────────────────────────────
        val buildSystem = when {
            File(root, "build.gradle.kts").exists() || File(root, "settings.gradle.kts").exists() -> {
                evidence.add("gradle_kts"); "gradle"
            }

            File(root, "build.gradle").exists() -> {
                evidence.add("gradle"); "gradle"
            }

            File(root, "pom.xml").exists() -> {
                evidence.add("maven"); "maven"
            }

            File(root, "package.json").exists() -> {
                evidence.add("npm"); "npm"
            }

            File(root, "Cargo.toml").exists() -> {
                evidence.add("cargo"); "cargo"
            }

            File(root, "go.mod").exists() -> {
                evidence.add("go_mod"); "go"
            }

            else -> "unknown"
        }

        // ── Architecture style ────────────────────────────────────────────
        val sep = File.separator
        val hasAndroid = files.any { it.endsWith("AndroidManifest.xml") }
        val hasDomain = files.any { it.contains("${sep}domain${sep}") }
        val hasApplication = files.any { it.contains("${sep}application${sep}") }
        val hasInfra = files.any { it.contains("${sep}infrastructure${sep}") }
        val hasController = files.any { it.contains("${sep}controller${sep}") }
        val hasService = files.any { it.contains("${sep}service${sep}") }
        val hasRepository = files.any { it.contains("${sep}repository${sep}") }
        val isMultiModule = File(root, "settings.gradle.kts").exists() || File(root, "settings.gradle").exists()

        val architectureStyleHint = when {
            hasAndroid -> {
                evidence.add("android_manifest"); "android"
            }

            hasDomain && hasApplication && hasInfra -> {
                evidence.add("clean_architecture"); "clean"
            }

            hasController && hasService && hasRepository -> {
                evidence.add("layered_architecture"); "layered"
            }

            isMultiModule -> {
                evidence.add("multi_module"); "modular"
            }

            else -> "unknown"
        }

        // ── Frameworks (file-presence only, no content reads) ─────────────
        val frameworks = mutableListOf<String>()
        if (hasAndroid) frameworks.add("android")
        if (File(root, "tsconfig.json").exists()) {
            frameworks.add("typescript"); evidence.add("tsconfig_json")
        }
        if (File(root, "next.config.js").exists() ||
            File(root, "next.config.ts").exists()
        ) {
            frameworks.add("nextjs"); evidence.add("nextjs_config")
        }

        return DetectionDetails(
            primaryLanguage = primaryLanguage,
            secondaryLanguages = secondaryLanguages,
            buildSystem = buildSystem,
            architectureStyleHint = architectureStyleHint,
            frameworks = frameworks,
            evidence = evidence
        )
    }

    /**
     * Assess confidence for a directory to be analyzed as a cluster.
     * 
     * @deprecated Use ComponentValidator.validate() instead.
     * This method is kept for backward compatibility but delegates to ComponentValidator.
     */
    fun assessDirectoryConfidence(dirPath: String, projectRoot: String): DirectoryConfidence {
        // TODO: Re-implement with ComponentValidator when contracts are extracted
        // For now, provide a simple heuristic-based assessment
        val dir = File(dirPath)
        val confidence = if (dir.exists() && dir.isDirectory) {
            // Simple heuristic: check for source files
            val sourceFileCount = dir.walk().count { it.extension in listOf("kt", "java", "scala", "groovy") }
            if (sourceFileCount > 0) 0.8 else 0.3
        } else {
            0.0
        }

        return DirectoryConfidence(
            path = dirPath,
            confidence = confidence,
            reason = "Heuristic-based assessment (source file count)",
            shouldAnalyze = confidence > 0.5
        )
    }

    private fun detectBuildSystem(projectRoot: String): String {
        return when {
            File(projectRoot, "build.gradle.kts").exists() -> "gradle"
            File(projectRoot, "pom.xml").exists() -> "maven"
            File(projectRoot, "package.json").exists() -> "npm"
            File(projectRoot, "Cargo.toml").exists() -> "cargo"
            File(projectRoot, "go.mod").exists() -> "go"
            else -> "unknown"
        }
    }
}

