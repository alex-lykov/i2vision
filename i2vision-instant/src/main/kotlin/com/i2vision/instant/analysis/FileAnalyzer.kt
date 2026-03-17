package com.i2vision.instant.analysis

import org.slf4j.LoggerFactory
import java.io.File

/**
 * File analyzer - provides advanced code analysis capabilities
 * Simplified implementations for complexity, patterns, code smells detection
 */
class FileAnalyzer(
    private val projectRoot: String
) {
    
    private val log = LoggerFactory.getLogger(FileAnalyzer::class.java)
    
    data class FileAnalysis(
        val filePath: String,
        val componentRole: String,
        val dependencies: List<String>,
        val couplingScore: Int,
        val patterns: List<String>,
        val complexityScore: Int,
        val testCoverage: Int,
        val codeSmells: List<String>,
        val maintainabilityIndex: Int
    )
    
    /**
     * Analyze a file and return comprehensive analysis data
     */
    fun analyzeFile(filePath: String): FileAnalysis {
        val absolutePath = if (File(filePath).isAbsolute) filePath else File(projectRoot, filePath).absolutePath
        val file = File(absolutePath)
        val content = if (file.exists()) file.readText() else ""
        
        return FileAnalysis(
            filePath = filePath,
            componentRole = detectComponentRole(filePath, content),
            dependencies = extractDependencies(content),
            couplingScore = calculateCouplingScore(content),
            patterns = detectPatterns(content),
            complexityScore = calculateComplexity(content),
            testCoverage = estimateTestCoverage(filePath),
            codeSmells = detectCodeSmells(content),
            maintainabilityIndex = calculateMaintainabilityIndex(content)
        )
    }
    
    /**
     * Detect the component role based on file path and content
     */
    fun detectComponentRole(filePath: String, content: String): String {
        return when {
            filePath.contains("orchestrator") -> "Orchestrator"
            filePath.contains("agent") -> "Agent"
            filePath.contains("service") -> "Service"
            filePath.contains("controller") -> "Controller"
            filePath.contains("repository") -> "Repository"
            content.contains("@Component") -> "Component"
            content.contains("class") && content.contains("interface") -> "Interface Implementation"
            content.contains("object") -> "Singleton"
            else -> "Utility"
        }
    }
    
    /**
     * Extract import dependencies from content
     */
    fun extractDependencies(content: String): List<String> {
        val imports = Regex("import\\s+([\\w.]+)").findAll(content)
        return imports.map { it.groupValues[1] }.take(10).toList()
    }
    
    /**
     * Calculate coupling score based on import count
     */
    fun calculateCouplingScore(content: String): Int {
        val imports = extractDependencies(content).size
        return when {
            imports > 20 -> 8  // High coupling
            imports > 10 -> 5  // Medium coupling
            imports > 5 -> 3   // Low coupling
            else -> 1          // Very low coupling
        }
    }
    
    /**
     * Detect design patterns in the code
     */
    fun detectPatterns(content: String): List<String> {
        val patterns = mutableListOf<String>()
        if (content.contains("Factory")) patterns.add("Factory")
        if (content.contains("Builder")) patterns.add("Builder")
        if (content.contains("Observer")) patterns.add("Observer")
        if (content.contains("Strategy")) patterns.add("Strategy")
        if (content.contains("suspend fun")) patterns.add("Coroutines")
        if (content.contains("interface") && content.contains("class")) patterns.add("Interface Implementation")
        if (content.contains("data class")) patterns.add("Data Class")
        if (content.contains("sealed class")) patterns.add("Sealed Class")
        return patterns
    }
    
    /**
     * Calculate complexity score based on branching
     */
    fun calculateComplexity(content: String): Int {
        val branches = Regex("(if|when|for|while)\\s*\\(").findAll(content).count()
        return (branches / 5).coerceIn(1, 10)
    }
    
    /**
     * Estimate test coverage based on test file existence
     */
    fun estimateTestCoverage(filePath: String): Int {
        val testFilePath = filePath.replace("/main/", "/test/").replace(".kt", "Test.kt")
        val testFile = File(projectRoot, testFilePath)
        return if (testFile.exists()) 
            (testFile.readText().split("@Test").size - 1) * 10 
        else 0
    }
    
    /**
     * Detect code smells in the code
     */
    fun detectCodeSmells(content: String): List<String> {
        val smells = mutableListOf<String>()
        if (content.lines().size > 200) smells.add("Long class")
        if (Regex("fun \\w+\\([^)]*\\)\\s*\\{[^}]{200,}").containsMatchIn(content)) smells.add("Long method")
        if (content.split("class").size > 5) smells.add("Multiple classes in file")
        if (Regex("class\\s+\\w+\\s*\\{[^}]{500,}").containsMatchIn(content)) smells.add("God class")
        if (content.split("fun").size > 20) smells.add("Too many methods")
        if (Regex("!!").findAll(content).count() > 10) smells.add("Excessive null assertions")
        return smells
    }
    
    /**
     * Calculate maintainability index
     */
    fun calculateMaintainabilityIndex(content: String): Int {
        val complexity = calculateComplexity(content)
        val linesOfCode = content.lines().size
        val documentation = if (content.contains("/**")) 10 else if (content.contains("//")) 5 else 0
        
        val baseScore = 100 - (complexity * 5) - (linesOfCode / 10)
        return (baseScore + documentation).coerceIn(0, 100)
    }
    
    /**
     * Get complexity details for a file
     */
    fun getComplexityDetails(filePath: String): ComplexityDetails {
        val analysis = analyzeFile(filePath)
        return ComplexityDetails(
            filePath = filePath,
            complexityScore = analysis.complexityScore,
            cyclomaticComplexity = analysis.complexityScore,
            cognitiveComplexity = (analysis.complexityScore * 1.2).toInt(),
            maintainabilityIndex = analysis.maintainabilityIndex,
            codeSmells = analysis.codeSmells,
            patterns = analysis.patterns
        )
    }
    
    data class ComplexityDetails(
        val filePath: String,
        val complexityScore: Int,
        val cyclomaticComplexity: Int,
        val cognitiveComplexity: Int,
        val maintainabilityIndex: Int,
        val codeSmells: List<String>,
        val patterns: List<String>
    )
}
