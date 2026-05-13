/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.layer

import com.i2vision.intent.DiscoveryIntent
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.SymbolKind
import com.i2vision.vslfc.VerbalizationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Structure layer verbalizer - transforms module dependencies into component graphs.
 * 
 * This verbalizer analyzes code structure to identify components, their relationships,
 * and architectural patterns, producing a component graph description.
 */
class StructureVerbalizer : LayerVerbalizer {
    
    override val layerName: String = "Structure"
    
    override fun canHandle(symbol: Symbol): Boolean {
        // Structure layer handles architectural components
        return symbol.kind in listOf(SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.OBJECT, SymbolKind.ENUM) ||
               symbol.filePath.contains("/module/") ||
               symbol.filePath.contains("/component/") ||
               symbol.filePath.contains("/architecture/")
    }
    
    override suspend fun verbalize(
        symbol: Symbol,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): VerbalizationResult = withContext(Dispatchers.Default) {
        val components = extractComponents(symbol)
        val dependencies = extractDependencies(symbol)
        val architecturePattern = detectArchitecturePattern(symbol)
        val modules = extractModules(symbol)
        
        val structureContext = StructureContext(
            components = components,
            dependencies = dependencies,
            architecturePattern = architecturePattern,
            modules = modules
        )
        
        val description = generateStructureDescription(structureContext)
        
        VerbalizationResult(
            symbol = symbol,
            description = description,
            confidence = calculateConfidence(symbol, structureContext),
            strategy = intent.verbalization.strategy,
            metadata = mapOf(
                "layer" to "STRUCTURE",
                "components_count" to components.size.toString(),
                "dependencies_count" to dependencies.size.toString(),
                "architecture_pattern" to architecturePattern,
                "modules_count" to modules.size.toString()
            )
        )
    }
    
