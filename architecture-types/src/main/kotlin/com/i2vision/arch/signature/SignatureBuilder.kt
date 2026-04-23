package com.i2vision.arch.signature

import com.i2vision.arch.detector.BuildSystemDetector
import com.i2vision.arch.detector.FrameworkDetector
import com.i2vision.arch.detector.ModulePatternDetector
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Builds architecture signature by running all detectors per-module
 * 
 * IMPORTANT: A single project can have multiple architectural patterns simultaneously
 * across different modules. This builder detects architecture per-module instead of globally.
 * 
 * Can optionally use an IndexProvider for more accurate symbol-based detection,
 * but falls back to file-based detection when no index is available.
 * The indexProvider is passed as a generic Any? to avoid compile-time dependency.
 */
class SignatureBuilder(
    private val projectRoot: String,
    private val confidenceThreshold: Double = 0.7,
    private val useLlmForLowConfidence: Boolean = false,
    private val llmClient: Any? = null,  // TODO: Define LLM client interface
    private val indexProvider: Any? = null  // Optional - no hard compile-time dependency
) {

    private val log = LoggerFactory.getLogger(SignatureBuilder::class.java)
    private val buildSystemDetector = BuildSystemDetector(projectRoot)
    private val modulePatternDetector = ModulePatternDetector(projectRoot)

    /**
     * Build complete architecture signature with per-module detection
     */
    fun build(): ArchitectureSignature {
        val useIndex = indexProvider != null
        log.info("[SIGNATURE_BUILDER] Building signature (using index: $useIndex)")

        val confidenceMap = mutableMapOf<String, Double>()

        // Detect build system (global)
        val buildSystemResult = buildSystemDetector.detect()
        confidenceMap["buildSystem"] = buildSystemResult.confidence

        // Detect module patterns (per-module)
        val modulePatternResult = modulePatternDetector.detect()
        confidenceMap.putAll(modulePatternResult.confidences)

        // Detect per-module architecture
        val moduleFrameworks = mutableMapOf<String, List<Framework>>()
        val moduleDesignPatterns = mutableMapOf<String, List<DesignPattern>>()
        val moduleLanguageFeatures = mutableMapOf<String, List<LanguageFeature>>()

        // Analyze each module separately
        val modules = identifyModules(projectRoot)
        for (module in modules) {
            val modulePath = File(projectRoot, module)

            // Detect frameworks per module
            val frameworkDetector = FrameworkDetector(modulePath.absolutePath)
            val frameworkResult = frameworkDetector.detect()
            if (frameworkResult.frameworks.isNotEmpty()) {
                moduleFrameworks[module] = frameworkResult.frameworks
                confidenceMap.putAll(frameworkResult.confidences.mapKeys { "${module}_framework_${it.key}" })
            }

            // Detect design patterns per module
            val designPatterns = detectDesignPatternsInModule(modulePath)
            if (designPatterns.isNotEmpty()) {
                moduleDesignPatterns[module] = designPatterns
                confidenceMap["${module}_design_patterns"] = 0.70
            }

            // Detect language features per module
            val languageFeatures = detectLanguageFeaturesInModule(modulePath)
            if (languageFeatures.isNotEmpty()) {
                moduleLanguageFeatures[module] = languageFeatures
                confidenceMap["${module}_language_features"] = 0.75
            }

            // Evaluate confidence for this module and use LLM fallback if needed
            val moduleConfidence = calculateModuleConfidence(
                module,
                moduleFrameworks[module],
                moduleDesignPatterns[module],
                moduleLanguageFeatures[module]
            )
            confidenceMap["${module}_overall"] = moduleConfidence

            if (moduleConfidence < confidenceThreshold && useLlmForLowConfidence && llmClient != null) {
                // TODO: Call LLM for low-confidence modules
                // detectWithLLM(module, modulePath, heuristicResult)
            }
        }

        // Detect clusters dynamically by scanning project structure
        val clusters = detectClustersDynamically()
        confidenceMap["clusterDetection"] = if (clusters.isEmpty()) 0.0 else 0.80

        // Detect deployment pattern derived from cluster analysis
        val deploymentPattern = detectDeploymentPattern(clusters)
        confidenceMap["deploymentPattern"] = 0.70

        return ArchitectureSignature(
            buildSystem = buildSystemResult.buildSystem,
            buildTools = detectBuildTools(),
            moduleFrameworks = moduleFrameworks,
            libraries = detectLibraries(),
            modulePatterns = modulePatternResult.modulePatterns,
            clusters = clusters,
            moduleDesignPatterns = moduleDesignPatterns,
            moduleLanguageFeatures = moduleLanguageFeatures,
            deploymentPattern = deploymentPattern,
            confidence = confidenceMap
        )
    }

    /**
     * Identify modules in the project
     */
    private fun identifyModules(projectRoot: String): List<String> {
        val root = File(projectRoot)
        val modules = mutableListOf<String>()

        // Top-level directories as modules
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { dir ->
            modules.add(dir.name)
        }

        // Nested modules (e.g., core/*)
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { dir ->
            dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { subdir ->
                modules.add("${dir.name}/${subdir.name}")
            }
        }

        return modules
    }

    private fun detectBuildTools(): List<BuildTool> {
        val root = File(projectRoot)
        val tools = mutableListOf<BuildTool>()

        val buildFile = File(root, "build.gradle.kts")
        if (buildFile.exists()) {
            val content = buildFile.readText()
            if (content.contains("kapt")) tools.add(BuildTool.KAPT)
            if (content.contains("ksp")) tools.add(BuildTool.KSP)
            if (content.contains("docker") || content.contains("Docker")) tools.add(BuildTool.DOCKER)
            if (content.contains("compose")) tools.add(BuildTool.COMPOSE)
        }

        return tools
    }

    private fun detectLibraries(): List<Library> {
        val root = File(projectRoot)
        val libraries = mutableListOf<Library>()

        val buildFile = File(root, "build.gradle.kts")
        if (buildFile.exists()) {
            val content = buildFile.readText()
            if (content.contains("exposed")) libraries.add(Library.EXPOSED)
            if (content.contains("hibernate")) libraries.add(Library.HIBERNATE)
            if (content.contains("redis")) libraries.add(Library.REDIS)
            if (content.contains("kotlinx-coroutines")) libraries.add(Library.KOTLINX_COROUTINES)
            if (content.contains("kotlinx-serialization")) libraries.add(Library.KOTLINX_SERIALIZATION)
            if (content.contains("jackson")) libraries.add(Library.JACKSON)
            if (content.contains("gson")) libraries.add(Library.GSON)
            if (content.contains("snakyaml")) libraries.add(Library.SNAKEYAML)
        }

        return libraries
    }

    private fun detectDesignPatternsInModule(modulePath: File): List<DesignPattern> {
        val patterns = mutableListOf<DesignPattern>()

        val srcDirs = listOf(
            File(modulePath, "src/main/kotlin"),
            File(modulePath, "src/main/java")
        )

        for (srcDir in srcDirs) {
            if (srcDir.exists()) {
                val files = srcDir.walkTopDown()
                    .filter { it.isFile && it.extension in setOf("kt", "java") }
                    .toList()

                for (file in files) {
                    val content = file.readText()
                    if (content.contains("Agent") && content.contains("orchestrator")) {
                        if (!patterns.contains(DesignPattern.AGENT)) patterns.add(DesignPattern.AGENT)
                    }
                    if (content.contains("Pipeline") || content.contains("pipeline")) {
                        if (!patterns.contains(DesignPattern.PIPELINE)) patterns.add(DesignPattern.PIPELINE)
                    }
                    if (content.contains("Event") || content.contains("event")) {
                        if (!patterns.contains(DesignPattern.EVENT_DRIVEN)) patterns.add(DesignPattern.EVENT_DRIVEN)
                    }
                    if (content.contains("Strategy") || content.contains("strategy")) {
                        if (!patterns.contains(DesignPattern.STRATEGY)) patterns.add(DesignPattern.STRATEGY)
                    }
                    if (content.contains("Factory") || content.contains("factory")) {
                        if (!patterns.contains(DesignPattern.FACTORY)) patterns.add(DesignPattern.FACTORY)
                    }
                    if (content.contains("Builder") || content.contains("builder")) {
                        if (!patterns.contains(DesignPattern.BUILDER)) patterns.add(DesignPattern.BUILDER)
                    }
                    if (content.contains("Observer") || content.contains("observer")) {
                        if (!patterns.contains(DesignPattern.OBSERVER)) patterns.add(DesignPattern.OBSERVER)
                    }
                }
            }
        }

        return patterns
    }

    private fun detectLanguageFeaturesInModule(modulePath: File): List<LanguageFeature> {
        val features = mutableListOf<LanguageFeature>()

        val srcDirs = listOf(
            File(modulePath, "src/main/kotlin"),
            File(modulePath, "src/main/java")
        )

        for (srcDir in srcDirs) {
            if (srcDir.exists() && srcDir.name.contains("kotlin")) {
                val files = srcDir.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .toList()

                for (file in files) {
                    val content = file.readText()
                    if (content.contains("suspend") || content.contains("suspend ")) {
                        if (!features.contains(LanguageFeature.SUSPEND)) features.add(LanguageFeature.SUSPEND)
                    }
                    if (content.contains("Flow") || content.contains("flow ")) {
                        if (!features.contains(LanguageFeature.FLOW)) features.add(LanguageFeature.FLOW)
                    }
                    if (content.contains("Sequence") || content.contains("sequence ")) {
                        if (!features.contains(LanguageFeature.SEQUENCE)) features.add(LanguageFeature.SEQUENCE)
                    }
                    if (content.contains("data class")) {
                        if (!features.contains(LanguageFeature.DATA_CLASSES)) features.add(LanguageFeature.DATA_CLASSES)
                    }
                    if (content.contains("fun ")) {
                        if (!features.contains(LanguageFeature.EXTENSION_FUNCTIONS)) features.add(LanguageFeature.EXTENSION_FUNCTIONS)
                    }
                }

                // Kotlin projects typically use coroutines
                if (features.isNotEmpty() || files.isNotEmpty()) {
                    if (!features.contains(LanguageFeature.COROUTINES)) features.add(LanguageFeature.COROUTINES)
                }
            }
        }

        return features
    }

    /**
     * Detect clusters dynamically using directory structure, build module boundaries, and import density
     * Clusters are derived from the project structure without hardcoding paths
     */
    private fun detectClustersDynamically(): List<ClusterInfo> {
        // If we have an index provider, use its cluster detection via reflection
        if (indexProvider != null) {
            try {
                val findClustersMethod = indexProvider.javaClass.getMethod("findClusters", String::class.java)
                val clusters = findClustersMethod.invoke(indexProvider, "") as? List<*>
                if (clusters != null) {
                    return clusters.mapNotNull { cluster ->
                        try {
                            val clusterClass = cluster?.javaClass ?: return@mapNotNull null
                            val nameMethod = clusterClass.getMethod("getName")
                            val name = nameMethod.invoke(cluster) as? String ?: return@mapNotNull null
                            val filesMethod = clusterClass.getMethod("getFiles")
                            val files = filesMethod.invoke(cluster) as? List<*> ?: return@mapNotNull null
                            ClusterInfo(name, files.size)
                        } catch (e: Exception) {
                            log.warn("[SIGNATURE_BUILDER] Failed to extract cluster info: ${e.message}")
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn("[SIGNATURE_BUILDER] Failed to use index provider for cluster detection: ${e.message}")
            }
        }

        // Otherwise, use the existing file-based detection
        val root = File(projectRoot)
        val clusters = mutableListOf<ClusterInfo>()

        // Detect build module boundaries (Gradle, Maven, etc.)
        val buildModules = detectBuildModules(root)

        // If build modules detected, use them as clusters
        if (buildModules.isNotEmpty()) {
            for (module in buildModules) {
                val modulePath = File(root, module)
                val fileCount = modulePath.walkTopDown().count { it.isFile }
                if (fileCount > 0) {
                    clusters.add(ClusterInfo(module, fileCount))
                }
            }
        } else {
            // Fallback to directory structure clustering
            val topDirs =
                root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.toList() ?: emptyList()

            for (dir in topDirs) {
                // Check if this directory contains source files
                val sourceFileCount = countSourceFiles(dir)
                if (sourceFileCount > 0) {
                    clusters.add(ClusterInfo(dir.name, sourceFileCount))
                }

                // Also check subdirectories for nested clusters
                val subdirs =
                    dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.toList() ?: emptyList()
                for (subdir in subdirs) {
                    val subSourceFileCount = countSourceFiles(subdir)
                    if (subSourceFileCount > 0) {
                        val relativePath = "${dir.name}/${subdir.name}"
                        clusters.add(ClusterInfo(relativePath, subSourceFileCount))
                    }
                }
            }
        }

        return clusters.sortedByDescending { it.fileCount }
    }

    /**
     * Detect build modules from build configuration files
     */
    private fun detectBuildModules(root: File): List<String> {
        val modules = mutableListOf<String>()

        // Check for Gradle multi-module project
        val settingsFile = File(root, "settings.gradle.kts").takeIf { it.exists() }
            ?: File(root, "settings.gradle").takeIf { it.exists() }

        if (settingsFile != null) {
            val content = settingsFile.readText()
            // Extract module names from settings.gradle.kts
            val modulePattern = Regex("""include\("([^"]+)"\)""")
            modulePattern.findAll(content).forEach { match ->
                val moduleName = match.groupValues[1].removePrefix(":")
                // Convert Gradle module path (with colons) to directory path (with slashes)
                // e.g., "core:orchestrator" -> "core/orchestrator"
                val directoryPath = moduleName.replace(":", "/")
                modules.add(directoryPath)
            }
        }

        // Check for Maven multi-module project
        val pomFile = File(root, "pom.xml").takeIf { it.exists() }
        if (pomFile != null) {
            val content = pomFile.readText()
            if (content.contains("<modules>")) {
                val modulePattern = Regex("""<module>([^<]+)</module>""")
                modulePattern.findAll(content).forEach { match ->
                    modules.add(match.groupValues[1].trim())
                }
            }
        }

        return modules
    }

    /**
     * Count source files in a directory
     */
    private fun countSourceFiles(dir: File): Int {
        return dir.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension in setOf("kt", "java", "scala", "groovy", "py", "js", "ts", "go", "rs") }
            .count()
    }

    /**
     * Calculate confidence score for module architecture detection
     * Based on: frameworks detected, design patterns detected, language features detected
     */
    private fun calculateModuleConfidence(
        module: String,
        frameworks: List<Framework>?,
        designPatterns: List<DesignPattern>?,
        languageFeatures: List<LanguageFeature>?
    ): Double {
        var confidence = 0.0
        var factors = 0

        // Frameworks contribute to confidence
        if (!frameworks.isNullOrEmpty()) {
            confidence += 0.3
            factors++
        }

        // Design patterns contribute to confidence
        if (!designPatterns.isNullOrEmpty()) {
            confidence += 0.35
            factors++
        }

        // Language features contribute to confidence
        if (!languageFeatures.isNullOrEmpty()) {
            confidence += 0.35
            factors++
        }

        // If no factors, return low confidence
        return if (factors == 0) 0.3 else confidence
    }

    private fun detectDeploymentPattern(clusters: List<ClusterInfo>): DeploymentPattern {
        val validClusters = clusters.filter { it.fileCount > 0 }
        return when {
            validClusters.isEmpty() -> DeploymentPattern.UNKNOWN
            validClusters.size == 1 -> DeploymentPattern.MONOLITH
            hasMultipleBuildModules() && validClusters.size >= 50 -> DeploymentPattern.MICROSERVICES
            hasMultipleBuildModules() -> DeploymentPattern.MODULAR_MONOLITH  // Default for multi-module Gradle
            else -> DeploymentPattern.MODULAR_MONOLITH
        }
    }

    private fun hasMultipleBuildModules(): Boolean {
        val root = File(projectRoot)
        val hasSettings = File(root, "settings.gradle.kts").exists() || File(root, "settings.gradle").exists()
        val hasPomModules = File(root, "pom.xml").exists() && File(root, "pom.xml").readText().contains("<modules>")
        return hasSettings || hasPomModules
    }
}
