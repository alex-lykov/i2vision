/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.arch.detector

import com.i2vision.arch.signature.EnrichedSymbol
import com.i2vision.arch.signature.EnrichedSymbolBuilder
import com.i2vision.arch.signature.ModifierKind
import com.i2vision.arch.signature.ModifierSource
import com.i2vision.arch.signature.StructuralRole
import com.i2vision.arch.signature.SymbolModifier
import com.i2vision.arch.signature.TechnicalContext
import com.i2vision.vslfc.Symbol

/**
 * Interface for extracting modifiers from AST/PSI during discovery phase.
 */
interface ModifierExtractor<T> {
    fun extractModifiers(node: T): List<SymbolModifier>
    fun extractStructuralRole(node: T): StructuralRole? = null
    fun extractTechnicalContext(node: T): TechnicalContext = TechnicalContext()
    
    fun enrich(symbol: Symbol, node: T): EnrichedSymbol {
        val modifiers = extractModifiers(node)
        val role = extractStructuralRole(node)
        val context = extractTechnicalContext(node)
        
        val builder = EnrichedSymbolBuilder(symbol)
        builder.addModifiers(modifiers)
        role?.let { builder.setStructuralRole(it) }
        builder.setTechnicalContext(context)
        return builder.build()
    }
}

/**
 * Kotlin-specific modifier extractor using Kotlin PSI.
 */
class KotlinModifierExtractor : ModifierExtractor<org.jetbrains.kotlin.psi.KtDeclaration> {
    
    override fun extractModifiers(node: org.jetbrains.kotlin.psi.KtDeclaration): List<SymbolModifier> {
        val modifiers = mutableListOf<SymbolModifier>()
        
        // Check for suspend modifier
        if (node.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SUSPEND_KEYWORD)) {
            modifiers.add(SymbolModifier(ModifierKind.SUSPEND, source = ModifierSource.AST))
        }
        
        // Check for data class
        if (node is org.jetbrains.kotlin.psi.KtClass && node.isData()) {
            modifiers.add(SymbolModifier(ModifierKind.DATA_CLASS, source = ModifierSource.AST))
        }
        
        // Check for sealed class/interface
        if (node is org.jetbrains.kotlin.psi.KtClass && node.isSealed()) {
            modifiers.add(
                SymbolModifier(
                    kind = if (node is org.jetbrains.kotlin.psi.KtClass) ModifierKind.SEALED_CLASS 
                           else ModifierKind.SEALED_INTERFACE,
                    source = ModifierSource.AST
                )
            )
        }
        
        // Check for value/inline class
        if (node is org.jetbrains.kotlin.psi.KtClass && node.isInline()) {
            modifiers.add(SymbolModifier(ModifierKind.VALUE_CLASS, source = ModifierSource.AST))
        }

