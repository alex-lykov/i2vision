/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.arch.signature

import com.i2vision.vslfc.Symbol

/**
 * Represents a modifier detected from AST analysis.
 */
data class SymbolModifier(
    val kind: ModifierKind,
    val properties: Map<String, String> = emptyMap(),
    val source: ModifierSource = ModifierSource.AST
)

/**
 * Types of modifiers that can be detected in code.
 */
enum class ModifierKind {
    // Kotlin-specific
    SUSPEND,
    DATA_CLASS,
    VALUE_CLASS,
    SEALED_CLASS,
    SEALED_INTERFACE,
    COMPANION_OBJECT,
    DELEGATE,
    
    // Java-specific
    RECORD,
    SYNC,
    
    // General/Universal
    ASYNC,
    STATIC,
    FINAL,
    ABSTRACT,
    OVERRIDE,
    ANNOTATION,
    EXTENSION,
    
    // Architectural
    REPOSITORY,
    CONTROLLER,
    SERVICE,
    FACTORY,
    BUILDER,
    OBSERVER,
    STRATEGY,
    SINGLETON,
    
    // Validation
    BUSINESS_RULE,
    INVARIANT,
    VALIDATION,
    
    // Flow
    FLOW,
    SEQUENCE,
    EVENT_PUBLISHER,
    EVENT_CONSUMER,
    
    // Technical
    DATABASE_ACCESS,
    EXTERNAL_CALL,
    CACHE_ACCESS,
    
    UNKNOWN
}

/**
 * Source of modifier detection.
 */
enum class ModifierSource {
    AST,
    ANNOTATION,
    NAMING,
    PATH,
    INFERENCE
}

/**
 * Represents architectural role of a symbol.
 */
enum class StructuralRole {
    CONTROLLER,
    SERVICE,
    REPOSITORY,
    ENTITY,
    DTO,
    VALIDATOR,
    FACTORY,
    BUILDER,
    ORCHESTRATOR,
    DISPATCHER,
    TRANSFORMER,
    AGGREGATOR,
    EVENT_PRODUCER,
    EVENT_CONSUMER,
    EVENT_HANDLER,
    UTIL,
    CONFIG,
    UNKNOWN
}

/**
 * Technical context flags for a symbol.
 */
data class TechnicalContext(
    val hasDatabaseAccess: Boolean = false,
    val hasExternalCalls: Boolean = false,
    val hasCacheAccess: Boolean = false,
    val isTransactional: Boolean = false,
    val isAsync: Boolean = false,
    val properties: Map<String, String> = emptyMap()
)

/**
 * Builder for creating EnrichedSymbol instances.
 */
class EnrichedSymbolBuilder(private val symbol: Symbol) {
    private var modifiers = mutableListOf<SymbolModifier>()
    private var structuralRole: StructuralRole? = null
    private var dependencies = mutableListOf<String>()
    private var flows = mutableListOf<String>()
    private var businessRules = mutableListOf<String>()
    private var technicalContext = TechnicalContext()
    
    fun addModifier(modifier: SymbolModifier) = apply {
        modifiers.add(modifier)
    }
    
    fun addModifiers(modifiers: List<SymbolModifier>) = apply {
        this.modifiers.addAll(modifiers)
    }
    
    fun setStructuralRole(role: StructuralRole) = apply {
        structuralRole = role
    }
    
    fun addDependency(dep: String) = apply {
        dependencies.add(dep)
    }
    
    fun addDependencies(deps: List<String>) = apply {
        dependencies.addAll(deps)
    }
    
    fun addFlow(flow: String) = apply {
        flows.add(flow)
    }
    
    fun addFlows(flows: List<String>) = apply {
        this.flows.addAll(flows)
    }
    
    fun addBusinessRule(rule: String) = apply {
        businessRules.add(rule)
    }
    
    fun addBusinessRules(rules: List<String>) = apply {
        businessRules.addAll(rules)
    }
    
    fun setTechnicalContext(context: TechnicalContext) = apply {
        technicalContext = context
    }
    
    fun build(): EnrichedSymbol {
        return EnrichedSymbol(
            symbol = symbol,
            modifiers = modifiers.toList(),
            structuralRole = structuralRole,
            dependencies = dependencies.toList(),
            flows = flows.toList(),
            businessRules = businessRules.toList(),
            technicalContext = technicalContext
        )
    }
}

/**
 * Extension function to create EnrichedSymbol with builder.
 */
fun Symbol.enrich(builderAction: EnrichedSymbolBuilder.() -> Unit): EnrichedSymbol {
    val builder = EnrichedSymbolBuilder(this)
    builder.builderAction()
    return builder.build()
}

/**
 * Extension function to convert Symbol to EnrichedSymbol.
 */
fun Symbol.toEnriched(): EnrichedSymbol {
    return EnrichedSymbol(symbol = this)
}

/**
 * Enriched symbol with structured facts from discovery phase.
 */
data class EnrichedSymbol(
    val symbol: Symbol,
    val modifiers: List<SymbolModifier> = emptyList(),
    val structuralRole: StructuralRole? = null,
    val dependencies: List<String> = emptyList(),
    val flows: List<String> = emptyList(),
    val businessRules: List<String> = emptyList(),
    val technicalContext: TechnicalContext = TechnicalContext()
) {
    fun hasModifier(kind: ModifierKind): Boolean {
        return modifiers.any { it.kind == kind }
    }
    
    fun getModifiers(kind: ModifierKind): List<SymbolModifier> {
        return modifiers.filter { it.kind == kind }
    }
    
    fun hasDatabaseAccess(): Boolean = technicalContext.hasDatabaseAccess
    fun hasExternalCalls(): Boolean = technicalContext.hasExternalCalls
    fun hasCacheAccess(): Boolean = technicalContext.hasCacheAccess
    
    fun toSymbol(): Symbol = symbol
}
