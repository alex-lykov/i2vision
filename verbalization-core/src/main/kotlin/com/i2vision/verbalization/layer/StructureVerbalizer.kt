/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.layer

import com.i2vision.arch.signature.EnrichedSymbol
import com.i2vision.arch.signature.ModifierKind
import com.i2vision.arch.signature.StructuralRole
import com.i2vision.intent.DiscoveryIntent
import com.i2vision.vslfc.SymbolKind
import com.i2vision.vslfc.VerbalizationResult
import com.i2vision.verbalization.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Structure layer verbalizer - transforms module dependencies into component graphs.
 * 
 * This verbalizer analyzes code structure to identify components, their relationships,
 * and architectural patterns, producing a component graph description.
 * 
 * REFACTORED: Uses structured detection from EnrichedSymbol. No fallback.
 */
class StructureVerbalizer : LayerVerbalizer {
    
    override val layerName: String = "Structure"
    
    override fun canHandle(symbol: EnrichedSymbol): Boolean {
        return symbol.symbol.kind in listOf(SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.OBJECT, SymbolKind.ENUM) ||
               symbol.symbol.filePath.contains("/module/") ||
               symbol.symbol.filePath.contains("/component/") ||
               symbol.symbol.filePath.contains("/architecture/")
    }
    
    override suspend fun verbalize(
        symbol: EnrichedSymbol,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): VerbalizationResult = withContext(Dispatchers.Default) {
        val components = extractComponents(symbol)
        val dependencies = extractDependencies(symbol)
        val patterns = extractPatterns(symbol)
        val layers = extractLayers(symbol)
        
        val structureContext = StructureContext(
            components = components,
            dependencies = dependencies,
            patterns = patterns,
            layers = layers
        )
        
        val description = generateStructureDescription(structureContext)
        
        VerbalizationResult(
            symbol = symbol.symbol,
            description = description,
            confidence = calculateConfidence(symbol, structureContext),
            strategy = intent.verbalization.strategy,
            metadata = mapOf(
                "layer" to "STRUCTURE",
                "components_count" to components.size.toString(),
                "dependencies_count" to dependencies.size.toString(),
                "patterns_count" to patterns.size.toString(),
                "layers_count" to layers.size.toString(),
                "structural_role" to (symbol.structuralRole?.name ?: "UNKNOWN")
            )
        )
    }
    
