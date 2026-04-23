package com.i2vision.arch.signature

import java.time.Instant

/**
 * Multi-dimensional architecture signature for project analysis.
 * 
 * This signature captures architecture information across multiple dimensions:
 * - Build level: build system and tools (global)
 * - Framework level: frameworks and libraries (per-module)
 * - Module level: per-module patterns (per-module)
 * - Cluster level: source code clusters for organization (global)
 * - Code level: design patterns and language features (per-module)
 * - Deployment level: deployment patterns (global)
 * 
 * IMPORTANT: A single project can have multiple architectural patterns simultaneously
 * across different modules. This signature supports multi-dimensional architecture detection.
 * 
 * Used by both discovery and development engines to understand and work with
 * project architecture.
 */
data class ArchitectureSignature(
    // Build level (global - same for entire project)
    val buildSystem: BuildSystem?,
    val buildTools: List<BuildTool> = emptyList(),

    // Framework level (per-module - different modules can use different frameworks)
    val moduleFrameworks: Map<String, List<Framework>> = emptyMap(),
    val libraries: List<Library> = emptyList(),

    // Module level (per-module - different modules can have different patterns)
    val modulePatterns: Map<String, ModulePattern> = emptyMap(),

    // Cluster level (global - source code clusters for organization)
    val clusters: List<ClusterInfo> = emptyList(),

    // Code level (per-module - different modules can use different design patterns)
    val moduleDesignPatterns: Map<String, List<DesignPattern>> = emptyMap(),
    val moduleLanguageFeatures: Map<String, List<LanguageFeature>> = emptyMap(),

    // Deployment level (global - overall deployment pattern)
    val deploymentPattern: DeploymentPattern = DeploymentPattern.UNKNOWN,

    // Meta
    val confidence: Map<String, Double> = emptyMap(),
    val detectedAt: Instant = Instant.now()
) {
    /**
     * Check if signature has a specific framework in any module
     */
    fun hasFramework(framework: Framework): Boolean {
        return moduleFrameworks.values.any { it.contains(framework) }
    }

    /**
     * Check if a specific module has a framework
     */
    fun moduleHasFramework(module: String, framework: Framework): Boolean {
        return moduleFrameworks[module]?.contains(framework) ?: false
    }

    /**
     * Check if signature has a specific module pattern
     */
    fun hasModulePattern(pattern: ModulePattern): Boolean {
        return modulePatterns.values.contains(pattern)
    }

    /**
     * Check if signature has a specific design pattern in any module
     */
    fun hasDesignPattern(pattern: DesignPattern): Boolean {
        return moduleDesignPatterns.values.any { it.contains(pattern) }
    }

    /**
     * Check if a specific module has a design pattern
     */
    fun moduleHasDesignPattern(module: String, pattern: DesignPattern): Boolean {
        return moduleDesignPatterns[module]?.contains(pattern) ?: false
    }

    /**
     * Get all frameworks across all modules
     */
    fun getAllFrameworks(): Set<Framework> {
        return moduleFrameworks.values.flatten().toSet()
    }

    /**
     * Get all design patterns across all modules
     */
    fun getAllDesignPatterns(): Set<DesignPattern> {
        return moduleDesignPatterns.values.flatten().toSet()
    }

    /**
     * Get all language features across all modules
     */
    fun getAllLanguageFeatures(): Set<LanguageFeature> {
        return moduleLanguageFeatures.values.flatten().toSet()
    }

    /**
     * Get confidence score for a specific category
     */
    fun getConfidence(category: String): Double {
        return confidence[category] ?: 0.0
    }

    /**
     * Get architecture signature for a specific module
     */
    fun getModuleSignature(module: String): ModuleSignature? {
        val patterns = modulePatterns[module]
        val frameworks = moduleFrameworks[module]
        val designPatterns = moduleDesignPatterns[module]
        val languageFeatures = moduleLanguageFeatures[module]

        if (patterns == null && frameworks == null && designPatterns == null && languageFeatures == null) {
            return null
        }

        return ModuleSignature(
            module = module,
            modulePattern = patterns,
            frameworks = frameworks ?: emptyList(),
            designPatterns = designPatterns ?: emptyList(),
            languageFeatures = languageFeatures ?: emptyList()
        )
    }
}

/**
 * Architecture signature for a single module
 */
data class ModuleSignature(
    val module: String,
    val modulePattern: ModulePattern?,
    val frameworks: List<Framework>,
    val designPatterns: List<DesignPattern>,
    val languageFeatures: List<LanguageFeature>
)

/**
 * Cluster information for source code organization
 */
data class ClusterInfo(
    val name: String,
    val fileCount: Int
)

/**
 * Build system types
 */
enum class BuildSystem {
    GRADLE_KTS,
    GRADLE_GROOVY,
    MAVEN,
    NPM,
    CARGO,
    GO,
    UNKNOWN
}

/**
 * Build tools
 */
enum class BuildTool {
    KAPT,
    KSP,
    DOCKER,
    COMPOSE,
    GRADLE_PLUGIN,
    MAVEN_PLUGIN,
    NPM_SCRIPT,
    CARGO_PLUGIN,
    UNKNOWN
}

/**
 * Framework types
 */
enum class Framework {
    SPRING_BOOT,
    KTOR,
    REACT,
    ANGULAR,
    VUE,
    COMPOSE_DESKTOP,
    COMPOSE_MULTIPLATFORM,
    FLUTTER,
    SWING,
    JAVA_FX,
    UNKNOWN
}

/**
 * Library types
 */
enum class Library {
    EXPOSED,
    HIBERNATE,
    REDIS,
    KOTLINX_COROUTINES,
    KOTLINX_SERIALIZATION,
    JACKSON,
    GSON,
    SNAKEYAML,
    UNKNOWN
}

/**
 * Module pattern types
 */
enum class ModulePattern {
    HEXAGONAL,
    LAYERED,
    AGENT_FRAMEWORK,
    MICROSERVICES,
    MVC,
    MVP,
    MVVM,
    CLEAN_ARCHITECTURE,
    UNKNOWN
}

/**
 * Design pattern types
 */
enum class DesignPattern {
    AGENT,
    PIPELINE,
    EVENT_DRIVEN,
    STRATEGY,
    FACTORY,
    BUILDER,
    OBSERVER,
    SINGLETON,
    UNKNOWN
}

/**
 * Language feature types
 */
enum class LanguageFeature {
    COROUTINES,
    SUSPEND,
    FLOW,
    SEQUENCE,
    GENERICS,
    EXTENSION_FUNCTIONS,
    DATA_CLASSES,
    UNKNOWN
}

/**
 * Deployment pattern types
 */
enum class DeploymentPattern {
    MONOLITH,
    MODULAR_MONOLITH,
    MICROSERVICES,
    AGGREGATOR,
    SERVERLESS,
    UNKNOWN
}
