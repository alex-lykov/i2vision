/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.modifier

import com.i2vision.arch.signature.EnrichedSymbol
import com.i2vision.arch.signature.ModifierKind
import com.i2vision.arch.signature.StructuralRole
import com.i2vision.arch.signature.SymbolModifier

/**
 * Interface for verbalizing modifiers into natural language.
 * 
 * This is a pure transformation component that converts structured
 * modifier facts into grammatically correct descriptions.
 * 
 * Each language should implement this interface to provide
 * language-specific verbalization.
 */
interface ModifierVerbalizer {
    /**
     * Verbalize a single modifier into natural language.
     * 
     * @param modifier The structured modifier to verbalize
     * @return Natural language description (e.g., "suspending", "data class")
     */
    fun verbalizeModifier(modifier: SymbolModifier): String
    
    /**
     * Verbalize multiple modifiers into a coherent phrase.
     * 
     * @param modifiers List of modifiers to verbalize
     * @return Combined natural language description
     */
    fun verbalizeModifiers(modifiers: List<SymbolModifier>): String {
        if (modifiers.isEmpty()) return ""
        
        val phrases = modifiers.map { verbalizeModifier(it) }
        return combinePhrases(phrases)
    }
    
    /**
     * Combine multiple modifier phrases into coherent text.
     * Default implementation joins with commas.
     */
    fun combinePhrases(phrases: List<String>): String {
        return when {
            phrases.isEmpty() -> ""
            phrases.size == 1 -> phrases.first()
            else -> phrases.joinToString(", ")
        }
    }
}

/**
 * Kotlin-specific modifier verbalizer.
 * 
 * Produces grammatically correct Kotlin terminology.
 */
class KotlinModifierVerbalizer : ModifierVerbalizer {
    
    override fun verbalizeModifier(modifier: SymbolModifier): String {
        return when (modifier.kind) {
            // Kotlin language features
            ModifierKind.SUSPEND -> "suspending"
            ModifierKind.DATA_CLASS -> "data class"
            ModifierKind.VALUE_CLASS -> "inline value class"
            ModifierKind.SEALED_CLASS -> "sealed class"
            ModifierKind.SEALED_INTERFACE -> "sealed interface"
            ModifierKind.COMPANION_OBJECT -> "with companion object"
            ModifierKind.DELEGATE -> "using delegation"
            ModifierKind.EXTENSION -> "extension"
            
            // Modifiers
            ModifierKind.FINAL -> "immutable"
            ModifierKind.ABSTRACT -> "abstract"
            ModifierKind.OVERRIDE -> "overridden"
            ModifierKind.STATIC -> "static"
            
            // Architectural patterns
            ModifierKind.REPOSITORY -> "repository"
            ModifierKind.CONTROLLER -> "controller"
            ModifierKind.SERVICE -> "service"
            ModifierKind.FACTORY -> "factory"
            ModifierKind.BUILDER -> "builder"
            ModifierKind.OBSERVER -> "observer"
            ModifierKind.STRATEGY -> "strategy"
            ModifierKind.SINGLETON -> "singleton"
            
            // Validation
            ModifierKind.BUSINESS_RULE -> "enforces business rules"
            ModifierKind.INVARIANT -> "maintains invariants"
            ModifierKind.VALIDATION -> "performs validation"
            
            // Flow
            ModifierKind.FLOW -> "part of flow"
            ModifierKind.SEQUENCE -> "part of sequence"
            ModifierKind.EVENT_PUBLISHER -> "publishes events"
            ModifierKind.EVENT_CONSUMER -> "consumes events"
            
            // Technical
            ModifierKind.DATABASE_ACCESS -> "with database access"
            ModifierKind.EXTERNAL_CALL -> "makes external calls"
            ModifierKind.CACHE_ACCESS -> "uses caching"
            
            // General
            ModifierKind.ASYNC -> "asynchronous"
            ModifierKind.ANNOTATION -> "annotated"
            
            ModifierKind.UNKNOWN -> ""
            ModifierKind.RECORD -> "record class"  // Java
            ModifierKind.SYNC -> "synchronized"    // Java
        }
    }
    
    override fun combinePhrases(phrases: List<String>): String {
        // Separate structural modifiers from behavioral modifiers
        val structuralPhrases = phrases.filter { 
            it in listOf("data class", "sealed class", "sealed interface", "inline value class", "record class") 
        }
        val behavioralPhrases = phrases.filter { it !in structuralPhrases }
        
        return buildString {
            if (structuralPhrases.isNotEmpty()) {
                append(structuralPhrases.joinToString(" "))
            }
            
            if (behavioralPhrases.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append(behavioralPhrases.joinToString(", "))
            }
        }.trim()
    }
}

