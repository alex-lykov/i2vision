/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.architecture

import org.slf4j.LoggerFactory
import java.io.File

/**
 * ArchitectureDetector - Enhanced multi-technology stack detector.
 * 
 * Detects and analyzes complex projects with multiple languages, frameworks,
 * and architectural patterns. Provides technology-specific entry point patterns
 * for intelligent code discovery.
 *
 * Supports:
 * - Multi-language detection (Kotlin, Java, TypeScript, Python, Go, etc.)
 * - Multiple frameworks (Spring Boot, Ktor, React, Vue, etc.)
 * - Multiple platforms (Backend, Frontend, CLI, Desktop, Mobile, Infrastructure)
 * - Architecture patterns (Layered, Hexagonal, Clean, Microservices, etc.)
 * - Per-module analysis for multi-module projects
 */
class ArchitectureDetector(private val projectRoot: String) {

    private val log = LoggerFactory.getLogger(ArchitectureDetector::class.java)

    // ── Data Models ───────────────────────────────────────────────────────

    /**
     * Complete technology stack representation for complex projects.
     */
    data class TechnologyStack(
        val primaryLanguage: Language,
        val secondaryLanguages: List<Language>,
        val frameworks: List<Framework>,
        val platforms: List<Platform>,
        val patterns: List<ArchitecturePattern>,
        val modules: List<Module>,
        val entryPointPatterns: List<String>,
        val confidence: Double,
        val indicators: List<String>
    )

    /**
     * Module-level analysis for multi-module projects.
     */
    data class Module(
        val name: String,
        val path: String,
        val primaryLanguage: Language,
        val frameworks: List<Framework>,
        val platforms: List<Platform>
    )

    enum class Language {
        KOTLIN, JAVA, TYPESCRIPT, JAVASCRIPT, PYTHON, GO, RUST, SWIFT, C_SHARP, UNKNOWN
    }

    enum class Framework {
        // Backend
        SPRING_BOOT, KTOR, MICRONAUT, QUARKUS,

        // Frontend
        REACT, VUE, ANGULAR,

        // CLI
        CLIKT, PICOCLI,

        // Desktop
        COMPOSE_DESKTOP, JAVAFX,

        // Mobile
        ANDROID, COMPOSE_MULTIPLATFORM,

        // Agent/AI
        AGENT_FRAMEWORK,

        // Other
        CUSTOM, UNKNOWN
    }

    enum class Platform {
        BACKEND, FRONTEND, CLI, DESKTOP, MOBILE, INFRASTRUCTURE, LIBRARY
    }

    enum class ArchitecturePattern {
        LAYERED, HEXAGONAL, CLEAN, MICROSERVICES, MODULAR_MONOLITH,
        EVENT_DRIVEN, P2P, AGENT_BASED, UNKNOWN
    }

    // ── Legacy Support (Deprecated) ───────────────────────────────────────

    /**
     * @deprecated Use TechnologyStack instead. Kept for backward compatibility.
     */
    @Deprecated("Use TechnologyStack", ReplaceWith("TechnologyStack"))
    data class ArchitectureResult(
        val type: ArchitectureType,
        val confidence: Double,
        val indicators: List<String>,
        val entryPointPatterns: List<String>,
        val secondaryType: ArchitectureType? = null
    )

    /**
     * @deprecated Use TechnologyStack for better multi-technology support.
     */
    @Deprecated("Use TechnologyStack model")
    enum class ArchitectureType {
        MONOLITH_WEB,
        MICROSERVICES,
        LIBRARY,
        CLI_TOOL,
        DESKTOP_APP,
        MOBILE_APP,
        AGENT_FRAMEWORK,
        MIXED,
        UNKNOWN
    }

    // ── Main Detection Methods ────────────────────────────────────────────

    /**
     * Detect complete technology stack with multi-language/framework support.
     * This is the primary API for enhanced architecture detection.
     */
    fun detectStack(): TechnologyStack {
        log.debug("[ARCH] Detecting technology stack for: {}", projectRoot)

        val indicators = mutableListOf<String>()

        // Discover all modules
        val modules = discoverModules()
        log.debug("[ARCH] Found {} modules", modules.size)

        // Aggregate languages from all modules
        val allLanguages = modules.flatMap { listOf(it.primaryLanguage) }
            .filter { it != Language.UNKNOWN }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }

        val primaryLanguage = allLanguages.firstOrNull() ?: Language.UNKNOWN
        val secondaryLanguages = allLanguages.drop(1)

        indicators.add("languages: ${allLanguages.joinToString()}")

        // Aggregate frameworks from all modules
        val frameworks = modules.flatMap { it.frameworks }.distinct()
        indicators.add("frameworks: ${frameworks.joinToString()}")