    override suspend fun verbalizeAll(
        symbols: List<Symbol>,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {
        return symbols.filter { canHandle(it) }
            .map { verbalize(it, context, intent) }
    }
    
    /**
     * Extract component information from symbol.
     */
    private fun extractComponents(symbol: Symbol): List<ComponentInfo> {
        val components = mutableListOf<ComponentInfo>()
        
        // Extract class/component definitions
        val classRegex = Regex("""(?:class|interface|object|enum class)\s+(\w+)""")
        classRegex.findAll(symbol.content)
            .forEach { match ->
                val name = match.groupValues[1]
                val type = when {
                    match.value.startsWith("interface") -> "INTERFACE"
                    match.value.startsWith("object") -> "OBJECT"
                    match.value.startsWith("enum") -> "ENUM"
                    else -> "CLASS"
                }
                
                val responsibilities = extractResponsibilities(symbol.content, name)
                
                components.add(
                    ComponentInfo(
                        name = name,
                        type = type,
                        responsibilities = responsibilities,
                        filePath = symbol.filePath
                    )
                )
            }
        
        // If no components found, create one from the symbol itself
        if (components.isEmpty()) {
            components.add(
                ComponentInfo(
                    name = symbol.name,
                    type = symbol.kind.name,
                    responsibilities = extractResponsibilities(symbol.content, symbol.name),
                    filePath = symbol.filePath
                )
            )
        }
        
        return components
    }
    
    /**
     * Extract responsibilities from documentation or naming patterns.
     */
    private fun extractResponsibilities(content: String, componentName: String): List<String> {
        val responsibilities = mutableListOf<String>()
        
        // Look for @responsibility or @purpose tags
        val tagRegex = Regex("""@(?:responsibility|purpose)\s+(.+)""")
        tagRegex.findAll(content)
            .map { it.groupValues[1].trim() }
            .forEach { responsibilities.add(it) }
        
        // Infer from method names
        val methodRegex = Regex("""fun\s+(\w+)\s*\(""")
        methodRegex.findAll(content)
            .map { it.groupValues[1] }
            .filter { it.length > 3 }
            .take(3)
            .forEach { methodName ->
                responsibilities.add(inferResponsibilityFromMethodName(methodName))
            }
        
        return responsibilities.ifEmpty { listOf("Manages $componentName operations") }.distinct()
    }
    
    /**
     * Infer responsibility from method name.
     */
    private fun inferResponsibilityFromMethodName(methodName: String): String {
        return when {
            methodName.startsWith("get") || methodName.startsWith("fetch") -> "Retrieves data"
            methodName.startsWith("set") || methodName.startsWith("update") -> "Updates state"
            methodName.startsWith("create") || methodName.startsWith("build") -> "Creates instances"
            methodName.startsWith("delete") || methodName.startsWith("remove") -> "Removes entities"
            methodName.startsWith("validate") || methodName.startsWith("check") -> "Validates data"
            methodName.startsWith("process") || methodName.startsWith("handle") -> "Processes requests"
            methodName.startsWith("initialize") || methodName.startsWith("init") -> "Initializes components"
            else -> "Performs ${methodName.lowercase().replaceFirstChar { it.uppercase() }} operation"
        }
    }
    
    /**
     * Extract dependency information from imports and usage.
     */
    private fun extractDependencies(symbol: Symbol): List<DependencyInfo> {
        val dependencies = mutableListOf<DependencyInfo>()
        
        // Extract import statements
        val importRegex = Regex("""import\s+([\w.]+)""")
        val imports = importRegex.findAll(symbol.content)
            .map { it.groupValues[1] }
            .filter { !it.startsWith("kotlin") && !it.startsWith("java") }
            .distinct()
        
        imports.forEach { import ->
            val strength = when {
                import.contains("core") || import.contains("api") -> DependencyStrength.STRONG
                import.contains("util") || import.contains("helper") -> DependencyStrength.WEAK
                else -> DependencyStrength.WEAK
            }
            
            dependencies.add(
                DependencyInfo(
                    from = symbol.name,
                    to = import.substringAfterLast("."),
                    type = "IMPORT",
                    strength = strength
                )
            )
        }
        
        // Extract constructor parameters and property types
        val usageRegex = Regex(""":\s*(\w+)(?:\s*[,)]|\s*=\s*)""")
        usageRegex.findAll(symbol.content)
            .map { it.groupValues[1] }
            .filter { it.first().isUpperCase() && it !in imports.map { imp -> imp.substringAfterLast(".") } }
            .distinct()
            .take(5)
            .forEach { type ->
                dependencies.add(
                    DependencyInfo(
                        from = symbol.name,
                        to = type,
                        type = "TYPE_REFERENCE",
                        strength = DependencyStrength.STRONG
                    )
                )
            }
        
        return dependencies
    }
    
    /**
     * Detect architecture pattern from code structure.
     */
    private fun detectArchitecturePattern(symbol: Symbol): String {
        val content = symbol.content.lowercase()
        
        return when {
            content.contains("@controller") || content.contains("@restcontroller") -> "MVC"
            content.contains("@service") && content.contains("@repository") -> "Layered"
            content.contains("interface") && content.contains("implementation") -> "Interface-based"
            content.contains("observer") || content.contains("listener") -> "Event-driven"
            content.contains("command") || content.contains("query") -> "CQRS"
            content.contains("factory") || content.contains("builder") -> "Builder Pattern"
            content.contains("singleton") || content.contains("object ") -> "Singleton"
            content.contains("strategy") -> "Strategy Pattern"
            else -> "Unknown"
        }
    }
    
    /**
     * Extract module names from file path and content.
     */
    private fun extractModules(symbol: Symbol): List<String> {
        val modules = mutableListOf<String>()
        
        // Extract from file path
        val pathParts = symbol.filePath.split("/")
        if (pathParts.size >= 2) {
            modules.add(pathParts[0])
            if (pathParts.size >= 3) {
                modules.add("${pathParts[0]}/${pathParts[1]}")
            }
        }
        
        // Extract package declarations
        val packageRegex = Regex("""package\s+([\w.]+)""")
        val packageMatch = packageRegex.find(symbol.content)
        if (packageMatch != null) {
            val packageName = packageMatch.groupValues[1]
            modules.add(packageName.split(".").joinToString("/"))
        }
        
        return modules.distinct().ifEmpty { listOf("default") }
    }
    
    /**
     * Generate natural language description from structure context.
     */
    private fun generateStructureDescription(context: StructureContext): String {
        val sb = StringBuilder()
        
        sb.append("Architecture: ${context.architecturePattern}. ")
        sb.append("Components: ${context.components.map { it.name }.joinToString(", ")}. ")
        
        if (context.dependencies.isNotEmpty()) {
            val depSummary = context.dependencies
                .groupBy { it.from }
                .mapValues { it.value.size }
                .map { "${it.key}(${it.value} deps)" }
            sb.append("Dependencies: ${depSummary.joinToString(", ")}. ")
        }
        
        if (context.modules.isNotEmpty()) {
            sb.append("Modules: ${context.modules.joinToString(", ")}.")
        }
        
        return sb.toString().trim()
    }
    
    /**
     * Calculate confidence based on structural analysis quality.
     */
    private fun calculateConfidence(symbol: Symbol, context: StructureContext): Double {
        var confidence = 0.5
        
        // Higher confidence if we found components
        if (context.components.isNotEmpty()) {
            confidence += 0.2
        }
        
        // Higher confidence if we found dependencies
        if (context.dependencies.isNotEmpty()) {
            confidence += 0.15
        }
        
        // Higher confidence if architecture pattern detected
        if (context.architecturePattern != "Unknown") {
            confidence += 0.15
        }
        
        return minOf(confidence, 0.95)
    }
}
