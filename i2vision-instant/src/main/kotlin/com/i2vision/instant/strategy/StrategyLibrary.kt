package com.i2vision.instant.strategy

import org.slf4j.LoggerFactory

/**
 * Simplified strategy library for i2vision-instant
 * Provides strategy-aware context generation and suggestions
 */
object StrategyLibrary {
    
    private val log = LoggerFactory.getLogger(StrategyLibrary::class.java)
    
    enum class StrategyCategory {
        ARCHITECTURE,
        DOCUMENTATION,
        ANALYSIS,
        DEVELOPMENT,
        QUALITY
    }
    
    data class Strategy(
        val id: String,
        val category: StrategyCategory,
        val name: String,
        val description: String,
        val icon: String,
        val prompt: String,
        val estimatedTime: String
    )
    
    data class StrategySuggestion(
        val id: String,
        val name: String,
        val description: String,
        val priority: String,
        val icon: String
    )
    
    /**
     * Get all available strategies
     */
    fun getAllStrategies(): List<Strategy> {
        return strategies
    }
    
    /**
     * Find strategy by ID
     */
    fun findStrategy(id: String): Strategy? {
        return strategies.find { it.id == id }
    }
    
    /**
     * Detect strategy from task hint
     */
    fun detectStrategyFromTask(taskHint: String): Strategy? {
        return when (taskHint.lowercase()) {
            "debug", "debugging", "error", "troubleshoot" -> 
                findStrategy("gap-analysis")
            "refactor", "refactoring", "restructure", "cleanup" -> 
                findStrategy("component-boundaries")
            "feature", "development", "implement", "enhance" -> 
                findStrategy("architecture-overview") 
            "test", "testing", "coverage", "quality" ->
                findStrategy("test-coverage")
            "security", "audit", "vulnerability" ->
                findStrategy("security-audit")
            "performance", "optimize", "speed" ->
                findStrategy("performance-analysis")
            "documentation", "docs", "readme" ->
                findStrategy("quick-start")
            "architecture", "design", "structure" ->
                findStrategy("architecture-overview")
            else -> null
        }
    }
    
    /**
     * Get suggested strategies based on confidence and context
     */
    fun getSuggestedStrategies(confidence: Double, fileType: String = "unknown"): List<StrategySuggestion> {
        val baseSuggestions = when {
            confidence < 0.3 -> listOf(
                StrategySuggestion("quick-start", "Generate documentation", "Low context confidence - create foundational documentation", "high", "rocket"),
                StrategySuggestion("component-boundaries", "Identify structure", "Medium", "architecture", "architecture"),
                StrategySuggestion("gap-analysis", "Find missing context", "High", "search", "search")
            )
            confidence < 0.6 -> listOf(
                StrategySuggestion("gap-analysis", "Find missing context", "High", "chart", "chart"),
                StrategySuggestion("drift-detection", "Check consistency", "Medium", "target", "target"),
                StrategySuggestion("dependency-audit", "Review dependencies", "Medium", "link", "link")
            )
            confidence < 0.8 -> listOf(
                StrategySuggestion("architecture-overview", "Broader architectural view", "Medium", "architecture", "architecture"),
                StrategySuggestion("api-contracts", "Validate interfaces", "Medium", "api", "api"),
                StrategySuggestion("performance-analysis", "Optimize performance", "Low", "performance", "performance")
            )
            else -> listOf(
                StrategySuggestion("security-audit", "Security review", "Low", "security", "security"),
                StrategySuggestion("test-coverage", "Enhance testing", "Low", "tests", "tests"),
                StrategySuggestion("maintainability-check", "Code health review", "Low", "maintainability", "maintainability")
            )
        }
        
        return baseSuggestions + getFileTypeSpecificSuggestions(fileType)
    }
    
    private fun getFileTypeSpecificSuggestions(fileType: String): List<StrategySuggestion> {
        return when (fileType.lowercase()) {
            "kotlin", "kt" -> listOf(
                StrategySuggestion("code-quality-check", "Kotlin code analysis", "medium", "quality", "quality"),
                StrategySuggestion("test-coverage", "Unit test analysis", "low", "tests", "tests")
            )
            "yaml", "yml" -> listOf(
                StrategySuggestion("configuration-audit", "Config validation", "medium", "config", "config"),
                StrategySuggestion("security-audit", "Config security check", "high", "security", "security")
            )
            "gradle", "gradle.kts" -> listOf(
                StrategySuggestion("dependency-audit", "Build dependency analysis", "high", "link", "link"),
                StrategySuggestion("build-optimization", "Build performance", "medium", "performance", "performance")
            )
            else -> emptyList()
        }
    }
    