        // Aggregate platforms from all modules
        val platforms = modules.flatMap { it.platforms }.distinct()
        indicators.add("platforms: ${platforms.joinToString()}")

        // Detect architecture patterns
        val patterns = detectArchitecturePatterns(modules, indicators)
        indicators.add("patterns: ${patterns.joinToString()}")

        // Build entry point patterns from detected technologies
        val entryPointPatterns = buildEntryPointPatterns(frameworks, platforms, patterns)

        // Calculate overall confidence
        val confidence = calculateConfidence(allLanguages, frameworks, platforms)

        log.info("[ARCH] Technology Stack:")
        log.info("  Primary: {} + {}", primaryLanguage, frameworks.joinToString())
        log.info("  Platforms: {}", platforms.joinToString())
        log.info("  Patterns: {}", patterns.joinToString())
        log.info("  Modules: {}", modules.size)
        log.info("  Confidence: {:.2f}", confidence)

        return TechnologyStack(
            primaryLanguage = primaryLanguage,
            secondaryLanguages = secondaryLanguages,
            frameworks = frameworks,
            platforms = platforms,
            patterns = patterns,
            modules = modules,
            entryPointPatterns = entryPointPatterns,
            confidence = confidence,
            indicators = indicators
        )
    }

    /**
     * Legacy detection method for backward compatibility.
     * @deprecated Use detectStack() for better multi-technology support.
     */
    @Deprecated("Use detectStack()", ReplaceWith("detectStack()"))
    fun detect(): ArchitectureResult {
        log.debug("[ARCH] Detecting architecture for: {}", projectRoot)

        val indicators = mutableListOf<String>()
        val scores = mutableMapOf<ArchitectureType, Double>()

        // Run all detection heuristics
        detectWebFrameworks(scores, indicators)
        detectMicroservices(scores, indicators)
        detectLibrary(scores, indicators)
        detectCli(scores, indicators)
        detectDesktopApp(scores, indicators)
        detectMobileApp(scores, indicators)
        detectAgentFramework(scores, indicators)

        // Determine primary architecture
        val primaryEntry = scores.maxByOrNull { it.value }
        val primaryType = primaryEntry?.key ?: ArchitectureType.UNKNOWN
        val primaryScore = primaryEntry?.value ?: 0.0

        // Check for mixed architecture
        val significantTypes = scores.filter { it.value >= 0.3 }
        val secondaryType = if (significantTypes.size > 1) {
            significantTypes.filter { it.key != primaryType }
                .maxByOrNull { it.value }?.key
        } else null

        val finalType = if (significantTypes.size > 1) {
            ArchitectureType.MIXED
        } else {
            primaryType
        }

        val confidence = if (finalType == ArchitectureType.MIXED) {
            significantTypes.values.average()
        } else {
            primaryScore
        }

        val entryPatterns = getEntryPointPatterns(primaryType, secondaryType)

        log.info(
            "[ARCH] Detected: {} (confidence: {:.2f}, indicators: {})",
            finalType, confidence, indicators.size
        )
        log.debug("[ARCH] Indicators: {}", indicators.joinToString())

        return ArchitectureResult(
            type = finalType,
            confidence = confidence,
            indicators = indicators,
            entryPointPatterns = entryPatterns,
            secondaryType = secondaryType
        )
    }

    // ── Module Discovery ──────────────────────────────────────────────────

    private fun discoverModules(): List<Module> {
        val modules = mutableListOf<Module>()

        // Check for Gradle multi-module project
        val settingsFile = File(projectRoot, "settings.gradle.kts")
            .takeIf { it.exists() }
            ?: File(projectRoot, "settings.gradle").takeIf { it.exists() }

        if (settingsFile != null) {
            val includePattern = Regex("include\\s*\\(\\s*[\"']([^\"']+)[\"']")
            val moduleNames = includePattern.findAll(settingsFile.readText())
                .map { it.groupValues[1] }
                .toList()

            if (moduleNames.isNotEmpty()) {
                log.debug("[ARCH] Found {} modules in settings.gradle", moduleNames.size)
                moduleNames.forEach { moduleName ->
                    val modulePath = moduleName.replace(':', '/')
                    modules.add(analyzeModule(modulePath, moduleName))
                }
            } else {
                // Single module project
                modules.add(analyzeModule(".", "root"))
            }
        } else {
            // No Gradle multi-module, check for other structures
            // Check for Maven multi-module (pom.xml with <modules>)
            val pomFile = File(projectRoot, "pom.xml")
            if (pomFile.exists()) {
                val pomText = pomFile.readText()
                val modulePattern = Regex("<module>([^<]+)</module>")
                val mavenModules = modulePattern.findAll(pomText)
                    .map { it.groupValues[1] }
                    .toList()

                if (mavenModules.isNotEmpty()) {
                    log.debug("[ARCH] Found {} Maven modules", mavenModules.size)
                    mavenModules.forEach { moduleName ->
                        modules.add(analyzeModule(moduleName, moduleName))
                    }
                } else {
                    modules.add(analyzeModule(".", "root"))
                }
            } else {
                // Single module project
                modules.add(analyzeModule(".", "root"))
            }
        }

        return modules
    }

    private fun analyzeModule(modulePath: String, moduleName: String): Module {
        val moduleRoot = File(projectRoot, modulePath)

        if (!moduleRoot.exists()) {
            log.warn("[ARCH] Module path does not exist: {}", moduleRoot.absolutePath)
            return Module(
                name = moduleName,
                path = modulePath,
                primaryLanguage = Language.UNKNOWN,
                frameworks = emptyList(),
                platforms = emptyList()
            )
        }

        val languages = detectLanguagesInModule(moduleRoot)
        val frameworks = detectFrameworksInModule(moduleRoot)
        val platforms = detectPlatformsInModule(moduleRoot, frameworks)

        log.debug(
            "[ARCH] Module '{}': {} | {} | {}",
            moduleName,
            languages.firstOrNull() ?: Language.UNKNOWN,
            frameworks.joinToString(),
            platforms.joinToString()
        )

        return Module(
            name = moduleName,
            path = modulePath,
            primaryLanguage = languages.firstOrNull() ?: Language.UNKNOWN,
            frameworks = frameworks,
            platforms = platforms
        )
    }

    private fun detectLanguagesInModule(moduleRoot: File): List<Language> {
        val languageCounts = mutableMapOf<Language, Int>()

        moduleRoot.walk()
            .filter { it.isFile }
            .take(500) // Limit for performance
            .forEach { file ->
                when (file.extension) {
                    "kt" -> languageCounts[Language.KOTLIN] = languageCounts.getOrDefault(Language.KOTLIN, 0) + 1
                    "java" -> languageCounts[Language.JAVA] = languageCounts.getOrDefault(Language.JAVA, 0) + 1
                    "ts", "tsx" -> languageCounts[Language.TYPESCRIPT] =
                        languageCounts.getOrDefault(Language.TYPESCRIPT, 0) + 1

                    "js", "jsx" -> languageCounts[Language.JAVASCRIPT] =
                        languageCounts.getOrDefault(Language.JAVASCRIPT, 0) + 1

                    "py" -> languageCounts[Language.PYTHON] = languageCounts.getOrDefault(Language.PYTHON, 0) + 1
                    "go" -> languageCounts[Language.GO] = languageCounts.getOrDefault(Language.GO, 0) + 1
                    "rs" -> languageCounts[Language.RUST] = languageCounts.getOrDefault(Language.RUST, 0) + 1
                    "swift" -> languageCounts[Language.SWIFT] = languageCounts.getOrDefault(Language.SWIFT, 0) + 1
                    "cs" -> languageCounts[Language.C_SHARP] = languageCounts.getOrDefault(Language.C_SHARP, 0) + 1
                }
            }

        return languageCounts.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }

    private fun detectFrameworksInModule(moduleRoot: File): List<Framework> {
        val frameworks = mutableSetOf<Framework>()

        // Backend frameworks
        if (containsImportInModule(moduleRoot, "org.springframework.boot")) frameworks.add(Framework.SPRING_BOOT)
        if (containsImportInModule(moduleRoot, "io.ktor")) frameworks.add(Framework.KTOR)
        if (containsImportInModule(moduleRoot, "io.micronaut")) frameworks.add(Framework.MICRONAUT)
        if (containsImportInModule(moduleRoot, "io.quarkus")) frameworks.add(Framework.QUARKUS)

        // Frontend frameworks (check package.json)
        val packageJson = File(moduleRoot, "package.json")
        if (packageJson.exists()) {
            val packageText = packageJson.readText()
            if ("\"react\"" in packageText) frameworks.add(Framework.REACT)
            if ("\"vue\"" in packageText) frameworks.add(Framework.VUE)
            if ("\"@angular" in packageText) frameworks.add(Framework.ANGULAR)
        }

        // CLI frameworks
        if (containsImportInModule(moduleRoot, "com.github.ajalt.clikt")) frameworks.add(Framework.CLIKT)
        if (containsImportInModule(moduleRoot, "picocli")) frameworks.add(Framework.PICOCLI)

        // Desktop frameworks
        if (containsImportInModule(moduleRoot, "androidx.compose.desktop")) frameworks.add(Framework.COMPOSE_DESKTOP)
        if (containsImportInModule(moduleRoot, "javafx")) frameworks.add(Framework.JAVAFX)

        // Mobile frameworks
        if (File(moduleRoot, "AndroidManifest.xml").exists()) frameworks.add(Framework.ANDROID)
        if (containsImportInModule(moduleRoot, "androidx.compose")) frameworks.add(Framework.COMPOSE_MULTIPLATFORM)

        // Agent framework detection
        if (containsPatternInModule(moduleRoot, "class\\s+\\w+Agent") &&
            containsPatternInModule(moduleRoot, "class\\s+\\w+Orchestrator")
        ) {
            frameworks.add(Framework.AGENT_FRAMEWORK)
        }

        return frameworks.toList()
    }

    private fun detectPlatformsInModule(moduleRoot: File, frameworks: List<Framework>): List<Platform> {
        val platforms = mutableSetOf<Platform>()

        // Backend detection
        if (frameworks.any {
                it in listOf(
                    Framework.SPRING_BOOT,
                    Framework.KTOR,
                    Framework.MICRONAUT,
                    Framework.QUARKUS
                )
            }) {
            platforms.add(Platform.BACKEND)
        }

        // Frontend detection
        if (frameworks.any { it in listOf(Framework.REACT, Framework.VUE, Framework.ANGULAR) }) {
            platforms.add(Platform.FRONTEND)
        }

        // CLI detection
        if (containsPatternInModule(moduleRoot, "fun\\s+main\\s*\\(") &&
            !frameworks.any { it in listOf(Framework.SPRING_BOOT, Framework.KTOR) }
        ) {
            platforms.add(Platform.CLI)
        }

        // Desktop detection
        if (frameworks.any { it in listOf(Framework.COMPOSE_DESKTOP, Framework.JAVAFX) }) {
            platforms.add(Platform.DESKTOP)
        }

        // Mobile detection
        if (frameworks.contains(Framework.ANDROID) ||
            File(moduleRoot, "iosMain").exists()
        ) {
            platforms.add(Platform.MOBILE)
        }

        // Infrastructure detection
        if (moduleRoot.walk().any { it.extension == "tf" || it.extension == "tfvars" }) {
            platforms.add(Platform.INFRASTRUCTURE)
        }

        // Library detection (has publishing config, no main)
        val buildFiles = listOf(
            File(moduleRoot, "build.gradle.kts"),
            File(moduleRoot, "build.gradle"),
            File(moduleRoot, "pom.xml")
        )
        val hasPublishing = buildFiles.any { it.exists() && it.readText().contains("publish") }
        val hasMain = containsPatternInModule(moduleRoot, "fun\\s+main\\s*\\(")

        if (hasPublishing || (!hasMain && platforms.isEmpty())) {
            platforms.add(Platform.LIBRARY)
        }

        return platforms.toList()
    }

    private fun detectArchitecturePatterns(
        modules: List<Module>,
        indicators: MutableList<String>
    ): List<ArchitecturePattern> {
        val patterns = mutableSetOf<ArchitecturePattern>()

        // Microservices: Multiple modules with backend platform
        val backendModules = modules.filter { it.platforms.contains(Platform.BACKEND) }
        if (modules.size >= 3 && backendModules.size >= 2) {
            patterns.add(ArchitecturePattern.MICROSERVICES)
            indicators.add("microservices_detected")
        }

        // Modular Monolith: Multiple modules, single backend
        if (modules.size >= 2 && backendModules.size == 1 && modules.size < 5) {
            patterns.add(ArchitecturePattern.MODULAR_MONOLITH)
            indicators.add("modular_monolith_detected")
        }

        // Clean Architecture: Check for domain/application/infrastructure structure
        modules.forEach { module ->
            val moduleRoot = File(projectRoot, module.path)
            val hasDomain = containsDirectoryInModule(moduleRoot, "domain")
            val hasApplication = containsDirectoryInModule(moduleRoot, "application") ||
                    containsDirectoryInModule(moduleRoot, "usecase")
            val hasInfrastructure = containsDirectoryInModule(moduleRoot, "infrastructure") ||
                    containsDirectoryInModule(moduleRoot, "adapter")

            if (hasDomain && hasApplication && hasInfrastructure) {
                patterns.add(ArchitecturePattern.CLEAN)
                indicators.add("clean_architecture_detected")
            }
        }

        // Layered Architecture: controller/service/repository pattern
        modules.forEach { module ->
            val moduleRoot = File(projectRoot, module.path)
            val hasController = containsPatternInModule(moduleRoot, "class\\s+\\w+Controller")
            val hasService = containsPatternInModule(moduleRoot, "class\\s+\\w+Service")
            val hasRepository = containsPatternInModule(moduleRoot, "class\\s+\\w+Repository")

            if (hasController && hasService && hasRepository) {
                patterns.add(ArchitecturePattern.LAYERED)
                indicators.add("layered_architecture_detected")
            }
        }

        // Hexagonal/Ports & Adapters: port/adapter naming
        modules.forEach { module ->
            val moduleRoot = File(projectRoot, module.path)
            val hasPort = containsDirectoryInModule(moduleRoot, "port") ||
                    containsPatternInModule(moduleRoot, "interface\\s+\\w+Port")
            val hasAdapter = containsDirectoryInModule(moduleRoot, "adapter") ||
                    containsPatternInModule(moduleRoot, "class\\s+\\w+Adapter")

            if (hasPort && hasAdapter) {
                patterns.add(ArchitecturePattern.HEXAGONAL)
                indicators.add("hexagonal_architecture_detected")
            }
        }

        // Event-Driven: Event/EventHandler patterns
        val hasEvents = modules.any { module ->
            val moduleRoot = File(projectRoot, module.path)
            containsPatternInModule(moduleRoot, "class\\s+\\w+Event") &&
                    containsPatternInModule(moduleRoot, "class\\s+\\w+EventHandler")
        }
        if (hasEvents) {
            patterns.add(ArchitecturePattern.EVENT_DRIVEN)
            indicators.add("event_driven_detected")
        }

        // Agent-Based: Agent/Orchestrator patterns
        val hasAgentPattern = modules.any { module ->
            val moduleRoot = File(projectRoot, module.path)
            containsPatternInModule(moduleRoot, "class\\s+\\w+Agent") &&
                    containsPatternInModule(moduleRoot, "class\\s+\\w+Orchestrator")
        }
        if (hasAgentPattern) {
            patterns.add(ArchitecturePattern.AGENT_BASED)
            indicators.add("agent_based_detected")
        }

        if (patterns.isEmpty()) {
            patterns.add(ArchitecturePattern.UNKNOWN)
        }

        return patterns.toList()
    }

    private fun buildEntryPointPatterns(
        frameworks: List<Framework>,
        platforms: List<Platform>,
        patterns: List<ArchitecturePattern>
    ): List<String> {
        val entryPatterns = mutableSetOf<String>()

        // Framework-specific patterns
        frameworks.forEach { framework ->
            when (framework) {
                Framework.SPRING_BOOT -> entryPatterns.addAll(
                    listOf(
                        "@RestController", "@Controller", "@Service", "@Repository", "@Component"
                    )
                )

                Framework.KTOR -> entryPatterns.addAll(
                    listOf(
                        "routing\\s*\\{", "fun\\s+Application\\.\\w+\\(", "fun\\s+Route\\.\\w+\\("
                    )
                )

                Framework.REACT -> entryPatterns.addAll(
                    listOf(
                        "export\\s+(default\\s+)?function", "export\\s+const\\s+\\w+\\s*="
                    )
                )

                Framework.CLIKT, Framework.PICOCLI -> entryPatterns.addAll(
                    listOf(
                        "class\\s+\\w+Command", "fun\\s+main\\s*\\("
                    )
                )

                Framework.AGENT_FRAMEWORK -> entryPatterns.addAll(
                    listOf(
                        "class\\s+\\w+Agent", "class\\s+\\w+Orchestrator", "interface\\s+\\w+Service"
                    )
                )

                else -> {} // No specific patterns
            }
        }

        // Platform-specific patterns
        platforms.forEach { platform ->
            when (platform) {
                Platform.BACKEND -> entryPatterns.addAll(
                    listOf(
                        "class\\s+\\w+Controller", "class\\s+\\w+Service", "interface\\s+\\w+Repository"
                    )
                )

                Platform.CLI -> entryPatterns.addAll(
                    listOf(
                        "fun\\s+main\\s*\\(", "class\\s+\\w+Command"
                    )
                )

                Platform.LIBRARY -> entryPatterns.addAll(
                    listOf(
                        "class\\s+\\w+", "interface\\s+\\w+", "object\\s+\\w+", "fun\\s+\\w+\\("
                    )
                )

                else -> {} // No specific patterns
            }
        }

        // Pattern-specific entry points
        patterns.forEach { pattern ->
            when (pattern) {
                ArchitecturePattern.CLEAN -> entryPatterns.addAll(
                    listOf(
                        "interface\\s+\\w+UseCase", "class\\s+\\w+UseCase"
                    )
                )

                ArchitecturePattern.HEXAGONAL -> entryPatterns.addAll(
                    listOf(
                        "interface\\s+\\w+Port", "class\\s+\\w+Adapter"
                    )
                )

                ArchitecturePattern.EVENT_DRIVEN -> entryPatterns.addAll(
                    listOf(
                        "class\\s+\\w+Event", "class\\s+\\w+EventHandler"
                    )
                )

                else -> {} // No specific patterns
            }
        }

        // Fallback generic patterns
        if (entryPatterns.isEmpty()) {
            entryPatterns.addAll(
                listOf(
                    "class\\s+\\w+", "interface\\s+\\w+", "object\\s+\\w+", "fun\\s+main\\s*\\("
                )
            )
        }

        return entryPatterns.toList()
    }

    private fun calculateConfidence(
        languages: List<Language>,
        frameworks: List<Framework>,
        platforms: List<Platform>
    ): Double {
        var confidence = 0.0

        // Confidence from language detection
        if (languages.isNotEmpty()) confidence += 0.3

        // Confidence from framework detection
        when (frameworks.size) {
            0 -> confidence += 0.0
            1 -> confidence += 0.3
            in 2..3 -> confidence += 0.2 // Multiple frameworks = less confident
            else -> confidence += 0.1 // Too many frameworks = even less confident
        }

        // Confidence from platform detection
        if (platforms.isNotEmpty()) confidence += 0.2

        // Bonus for well-defined structure
        if (languages.size == 1 && frameworks.size == 1) confidence += 0.2

        return confidence.coerceIn(0.0, 1.0)
    }

    // ── Helper Methods (Module-Scoped) ────────────────────────────────────

    private fun containsImportInModule(moduleRoot: File, importPattern: String): Boolean {
        return moduleRoot.walk()
            .filter { it.isFile && it.extension in setOf("kt", "java", "ts", "tsx", "js", "jsx") }
            .take(100) // Performance limit
            .any { file ->
                try {
                    file.readLines().any { line ->
                        line.trim().startsWith("import") && line.contains(importPattern)
                    }
                } catch (e: Exception) {
                    false
                }
            }
    }

    private fun containsPatternInModule(moduleRoot: File, regex: String): Boolean {
        val pattern = Regex(regex)
        return moduleRoot.walk()
            .filter { it.isFile && it.extension in setOf("kt", "java", "ts", "tsx", "js", "jsx") }
            .take(100) // Performance limit
            .any { file ->
                try {
                    pattern.containsMatchIn(file.readText())
                } catch (e: Exception) {
                    false
                }
            }
    }

    private fun containsDirectoryInModule(moduleRoot: File, name: String): Boolean {
        return moduleRoot.walk()
            .filter { it.isDirectory }
            .any { it.name == name }
    }

    // ── Detection Heuristics (Legacy) ─────────────────────────────────────

    private fun detectWebFrameworks(scores: MutableMap<ArchitectureType, Double>, indicators: MutableList<String>) {
        var score = 0.0

        // Spring Boot
        if (hasFile("**/application.properties") || hasFile("**/application.yml")) {
            score += 0.2
            indicators.add("spring_config")
        }
        if (hasAnnotation("@SpringBootApplication")) {
            score += 0.3
            indicators.add("spring_boot_annotation")
        }
        if (hasAnnotation("@RestController") || hasAnnotation("@Controller")) {
            score += 0.2
            indicators.add("spring_controller")
        }

        // Ktor
        if (hasImport("io.ktor.server.engine")) {
            score += 0.2
            indicators.add("ktor_import")
        }
        if (hasPattern("embeddedServer\\(")) {
            score += 0.3
            indicators.add("ktor_embedded_server")
        }
        if (hasPattern("routing\\s*\\{")) {
            score += 0.2
            indicators.add("ktor_routing")
        }

        if (score > 0) {
            scores[ArchitectureType.MONOLITH_WEB] = score.coerceIn(0.0, 1.0)
        }
    }

    private fun detectMicroservices(scores: MutableMap<ArchitectureType, Double>, indicators: MutableList<String>) {
        var score = 0.0

        // Multi-module project structure
        val moduleCount = countModules()
        if (moduleCount >= 3) {
            score += 0.3
            indicators.add("multi_module($moduleCount)")
        }

        // Service-oriented patterns
        if (hasPattern("class\\s+\\w+Service") && moduleCount >= 2) {
            score += 0.2
            indicators.add("service_classes")
        }

        // API gateways / clients
        if (hasPattern("class\\s+\\w+(Client|Gateway)")) {
            score += 0.2
            indicators.add("service_communication")
        }

        // Microservice frameworks
        if (hasImport("org.springframework.cloud")) {
            score += 0.3
            indicators.add("spring_cloud")
        }

        if (score > 0) {
            scores[ArchitectureType.MICROSERVICES] = score.coerceIn(0.0, 1.0)
        }
    }

    private fun detectLibrary(scores: MutableMap<ArchitectureType, Double>, indicators: MutableList<String>) {
        var score = 0.0

        // Library markers - check for publishing configuration in gradle files
        val buildKts = File(projectRoot, "build.gradle.kts")
        val buildGradle = File(projectRoot, "build.gradle")
        val hasPublishInKts = buildKts.exists() && buildKts.readText().contains("publish")
        val hasPublishInGradle = buildGradle.exists() && buildGradle.readText().contains("publish")

        if (hasPublishInKts || hasPublishInGradle) {
            score += 0.3
            indicators.add("publish")
        }

        // Public API patterns
        if (hasPattern("@JvmName|@JvmStatic|@JvmField")) {
            score += 0.2
            indicators.add("jvm_interop")
        }

        // No main() function
        if (!hasPattern("fun\\s+main\\s*\\(")) {
            score += 0.2
            indicators.add("no_main")
        }

        // README with usage examples
        if (hasFile("README.md") && fileContains("README.md", "## Usage")) {
            score += 0.2
            indicators.add("library_readme")
        }

        if (score > 0) {
            scores[ArchitectureType.LIBRARY] = score.coerceIn(0.0, 1.0)
        }
    }

    private fun detectCli(scores: MutableMap<ArchitectureType, Double>, indicators: MutableList<String>) {
        var score = 0.0

        // CLI frameworks
        if (hasImport("com.github.ajalt.clikt") || hasImport("picocli")) {
            score += 0.4
            indicators.add("cli_framework")
        }

        // Main function
        if (hasPattern("fun\\s+main\\s*\\(")) {
            score += 0.2
            indicators.add("main_function")
        }

        // Command classes
        if (hasPattern("class\\s+\\w+Command")) {
            score += 0.3
            indicators.add("command_classes")
        }

        // Args parsing
        if (hasPattern("args:\\s*Array<String>")) {
            score += 0.1
            indicators.add("args_parsing")
        }

        if (score > 0) {
            scores[ArchitectureType.CLI_TOOL] = score.coerceIn(0.0, 1.0)
        }
    }

    private fun detectDesktopApp(scores: MutableMap<ArchitectureType, Double>, indicators: MutableList<String>) {
        var score = 0.0

        // Compose Desktop
        if (hasImport("androidx.compose.desktop")) {
            score += 0.5
            indicators.add("compose_desktop")
        }

        // JavaFX
        if (hasImport("javafx.application")) {
            score += 0.5
            indicators.add("javafx")
        }

        // Window/Application classes
        if (hasPattern("class\\s+\\w+(Window|Application)")) {
            score += 0.2
            indicators.add("window_classes")
        }

        if (score > 0) {
            scores[ArchitectureType.DESKTOP_APP] = score.coerceIn(0.0, 1.0)
        }
    }

    private fun detectMobileApp(scores: MutableMap<ArchitectureType, Double>, indicators: MutableList<String>) {
        var score = 0.0

        // Android
        if (hasFile("AndroidManifest.xml")) {
            score += 0.5
            indicators.add("android_manifest")
        }
        if (hasImport("android.app.Activity")) {
            score += 0.3
            indicators.add("android_activity")
        }

        // iOS / Kotlin Multiplatform
        if (hasDirectory("iosMain")) {
            score += 0.4
            indicators.add("ios_source")
        }

        if (score > 0) {
            scores[ArchitectureType.MOBILE_APP] = score.coerceIn(0.0, 1.0)
        }
    }

    private fun detectAgentFramework(scores: MutableMap<ArchitectureType, Double>, indicators: MutableList<String>) {
        var score = 0.0

        // Agent classes
        val agentCount = countPattern("class\\s+\\w+Agent")
        if (agentCount >= 3) {
            score += 0.3
            indicators.add("agent_classes($agentCount)")
        }

        // Orchestrator
        if (hasPattern("class\\s+\\w+Orchestrator")) {
            score += 0.25
            indicators.add("orchestrator")
        }

        // MCP or tool integration
        if (hasDirectory("mcp") || hasPattern("interface\\s+\\w+Tool")) {
            score += 0.2
            indicators.add("tool_system")
        }

        // Agent framework structure
        if (hasDirectory("agents") && hasDirectory("core")) {
            score += 0.25
            indicators.add("agent_framework_structure")
        }

        if (score > 0) {
            scores[ArchitectureType.AGENT_FRAMEWORK] = score.coerceIn(0.0, 1.0)
        }
    }

    // ── Entry Point Patterns ──────────────────────────────────────────────

    private fun getEntryPointPatterns(primary: ArchitectureType, secondary: ArchitectureType?): List<String> {
        val patterns = mutableListOf<String>()

        patterns.addAll(getPatternsForType(primary))

        if (secondary != null && secondary != primary) {
            patterns.addAll(getPatternsForType(secondary))
        }

        return patterns.distinct()
    }

    private fun getPatternsForType(type: ArchitectureType): List<String> {
        return when (type) {
            ArchitectureType.MONOLITH_WEB -> listOf(
                "@RestController",
                "@Controller",
                "@Service",
                "@Repository",
                "fun\\s+route\\w+\\(",
                "fun\\s+Application\\.\\w+\\(",
                "routing\\s*\\{"
            )

            ArchitectureType.MICROSERVICES -> listOf(
                "@RestController",
                "@Service",
                "class\\s+\\w+Client",
                "class\\s+\\w+Gateway",
                "interface\\s+\\w+Service"
            )

            ArchitectureType.LIBRARY -> listOf(
                "class\\s+\\w+",
                "interface\\s+\\w+",
                "object\\s+\\w+",
                "fun\\s+\\w+\\("
            )

            ArchitectureType.CLI_TOOL -> listOf(
                "fun\\s+main\\s*\\(",
                "class\\s+\\w+Command",
                "@Command"
            )

            ArchitectureType.DESKTOP_APP -> listOf(
                "class\\s+\\w+(Window|Application)",
                "fun\\s+main\\s*\\(",
                "@Composable\\s+fun"
            )

            ArchitectureType.MOBILE_APP -> listOf(
                "class\\s+\\w+Activity",
                "class\\s+\\w+Fragment",
                "class\\s+\\w+ViewModel",
                "@Composable\\s+fun"
            )

            ArchitectureType.AGENT_FRAMEWORK -> listOf(
                "class\\s+\\w+Agent",
                "class\\s+\\w+Orchestrator",
                "interface\\s+\\w+Service",
                "object\\s+\\w+",
                "fun\\s+route\\w+\\("
            )

            ArchitectureType.MIXED, ArchitectureType.UNKNOWN -> listOf(
                "class\\s+\\w+",
                "interface\\s+\\w+",
                "object\\s+\\w+",
                "fun\\s+main\\s*\\("
            )
        }
    }

    // ── Helper Methods ────────────────────────────────────────────────────

    private fun hasFile(pattern: String): Boolean {
        val root = File(projectRoot)
        return findFiles(root, pattern).isNotEmpty()
    }

    private fun hasDirectory(name: String): Boolean {
        return File(projectRoot).walk()
            .filter { it.isDirectory }
            .any { it.name == name }
    }

    private fun hasImport(importPattern: String): Boolean {
        return File(projectRoot).walk()
            .filter { it.extension == "kt" || it.extension == "java" }
            .take(100) // Limit search for performance
            .any { file ->
                file.readLines().any { line ->
                    line.trim().startsWith("import") && line.contains(importPattern)
                }
            }
    }

    private fun hasAnnotation(annotation: String): Boolean {
        return hasPattern(annotation.replace("@", "\\@"))
    }

    private fun hasPattern(regex: String): Boolean {
        val pattern = Regex(regex)
        return File(projectRoot).walk()
            .filter { it.extension == "kt" || it.extension == "java" }
            .take(100) // Limit search for performance
            .any { file ->
                file.readText().contains(pattern)
            }
    }

    private fun countPattern(regex: String): Int {
        val pattern = Regex(regex)
        return File(projectRoot).walk()
            .filter { it.extension == "kt" || it.extension == "java" }
            .sumOf { file ->
                pattern.findAll(file.readText()).count()
            }
    }

    private fun fileContains(filename: String, text: String): Boolean {
        val file = File(projectRoot, filename)
        return file.exists() && file.readText().contains(text)
    }

    private fun countModules(): Int {
        val settingsFile = File(projectRoot, "settings.gradle.kts")
            .takeIf { it.exists() }
            ?: File(projectRoot, "settings.gradle").takeIf { it.exists() }
            ?: return 1

        val includePattern = Regex("include\\s*\\(\\s*[\"']([^\"']+)[\"']")
        return includePattern.findAll(settingsFile.readText()).count()
    }

    private fun findFiles(root: File, pattern: String): List<File> {
        val globRegex = pattern
            .replace("**", ".*")
            .replace("*", "[^/]*")
            .toRegex()

        return root.walk()
            .filter { it.isFile }
            .filter { file ->
                val relativePath = file.relativeTo(root).path.replace("\\", "/")
                globRegex.matches(relativePath)
            }
            .toList()
    }
}


