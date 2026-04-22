package com.i2vision.discover.structure

import com.i2vision.index.IndexProvider
import com.i2vision.index.SymbolInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Structure builder using package structure analysis.
 * 
 * Discovers components by analyzing package structure, file organization,
 * and symbol relationships.
 */
class StructureBuilder(
    projectRoot: String,
    private val indexProvider: IndexProvider
) {
    
    private val log = LoggerFactory.getLogger(StructureBuilder::class.java)
    
    // Normalize projectRoot to absolute path to avoid path normalization issues
    private val projectRoot = File(projectRoot).absolutePath
    
    // Configuration for adaptive dependency detection
    private data class DetectionConfig(
        val fullAnalysisThreshold: Int = 500,      // Full analysis up to 500 symbols
        val sampledAnalysisThreshold: Int = 2000,   // Sampled analysis up to 2000 symbols
        val importBasedThreshold: Int = 5000,       // Import-based analysis up to 5000 symbols
        val sampleRate: Double = 0.3,               // 30% sampling for medium sets
        val maxDependenciesPerComponent: Int = 50   // Cap dependencies per component
    )
    
    private val config = DetectionConfig()
    
    // Cache imports per file for performance
    private val importCache = ConcurrentHashMap<File, List<String>>()
    
    /**
     * Build components from source files and symbols.
     * 
     * @param files List of source files
     * @param symbols List of symbols
     * @return List of discovered components
     */
    fun buildComponents(files: List<File>, symbols: List<SymbolInfo>): List<Component> {
        log.info("[STRUCTURE_BUILDER] Building components from {} files and {} symbols", files.size, symbols.size)
        
        val components = mutableListOf<Component>()
        
        // Group files by package
        val filesByPackage = files.groupBy { extractPackage(it) }
        
        // Build components from packages
        filesByPackage.forEach { (packageName, packageFiles) ->
            val component = buildComponentFromPackage(packageName, packageFiles, symbols)
            if (component != null) {
                components.add(component)
            }
        }
        
        // Build components from clusters (groups of related packages)
        val clusterComponents = buildClusterComponents(filesByPackage, symbols)
        components.addAll(clusterComponents)
        
        log.info("[STRUCTURE_BUILDER] Built {} components", components.size)
        
        return components
    }
    
    /**
     * Extract package name from a file.
     * 
     * @param file The source file
     * @return Package name
     */
    private fun extractPackage(file: File): String {
        try {
            val content = file.readText()
            
            // Try Kotlin-style package declaration
            val kotlinPattern = Regex("""package\s+([\w.]+)""")
            val kotlinMatch = kotlinPattern.find(content)
            if (kotlinMatch != null) {
                return kotlinMatch.groupValues[1]
            }
            
            // Try Java-style package declaration
            val javaPattern = Regex("""package\s+([\w.]+);""")
            val javaMatch = javaPattern.find(content)
            if (javaMatch != null) {
                return javaMatch.groupValues[1]
            }
            
            // Fallback: derive package from directory structure
            val relativePath = file.relativeTo(File(projectRoot)).path.replace("\\", "/")
            
            // Support multiple source directory patterns
            val sourcePatterns = listOf(
                "src/main/kotlin/",
                "src/main/java/",
                "src/main/groovy/",
                "src/test/kotlin/",
                "src/test/java/",
                "src/test/groovy/",
                "src/kotlin/",
                "src/java/",
                "src/groovy/",
                "kotlin/",
                "java/",
                "groovy/"
            )
            
            for (pattern in sourcePatterns) {
                val patternIndex = relativePath.indexOf(pattern)
                if (patternIndex >= 0) {
                    val afterPattern = relativePath.substring(patternIndex + pattern.length)
                    val packageDir = afterPattern.substringBeforeLast("/")
                    if (packageDir.isNotEmpty()) {
                        return packageDir.replace("/", ".")
                    }
                }
            }
            
            // Last resort: use directory name as package
            val parentDir = file.parentFile?.name ?: "default"
            return parentDir
        } catch (e: Exception) {
            log.warn("[STRUCTURE_BUILDER] Failed to extract package from {}: {}", file.path, e.message)
            return "default"
        }
    }
    
    /**
     * Build a component from a package.
     * 
     * @param packageName The package name
     * @param files Files in the package
     * @param symbols All symbols
     * @return Component or null if package is too small
     */
    private fun buildComponentFromPackage(
        packageName: String,
        files: List<File>,
        symbols: List<SymbolInfo>
    ): Component? {
        // Skip very small packages (likely utilities)
        if (files.size < 2 && !isSignificantPackage(packageName)) {
            return null
        }
        
        val packageSymbols = symbols.filter { symbol ->
            files.contains(symbol.file)
        }
        
        val classes = packageSymbols.filter { it.kind == "class" || it.kind == "interface" || it.kind == "object" || it.kind == "enum" }
        val functions = packageSymbols.filter { it.kind == "fun" }
        
        return Component(
            id = generateComponentId(packageName),
            name = extractComponentName(packageName),
            type = determineComponentType(packageName, classes, functions),
            packageName = packageName,
            files = files.map { it.relativeTo(File(projectRoot)).path },
            classes = classes.map { it.name },
            functions = functions.map { it.name },
            dependencies = findDependencies(packageSymbols, symbols)
        )
    }
    
    /**
     * Build cluster components from related packages.
     * 
     * @param filesByPackage Files grouped by package
     * @param symbols All symbols
     * @return List of cluster components
     */
    private fun buildClusterComponents(
        filesByPackage: Map<String, List<File>>,
        symbols: List<SymbolInfo>
    ): List<Component> {
        val clusterComponents = mutableListOf<Component>()
        
        // Find common package prefixes (e.g., com.i2vision.core.orchestrator)
        val packagePrefixes = filesByPackage.keys
            .map { it.split(".").dropLast(1).joinToString(".") }
            .filter { it.isNotEmpty() }
            .groupBy { it }
            .filter { it.value.size >= 2 }
        
        packagePrefixes.forEach { (prefix, packages) ->
            val clusterFiles = packages.flatMap { filesByPackage[it] ?: emptyList() }
            val clusterSymbols = symbols.filter { symbol ->
                clusterFiles.contains(symbol.file)
            }
            
            val component = Component(
                id = generateComponentId(prefix),
                name = extractComponentName(prefix),
                type = "cluster",
                packageName = prefix,
                files = clusterFiles.map { it.relativeTo(File(projectRoot)).path },
                classes = clusterSymbols.filter { it.kind == "class" }.map { it.name },
                functions = clusterSymbols.filter { it.kind == "fun" }.map { it.name },
                dependencies = findDependencies(clusterSymbols, symbols)
            )
            
            clusterComponents.add(component)
        }
        
        return clusterComponents
    }
    
    /**
     * Check if a package is significant (worth creating a component for).
     * 
     * @param packageName The package name
     * @return true if significant
     */
    private fun isSignificantPackage(packageName: String): Boolean {
        val significantKeywords = listOf(
            "core", "service", "provider", "manager", "handler",
            "orchestrator", "pipeline", "engine", "processor",
            "repository", "dao", "model", "entity", "dto",
            "controller", "api", "rest", "graphql"
        )
        
        return significantKeywords.any { keyword ->
            packageName.lowercase().contains(keyword)
        }
    }
    
    /**
     * Extract component name from package name.
     * 
     * @param packageName The package name
     * @return Component name
     */
    private fun extractComponentName(packageName: String): String {
        val parts = packageName.split(".")
        return parts.lastOrNull()?.replaceFirstChar { it.uppercase() } ?: packageName
    }
    
    /**
     * Determine component type based on package name and contents.
     * 
     * @param packageName The package name
     * @param classes Classes in the component
     * @param functions Functions in the component
     * @return Component type
     */
    private fun determineComponentType(packageName: String, classes: List<SymbolInfo>, functions: List<SymbolInfo>): String {
        val lowerPackage = packageName.lowercase()
        
        return when {
            lowerPackage.contains("api") || lowerPackage.contains("controller") || lowerPackage.contains("rest") -> "api"
            lowerPackage.contains("service") || lowerPackage.contains("provider") -> "service"
            lowerPackage.contains("repository") || lowerPackage.contains("dao") || lowerPackage.contains("storage") -> "repository"
            lowerPackage.contains("model") || lowerPackage.contains("entity") || lowerPackage.contains("dto") -> "model"
            lowerPackage.contains("core") || lowerPackage.contains("orchestrator") || lowerPackage.contains("pipeline") -> "core"
            lowerPackage.contains("util") || lowerPackage.contains("helper") || lowerPackage.contains("common") -> "utility"
            lowerPackage.contains("config") || lowerPackage.contains("setting") -> "config"
            else -> "module"
        }
    }
    
    /**
     * Find dependencies for a component with adaptive strategy based on symbol count.
     */
    private fun findDependencies(
        componentSymbols: List<SymbolInfo>,
        allSymbols: List<SymbolInfo>
    ): List<String> {
        val totalSymbols = allSymbols.size
        
        return when {
            totalSymbols <= config.fullAnalysisThreshold -> {
                // Small project: Full analysis
                log.debug("[STRUCTURE_BUILDER] Using full analysis for {} symbols", totalSymbols)
                findDependenciesFull(componentSymbols, allSymbols)
            }
            
            totalSymbols <= config.sampledAnalysisThreshold -> {
                // Medium project: Sampled analysis
                log.debug("[STRUCTURE_BUILDER] Using sampled analysis for {} symbols", totalSymbols)
                findDependenciesSampled(componentSymbols, allSymbols)
            }
            
            totalSymbols <= config.importBasedThreshold -> {
                // Large project: Import-based analysis
                log.debug("[STRUCTURE_BUILDER] Using import-based analysis for {} symbols", totalSymbols)
                findDependenciesFromImports(componentSymbols, allSymbols)
            }
            
            else -> {
                // Very large project: Index-based only
                log.debug("[STRUCTURE_BUILDER] Using index-based analysis for {} symbols", totalSymbols)
                findDependenciesFromIndex(componentSymbols)
            }
        }
    }
    
    /**
     * Full analysis - suitable for small projects (<500 symbols).
     * Examines all symbol pairs.
     */
    private fun findDependenciesFull(
        componentSymbols: List<SymbolInfo>,
        allSymbols: List<SymbolInfo>
    ): List<String> {
        val dependencies = mutableSetOf<String>()
        val symbolsByPackage = allSymbols.groupBy { extractPackage(it.file) }
        val fileContentCache = ConcurrentHashMap<File, String>()
        
        // Pre-build package set for component
        val componentPackages = componentSymbols.map { extractPackage(it.file) }.toSet()
        
        componentSymbols.forEach { symbol ->
            val symbolPackage = extractPackage(symbol.file)
            val content = fileContentCache.getOrPut(symbol.file) {
                try { symbol.file.readText() } catch (e: Exception) { "" }
            }
            
            symbolsByPackage.forEach { (otherPackage, packageSymbols) ->
                if (otherPackage != symbolPackage && otherPackage !in componentPackages) {
                    val referenced = packageSymbols.any { other ->
                        content.contains(other.name)
                    }
                    if (referenced) {
                        dependencies.add(otherPackage)
                    }
                }
            }
        }
        
        return dependencies.take(config.maxDependenciesPerComponent)
    }
    
    /**
     * Sampled analysis - suitable for medium projects (500-2000 symbols).
     * Samples symbols and uses import information.
     */
    private fun findDependenciesSampled(
        componentSymbols: List<SymbolInfo>,
        allSymbols: List<SymbolInfo>
    ): List<String> {
        val dependencies = mutableSetOf<String>()
        val symbolsByPackage = allSymbols.groupBy { extractPackage(it.file) }
        val componentPackages = componentSymbols.map { extractPackage(it.file) }.toSet()
        
        // Sample component symbols
        val sampleSize = (componentSymbols.size * config.sampleRate).toInt().coerceAtLeast(10)
        val sampledSymbols = componentSymbols.shuffled().take(sampleSize)
        
        // Also include significant symbols (classes, public functions)
        val significantSymbols = componentSymbols.filter { 
            it.kind == "class" || it.kind == "interface" || it.kind == "object" ||
            (it.kind == "fun" && it.name.startsWith("public"))
        }
        
        val symbolsToAnalyze = (sampledSymbols + significantSymbols).distinct()
        
        val fileContentCache = ConcurrentHashMap<File, String>()
        
        symbolsToAnalyze.forEach { symbol ->
            val symbolPackage = extractPackage(symbol.file)
            val content = fileContentCache.getOrPut(symbol.file) {
                try { symbol.file.readText() } catch (e: Exception) { "" }
            }
            
            // Extract imports from file (fast!)
            val imports = extractImports(content)
            
            symbolsByPackage.forEach { (otherPackage, _) ->
                if (otherPackage != symbolPackage && otherPackage !in componentPackages) {
                    // Check if package is imported
                    val isImported = imports.any { import -> 
                        import.startsWith(otherPackage) || otherPackage.startsWith(import)
                    }
                    
                    if (isImported) {
                        dependencies.add(otherPackage)
                    }
                }
            }
        }
        
        return dependencies.take(config.maxDependenciesPerComponent)
    }
    
    /**
     * Import-based analysis - suitable for large projects (2000-5000 symbols).
     * Only uses import statements (very fast).
     */
    private fun findDependenciesFromImports(
        componentSymbols: List<SymbolInfo>,
        allSymbols: List<SymbolInfo>
    ): List<String> {
        val dependencies = mutableSetOf<String>()
        val allPackages = allSymbols.map { extractPackage(it.file) }.toSet()
        val componentPackages = componentSymbols.map { extractPackage(it.file) }.toSet()
        
        // Only analyze one file per package (representative)
        val representativeFiles = componentSymbols
            .groupBy { extractPackage(it.file) }
            .mapValues { it.value.first().file }
            .values
            .distinct()
        
        representativeFiles.forEach { file ->
            try {
                val content = file.readText()
                val imports = extractImports(content)
                
                imports.forEach { import ->
                    // Find which package this import corresponds to
                    val matchingPackage = allPackages.find { pkg ->
                        import.startsWith(pkg) || pkg.startsWith(import)
                    }
                    
                    if (matchingPackage != null && matchingPackage !in componentPackages) {
                        dependencies.add(matchingPackage)
                    }
                }
            } catch (e: Exception) {
                // Skip unreadable files
            }
        }
        
        return dependencies.take(config.maxDependenciesPerComponent)
    }
    
    /**
     * Index-based analysis - suitable for very large projects (>5000 symbols).
     * Uses pre-built index from IndexProvider.
     */
    private fun findDependenciesFromIndex(
        componentSymbols: List<SymbolInfo>
    ): List<String> {
        val dependencies = mutableSetOf<String>()
        val componentPackages = componentSymbols.map { extractPackage(it.file) }.toSet()
        
        // Use index provider for fast cross-reference lookup via call hierarchy
        componentSymbols.forEach { symbol ->
            try {
                val callHierarchy = indexProvider.getCallHierarchy(symbol)
                callHierarchy.callees.forEach { callee ->
                    val calleePackage = extractPackage(callee.file)
                    if (calleePackage !in componentPackages) {
                        dependencies.add(calleePackage)
                    }
                }
            } catch (e: Exception) {
                // Skip if index lookup fails
            }
        }
        
        return dependencies.take(config.maxDependenciesPerComponent)
    }
    
    /**
     * Extract import statements from file content.
     */
    private fun extractImports(content: String): List<String> {
        val imports = mutableListOf<String>()
        
        // Kotlin imports
        val kotlinImportPattern = Regex("""import\s+([\w.]+)""")
        kotlinImportPattern.findAll(content).forEach { match ->
            imports.add(match.groupValues[1])
        }
        
        // Java imports
        val javaImportPattern = Regex("""import\s+([\w.]+);""")
        javaImportPattern.findAll(content).forEach { match ->
            imports.add(match.groupValues[1])
        }
        
        return imports
    }
    
    /**
     * Generate a unique component ID.
     * 
     * @param packageName The package name
     * @return Unique component ID
     */
    private fun generateComponentId(packageName: String): String {
        return "component_${packageName.replace(".", "_")}"
    }
}

/**
 * Represents a discovered component.
 */
data class Component(
    val id: String,
    val name: String,
    val type: String,           // api, service, repository, model, core, utility, config, module, cluster
    val packageName: String,
    val files: List<String>,
    val classes: List<String>,
    val functions: List<String>,
    val dependencies: List<String>
)