/**
 * Java-specific modifier verbalizer.
 */
class JavaModifierVerbalizer : ModifierVerbalizer {
    
    override fun verbalizeModifier(modifier: SymbolModifier): String {
        return when (modifier.kind) {
            ModifierKind.RECORD -> "record"
            ModifierKind.SYNC -> "synchronized"
            ModifierKind.FINAL -> "final"
            ModifierKind.STATIC -> "static"
            ModifierKind.ABSTRACT -> "abstract"
            ModifierKind.SERVICE -> "service"
            ModifierKind.REPOSITORY -> "repository"
            ModifierKind.CONTROLLER -> "controller"
            ModifierKind.DATABASE_ACCESS -> "with database access"
            ModifierKind.EXTERNAL_CALL -> "makes external calls"
            else -> modifier.kind.name.lowercase().replace("_", " ")
        }
    }
}

/**
 * Context for verbalizing a complete symbol.
 */
data class VerbalizationContext(
    val baseDescription: String,
    val enrichedSymbol: EnrichedSymbol,
    val includeModifiers: Boolean = true,
    val includeRole: Boolean = true,
    val includeTechnicalContext: Boolean = true
)

/**
 * Extension function to verbalize an enriched symbol.
 */
fun ModifierVerbalizer.verbalizeEnrichedSymbol(
    enriched: EnrichedSymbol,
    baseDescription: String
): String {
    val context = VerbalizationContext(
        baseDescription = baseDescription,
        enrichedSymbol = enriched
    )
    return verbalizeWithContext(context)
}

/**
 * Verbalize structural role into natural language description.
 */
fun ModifierVerbalizer.verbalizeStructuralRole(role: StructuralRole): String {
    return when (role) {
        // Data layer
        StructuralRole.REPOSITORY -> "Repository pattern implementation"
        StructuralRole.ENTITY -> "Domain entity"
        StructuralRole.DTO -> "Data transfer object"
        StructuralRole.VALIDATOR -> "Validation component"
        
        // Service layer
        StructuralRole.SERVICE -> "service component"
        StructuralRole.CONTROLLER -> "REST controller"
        StructuralRole.ORCHESTRATOR -> "Orchestration coordinator"
        StructuralRole.DISPATCHER -> "Request dispatcher"
        
        // Factory/Creational
        StructuralRole.FACTORY -> "Factory pattern implementation"
        StructuralRole.BUILDER -> "Builder pattern implementation"
        
        // Event handling
        StructuralRole.EVENT_PRODUCER -> "Event publisher"
        StructuralRole.EVENT_CONSUMER -> "Event consumer"
        StructuralRole.EVENT_HANDLER -> "Event handler"
        
        // Transformation
        StructuralRole.TRANSFORMER -> "Data transformer"
        StructuralRole.AGGREGATOR -> "Data aggregator"
        
        // Other
        StructuralRole.UTIL -> "Utility component"
        StructuralRole.CONFIG -> "Configuration component"
        StructuralRole.UNKNOWN -> ""
    }
}

/**
 * Verbalize symbol with full context.
 */
fun ModifierVerbalizer.verbalizeWithContext(context: VerbalizationContext): String {
    val sb = StringBuilder()
    
    // Start with base description
    sb.append(context.baseDescription)
    
    // Add modifiers if requested
    if (context.includeModifiers) {
        val modifierPhrases = context.enrichedSymbol.modifiers.map { verbalizeModifier(it) }
            .filter { it.isNotEmpty() }
        if (modifierPhrases.isNotEmpty()) {
            sb.append(" - ").append(modifierPhrases.joinToString(", "))
        }
    }
    
    // Add structural role if requested - incorporate naturally into description
    if (context.includeRole) {
        val role = context.enrichedSymbol.structuralRole
        if (role != null && role != StructuralRole.UNKNOWN) {
            val roleDescription = verbalizeStructuralRole(role)
            if (roleDescription.isNotEmpty()) {
                sb.append(" - ").append(roleDescription)
            }
        }
    }
    
    // Add technical context if requested
    if (context.includeTechnicalContext) {
        val techContext = context.enrichedSymbol.technicalContext
        val techPhrases = mutableListOf<String>()
        
        if (techContext.hasDatabaseAccess) techPhrases.add("database access")
        if (techContext.hasExternalCalls) techPhrases.add("external calls")
        if (techContext.hasCacheAccess) techPhrases.add("caching")
        if (techContext.isTransactional) techPhrases.add("transactional")
        if (techContext.isAsync) techPhrases.add("async")
        
        if (techPhrases.isNotEmpty()) {
            sb.append(" {").append(techPhrases.joinToString(", ")).append("}")
        }
    }
    
    return sb.toString()
}