        // Also check for value class using VALUE_CLASS keyword token (Kotlin 1.5+)
        if (node is org.jetbrains.kotlin.psi.KtClass &&
            node.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.VALUE_KEYWORD)) {
            modifiers.add(SymbolModifier(ModifierKind.VALUE_CLASS, source = ModifierSource.AST))
        }

        // Check for @JvmInline annotation which is required for JVM value classes
        if (node is org.jetbrains.kotlin.psi.KtClass) {
            val hasJvmInlineAnnotation = node.annotationEntries.any { annotation ->
                annotation.shortName?.asString() == "JvmInline"
            }
            if (hasJvmInlineAnnotation) {
                modifiers.add(SymbolModifier(ModifierKind.VALUE_CLASS, source = ModifierSource.ANNOTATION))
            }
        }
        
        // Check for companion object
        if (node is org.jetbrains.kotlin.psi.KtObjectDeclaration && node.isCompanion()) {
            modifiers.add(SymbolModifier(ModifierKind.COMPANION_OBJECT, source = ModifierSource.AST))
        }
        
        // Check for extension function/property
        if (node is org.jetbrains.kotlin.psi.KtNamedFunction && node.receiverTypeReference != null) {
            modifiers.add(SymbolModifier(ModifierKind.EXTENSION, source = ModifierSource.AST))
        }
        
        // Check for delegation (by keyword) - simplified
        if (node is org.jetbrains.kotlin.psi.KtClass) {
            val text = node.text
            if (text.contains(" by ")) {
                modifiers.add(SymbolModifier(ModifierKind.DELEGATE, source = ModifierSource.AST))
            }
        }
        
        // Check for annotations indicating architectural patterns
        extractAnnotationModifiers(node, modifiers)
        
        // Check for business rules and validation
        extractBusinessRuleModifiers(node, modifiers)
        
        // Check for technical capabilities
        extractTechnicalModifiers(node, modifiers)
        
        return modifiers
    }
    
    override fun extractStructuralRole(node: org.jetbrains.kotlin.psi.KtDeclaration): StructuralRole? {
        // Check annotations first
        val annotations = node.annotationEntries
        for (annotation in annotations) {
            val annotationName = annotation.shortName?.asString() ?: continue
            when {
                annotationName in listOf("Repository", "SpringRepository") -> return StructuralRole.REPOSITORY
                annotationName in listOf("Controller", "RestController", "GetMapping", "PostMapping") -> return StructuralRole.CONTROLLER
                annotationName in listOf("Service") -> return StructuralRole.SERVICE
                annotationName in listOf("Component", "Bean") -> return StructuralRole.SERVICE
                annotationName in listOf("Entity", "Table") -> return StructuralRole.ENTITY
                annotationName in listOf("Builder") -> return StructuralRole.BUILDER
                annotationName in listOf("Factory") -> return StructuralRole.FACTORY
            }
        }
        
        // Check naming conventions
        val name = node.name ?: return null
        return when {
            name.endsWith("Repository") -> StructuralRole.REPOSITORY
            name.endsWith("Controller") -> StructuralRole.CONTROLLER
            name.endsWith("Service") -> StructuralRole.SERVICE
            name.endsWith("Factory") -> StructuralRole.FACTORY
            name.endsWith("Builder") -> StructuralRole.BUILDER
            name.endsWith("Validator") -> StructuralRole.VALIDATOR
            name.endsWith("Transformer") -> StructuralRole.TRANSFORMER
            name.endsWith("Orchestrator") -> StructuralRole.ORCHESTRATOR
            name.endsWith("Dispatcher") -> StructuralRole.DISPATCHER
            name.endsWith("Handler") -> StructuralRole.EVENT_HANDLER
            name.endsWith("Producer") -> StructuralRole.EVENT_PRODUCER
            name.endsWith("Consumer") -> StructuralRole.EVENT_CONSUMER
            else -> null
        }
    }
    
    override fun extractTechnicalContext(node: org.jetbrains.kotlin.psi.KtDeclaration): TechnicalContext {
        var hasDb = false
        var hasExternal = false
        var hasCache = false
        var isTx = false
        var isAsync = false
        
        // Check annotations
        node.annotationEntries.forEach { annotation ->
            val name = annotation.shortName?.asString() ?: return@forEach
            when {
                name in listOf("Transactional", "Transaction") -> isTx = true
                name in listOf("Async", "Scheduled") -> isAsync = true
                name in listOf("Repository", "Entity", "Table", "Column") -> hasDb = true
                name in listOf("Cacheable", "CachePut", "CacheEvict") -> hasCache = true
                name in listOf("RestClient", "HttpClient") -> hasExternal = true
            }
        }
        
        // Check for database access in code
        val text = node.text
        if (text.contains("entityManager") || 
            text.contains("JdbcTemplate") ||
            text.contains("Exposed") ||
            text.contains("Room") ||
            text.contains("RoomDatabase")) {
            hasDb = true
        }
        
        // Check for external calls
        if (text.contains("HttpClient") ||
            text.contains("RestTemplate") ||
            text.contains("WebClient") ||
            text.contains("OkHttp")) {
            hasExternal = true
        }
        
        // Check for caching
        if (text.contains("CacheManager") ||
            text.contains("redis") ||
            text.contains("memcached")) {
            hasCache = true
        }
        
        return TechnicalContext(
            hasDatabaseAccess = hasDb,
            hasExternalCalls = hasExternal,
            hasCacheAccess = hasCache,
            isTransactional = isTx,
            isAsync = isAsync
        )
    }
    
    private fun extractAnnotationModifiers(
        node: org.jetbrains.kotlin.psi.KtDeclaration,
        modifiers: MutableList<SymbolModifier>
    ) {
        node.annotationEntries.forEach { annotation ->
            val name = annotation.shortName?.asString() ?: return@forEach
            when {
                name in listOf("Repository", "SpringRepository") -> 
                    modifiers.add(SymbolModifier(ModifierKind.REPOSITORY, source = ModifierSource.ANNOTATION))
                name in listOf("Controller", "RestController") -> 
                    modifiers.add(SymbolModifier(ModifierKind.CONTROLLER, source = ModifierSource.ANNOTATION))
                name in listOf("Service") -> 
                    modifiers.add(SymbolModifier(ModifierKind.SERVICE, source = ModifierSource.ANNOTATION))
                name in listOf("Component", "Bean") -> 
                    modifiers.add(SymbolModifier(ModifierKind.SERVICE, source = ModifierSource.ANNOTATION))
                name in listOf("Builder") -> 
                    modifiers.add(SymbolModifier(ModifierKind.BUILDER, source = ModifierSource.ANNOTATION))
                name in listOf("Factory") -> 
                    modifiers.add(SymbolModifier(ModifierKind.FACTORY, source = ModifierSource.ANNOTATION))
                name in listOf("Observer", "EventListener") -> 
                    modifiers.add(SymbolModifier(ModifierKind.OBSERVER, source = ModifierSource.ANNOTATION))
                name in listOf("Singleton", "Scope") -> 
                    modifiers.add(SymbolModifier(ModifierKind.SINGLETON, source = ModifierSource.ANNOTATION))
            }
        }
    }
    
    private fun extractBusinessRuleModifiers(
        node: org.jetbrains.kotlin.psi.KtDeclaration,
        modifiers: MutableList<SymbolModifier>
    ) {
        val text = node.text
        
        // Check for validation annotations
        node.annotationEntries.forEach { annotation ->
            val name = annotation.shortName?.asString() ?: return@forEach
            when {
                name in listOf("Valid", "Validated", "Constraint") -> 
                    modifiers.add(SymbolModifier(ModifierKind.VALIDATION, source = ModifierSource.ANNOTATION))
                name in listOf("BusinessRule", "Rule") -> 
                    modifiers.add(SymbolModifier(ModifierKind.BUSINESS_RULE, source = ModifierSource.ANNOTATION))
            }
        }
        
        // Check for validation logic in code
        if (text.contains("require(") || text.contains("check(") || text.contains("assert(")) {
            modifiers.add(SymbolModifier(ModifierKind.VALIDATION, source = ModifierSource.AST))
        }
        
        // Check for business rule patterns
        if (text.contains("if (") && text.contains("throw ")) {
            modifiers.add(SymbolModifier(ModifierKind.BUSINESS_RULE, source = ModifierSource.AST))
        }
    }
    
    private fun extractTechnicalModifiers(
        node: org.jetbrains.kotlin.psi.KtDeclaration,
        modifiers: MutableList<SymbolModifier>
    ) {
        val text = node.text
        
        // Check for database access
        if (text.contains("entityManager") || 
            text.contains("JdbcTemplate") ||
            text.contains("repository") ||
            text.contains("dao")) {
            modifiers.add(SymbolModifier(ModifierKind.DATABASE_ACCESS, source = ModifierSource.AST))
        }
        
        // Check for external calls
        if (text.contains("HttpClient") ||
            text.contains("RestTemplate") ||
            text.contains("WebClient") ||
            text.contains("http.")) {
            modifiers.add(SymbolModifier(ModifierKind.EXTERNAL_CALL, source = ModifierSource.AST))
        }
        
        // Check for caching
        if (text.contains("CacheManager") ||
            text.contains("redis") ||
            text.contains("@Cacheable")) {
            modifiers.add(SymbolModifier(ModifierKind.CACHE_ACCESS, source = ModifierSource.AST))
        }
    }
}