    override suspend fun verbalizeAll(
        symbols: List<EnrichedSymbol>,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {
        return symbols.filter { canHandle(it) }
            .map { verbalize(it, context, intent) }
    }
    
    private fun extractComponents(enriched: EnrichedSymbol): List<ComponentInfo> {
        val type = when (enriched.symbol.kind) {
            SymbolKind.INTERFACE -> "INTERFACE"
            SymbolKind.OBJECT -> "OBJECT"
            SymbolKind.ENUM -> "ENUM"
            SymbolKind.CLASS -> {
                when {
                    enriched.hasModifier(ModifierKind.DATA_CLASS) -> "DATA_CLASS"
                    enriched.hasModifier(ModifierKind.SEALED_CLASS) -> "SEALED_CLASS"
                    enriched.hasModifier(ModifierKind.VALUE_CLASS) -> "VALUE_CLASS"
                    else -> "CLASS"
                }
            }
            else -> "CLASS"
        }
        
        val responsibilities = extractResponsibilities(enriched)
        
        return listOf(
            ComponentInfo(
                name = enriched.symbol.name,
                type = type,
                role = enriched.structuralRole,
                modifiers = enriched.modifiers.map { it.kind.name },
                description = responsibilities.joinToString(". ")
            )
        )
    }
    
    private fun extractResponsibilities(enriched: EnrichedSymbol): List<String> {
        val responsibilities = mutableListOf<String>()
        
        enriched.structuralRole?.let { role ->
            responsibilities.add(role.toResponsibility())
        }
        
        when {
            enriched.hasModifier(ModifierKind.REPOSITORY) -> 
                responsibilities.add("Manages data access operations")
            enriched.hasModifier(ModifierKind.CONTROLLER) -> 
                responsibilities.add("Handles HTTP requests and responses")
            enriched.hasModifier(ModifierKind.SERVICE) -> 
                responsibilities.add("Implements business logic")
            enriched.hasModifier(ModifierKind.FACTORY) -> 
                responsibilities.add("Creates object instances")
            enriched.hasModifier(ModifierKind.BUILDER) -> 
                responsibilities.add("Constructs complex objects")
            enriched.hasModifier(ModifierKind.VALIDATION) -> 
                responsibilities.add("Validates input data")
            enriched.hasModifier(ModifierKind.BUSINESS_RULE) -> 
                responsibilities.add("Enforces business rules")
        }
        
        return responsibilities.ifEmpty { 
            listOf("Manages ${enriched.symbol.name} operations") 
        }.distinct()
    }
    
    private fun StructuralRole.toResponsibility(): String {
        return when (this) {
            StructuralRole.CONTROLLER -> "Handles HTTP requests and responses"
            StructuralRole.SERVICE -> "Implements business logic"
            StructuralRole.REPOSITORY -> "Manages data access and persistence"
            StructuralRole.ENTITY -> "Represents domain model"
            StructuralRole.DTO -> "Transfers data between layers"
            StructuralRole.VALIDATOR -> "Validates input data"
            StructuralRole.FACTORY -> "Creates object instances"
            StructuralRole.BUILDER -> "Constructs complex objects step-by-step"
            StructuralRole.ORCHESTRATOR -> "Coordinates multiple services"
            StructuralRole.DISPATCHER -> "Routes requests to handlers"
            StructuralRole.TRANSFORMER -> "Transforms data between formats"
            StructuralRole.AGGREGATOR -> "Aggregates data from multiple sources"
            StructuralRole.EVENT_PRODUCER -> "Publishes domain events"
            StructuralRole.EVENT_CONSUMER -> "Consumes and processes events"
            StructuralRole.EVENT_HANDLER -> "Handles specific event types"
            StructuralRole.UTIL -> "Provides utility functions"
            StructuralRole.CONFIG -> "Manages configuration"
            StructuralRole.UNKNOWN -> "Performs operations"
        }
    }
    
    private fun extractDependencies(enriched: EnrichedSymbol): List<DependencyInfo> {
        return enriched.dependencies.map { dep ->
            DependencyInfo(
                from = enriched.symbol.name,
                to = dep,
                type = "INJECTED",
                strength = DependencyStrength.STRONG
            )
        }
    }
    
    private fun extractPatterns(enriched: EnrichedSymbol): List<String> {
        val patterns = mutableListOf<String>()
        
        when {
            enriched.hasModifier(ModifierKind.CONTROLLER) -> patterns.add("MVC")
            enriched.hasModifier(ModifierKind.SERVICE) && enriched.hasModifier(ModifierKind.REPOSITORY) -> patterns.add("Layered")
            enriched.hasModifier(ModifierKind.OBSERVER) -> patterns.add("Event-driven")
            enriched.hasModifier(ModifierKind.FACTORY) && enriched.hasModifier(ModifierKind.BUILDER) -> patterns.add("Builder Pattern")
            enriched.hasModifier(ModifierKind.SINGLETON) -> patterns.add("Singleton")
            enriched.hasModifier(ModifierKind.STRATEGY) -> patterns.add("Strategy Pattern")
            enriched.symbol.kind == SymbolKind.INTERFACE -> patterns.add("Interface-based")
        }
        
        return patterns
    }
    
    private fun extractLayers(enriched: EnrichedSymbol): List<String> {
        val layers = mutableListOf<String>()
        val pathParts = enriched.symbol.filePath.split("/")
        
        if (pathParts.size >= 2) {
            layers.add(pathParts[0])
            if (pathParts.size >= 3) {
                layers.add("${pathParts[0]}/${pathParts[1]}")
            }
        }
        
        return layers.distinct()
    }
    
    private fun generateStructureDescription(context: StructureContext): String {
        val sb = StringBuilder()
        
        if (context.components.isNotEmpty()) {
            val component = context.components.first()
            sb.append("${component.type.lowercase().replace("_", " ")} ")
            sb.append(component.name)
            
            if (component.description.isNotEmpty()) {
                sb.append(" - ${component.description}")
            }
        }
        
        if (context.patterns.isNotEmpty()) {
            sb.append(". Follows ${context.patterns.joinToString(", ")} pattern(s)")
        }
        
        if (context.dependencies.isNotEmpty()) {
            sb.append(". Depends on ${context.dependencies.size} component(s)")
        }
        
        return sb.toString()
    }
    
    private fun calculateConfidence(enriched: EnrichedSymbol, context: StructureContext): Double {
        var confidence = 0.6
        
        if (enriched.modifiers.isNotEmpty()) confidence += 0.15
        if (enriched.structuralRole != null && enriched.structuralRole != StructuralRole.UNKNOWN) confidence += 0.15
        if (context.patterns.isNotEmpty()) confidence += 0.1
        
        return confidence.coerceAtMost(1.0)
    }
}