    // Pre-defined strategies
    private val strategies = listOf(
        // Architecture Strategies
        Strategy(
            id = "component-boundaries",
            category = StrategyCategory.ARCHITECTURE,
            name = "Component Boundaries",
            description = "Identify natural module boundaries and separation of concerns",
            icon = "architecture",
            prompt = "Analyze the codebase to identify natural component boundaries. Look for: 1) High cohesion within modules, 2) Low coupling between modules, 3) Clear interface contracts, 4) Single Responsibility Principle violations. Suggest optimal module decomposition.",
            estimatedTime = "3-5 min"
        ),
        Strategy(
            id = "dependency-audit",
            category = StrategyCategory.ARCHITECTURE,
            name = "Dependency Audit",
            description = "Find circular dependencies and coupling issues",
            icon = "link",
            prompt = "Perform a dependency audit: identify circular dependencies, analyze import chains, find tightly-coupled components, and suggest dependency inversion or abstraction opportunities. Generate a dependency graph.",
            estimatedTime = "2-3 min"
        ),
        Strategy(
            id = "api-contracts",
            category = StrategyCategory.ARCHITECTURE,
            name = "API Contracts",
            description = "Extract and validate public interfaces",
            icon = "api",
            prompt = "Extract all public APIs, endpoints, and interfaces. Document request/response schemas, validation rules, error handling patterns, and versioning strategy. Identify breaking changes and compatibility issues.",
            estimatedTime = "2-3 min"
        ),
        // Documentation Strategies
        Strategy(
            id = "quick-start",
            category = StrategyCategory.DOCUMENTATION,
            name = "Quick Start Guide",
            description = "Generate onboarding documentation",
            icon = "rocket",
            prompt = "Generate a Quick Start Guide for new developers: include setup instructions, key entry points, common workflows, important conventions, and links to relevant documentation. Focus on getting productive quickly.",
            estimatedTime = "2-3 min"
        ),
        Strategy(
            id = "architecture-overview",
            category = StrategyCategory.DOCUMENTATION,
            name = "Architecture Overview",
            description = "High-level system diagram and explanation",
            icon = "architecture",
            prompt = "Create an architecture overview: describe the system's purpose, main components, data flow, external dependencies, and deployment structure. Generate diagrams showing component relationships.",
            estimatedTime = "3-5 min"
        ),
        // Analysis Strategies
        Strategy(
            id = "gap-analysis",
            category = StrategyCategory.ANALYSIS,
            name = "Gap Analysis",
            description = "Find missing documentation and coverage gaps",
            icon = "search",
            prompt = "Perform a gap analysis: identify code without documentation, missing flow diagrams, untested scenarios, and incomplete specifications. Calculate coverage by layer and prioritize gaps.",
            estimatedTime = "2-3 min"
        ),
        Strategy(
            id = "drift-detection",
            category = StrategyCategory.ANALYSIS,
            name = "Drift Detection",
            description = "Code vs documentation inconsistency check",
            icon = "target",
            prompt = "Check for drift between code and documentation: compare implementation against stored artifacts, identify stale diagrams, flag API changes not reflected in docs, and list outdated comments.",
            estimatedTime = "1-2 min"
        ),
        // Quality Strategies
        Strategy(
            id = "security-audit",
            category = StrategyCategory.QUALITY,
            name = "Security Audit",
            description = "Find security vulnerabilities and anti-patterns",
            icon = "security",
            prompt = "Perform a security audit: identify injection risks, authentication gaps, sensitive data exposure, insecure dependencies, and common vulnerability patterns. Suggest remediation steps.",
            estimatedTime = "3-5 min"
        ),
        Strategy(
            id = "performance-analysis",
            category = StrategyCategory.QUALITY,
            name = "Performance Analysis",
            description = "Identify bottlenecks and optimization opportunities",
            icon = "performance",
            prompt = "Analyze performance characteristics: identify inefficient algorithms, blocking operations, memory leaks, and resource contention. Suggest optimization strategies with trade-offs.",
            estimatedTime = "3-5 min"
        ),
        Strategy(
            id = "test-coverage",
            category = StrategyCategory.QUALITY,
            name = "Test Coverage Analysis",
            description = "Map requirements to test coverage",
            icon = "tests",
            prompt = "Analyze test coverage: map business flows to test cases, identify untested scenarios, calculate coverage by layer, and suggest missing test cases for edge conditions.",
            estimatedTime = "2-3 min"
        )
    )
}
