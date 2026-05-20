/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.layer

import com.i2vision.arch.signature.EnrichedSymbol
import com.i2vision.arch.signature.ModifierKind
import com.i2vision.arch.signature.SymbolModifier
import com.i2vision.intent.DiscoveryIntent
import com.i2vision.vslfc.SymbolKind
import com.i2vision.vslfc.VerbalizationResult
import com.i2vision.verbalization.CodeContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Code layer verbalizer - transforms symbol details into API reference documentation.
 * 
 * This verbalizer analyzes code symbols (classes, functions, properties) to produce
 * detailed API documentation with signatures, parameters, and documentation.
 */
class CodeVerbalizer : LayerVerbalizer {
    
    override val layerName: String = "Code"
    
    override fun canHandle(symbol: EnrichedSymbol): Boolean {
        // Code layer handles all code symbols
        return true
    }
    
    override suspend fun verbalize(
        symbol: EnrichedSymbol,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): VerbalizationResult = withContext(Dispatchers.Default) {
        // Use PSI-extracted modifiers from EnrichedSymbol (no regex!)
        val signature = extractSignature(symbol)
        val documentation = extractDocumentation(symbol.symbol)
        val implementations = findImplementations(symbol.symbol)
        
        val codeContext = CodeContext(
            symbol = symbol.symbol,
            signature = signature,
            documentation = documentation,
            implementations = implementations
        )
        
        val description = generateCodeDescription(codeContext)
        val modifiers = extractModifiersFromEnrichedSymbol(symbol)
        
        VerbalizationResult(
            symbol = symbol.symbol,
            description = description,
            confidence = calculateConfidence(symbol.symbol, codeContext),
            strategy = intent.verbalization.strategy,
            metadata = mapOf(
                "layer" to "CODE",
                "symbol_kind" to symbol.symbol.kind.name,
                "has_documentation" to (documentation != null).toString(),
                "implementations_count" to implementations.size.toString(),
                "signature_length" to signature.length.toString(),
                "modifiers" to modifiers
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
    
    /**
     * Extract signature from symbol using PSI-extracted modifiers from EnrichedSymbol.
     * This replaces regex-based detection with AST-based detection from discovery phase.
     */
    private fun extractSignature(enrichedSymbol: EnrichedSymbol): String {
        val symbol = enrichedSymbol.symbol
        val modifiers = extractModifiersFromEnrichedSymbol(enrichedSymbol)
        return when (symbol.kind) {
            SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM, SymbolKind.OBJECT -> {
                val kind = symbol.kind.name.lowercase()
                "$modifiers $kind ${symbol.name}"
            }
            SymbolKind.FUNCTION -> {
                val returnType = extractReturnType(symbol.content)
                val params = extractParameters(symbol.content)
                "$modifiers fun ${symbol.name}($params)$returnType"
            }
            SymbolKind.PROPERTY, SymbolKind.VARIABLE -> {
                val type = extractPropertyType(symbol.content)
                "$modifiers val/var ${symbol.name}: $type"
            }
            SymbolKind.ANNOTATION -> {
                "@${symbol.name}"
            }
            SymbolKind.TYPE_ALIAS -> {
                "typealias ${symbol.name} = ..."
            }
            SymbolKind.UNKNOWN -> {
                symbol.name
            }
        }
    }

    /**
     * Extract modifiers from EnrichedSymbol (PSI-based, not regex).
     * This uses modifiers already extracted during discovery phase via KotlinModifierExtractor.
     */
    private fun extractModifiersFromEnrichedSymbol(enrichedSymbol: EnrichedSymbol): String {
        val modifiers = enrichedSymbol.modifiers.map { it.toModifierString() }
        return modifiers.joinToString(" ")
    }

    /**
     * Convert SymbolModifier to display string.
     * Uses PSI-extracted modifiers from EnrichedSymbol.
     */
    private fun SymbolModifier.toModifierString(): String {
        return when (kind) {
            ModifierKind.SUSPEND -> "suspend"
            ModifierKind.DATA_CLASS -> "data"
            ModifierKind.VALUE_CLASS -> "value"
            ModifierKind.SEALED_CLASS -> "sealed"
            ModifierKind.SEALED_INTERFACE -> "sealed interface"
            ModifierKind.COMPANION_OBJECT -> "companion"
            ModifierKind.DELEGATE -> "by"
            ModifierKind.ABSTRACT -> "abstract"
            ModifierKind.FINAL -> "final"
            ModifierKind.OVERRIDE -> "override"
            ModifierKind.STATIC -> "static"
            ModifierKind.SYNC -> "synchronized"
            ModifierKind.ASYNC -> "async"
            ModifierKind.EXTENSION -> "extension"
            ModifierKind.ANNOTATION -> "@interface"
            ModifierKind.RECORD -> "record"
            ModifierKind.BUSINESS_RULE -> "business_rule"
            ModifierKind.INVARIANT -> "invariant"
            ModifierKind.VALIDATION -> "validation"
            ModifierKind.FLOW -> "flow"
            ModifierKind.SEQUENCE -> "sequence"
            ModifierKind.EVENT_PUBLISHER -> "event_publisher"
            ModifierKind.EVENT_CONSUMER -> "event_consumer"
            ModifierKind.DATABASE_ACCESS -> "database"
            ModifierKind.EXTERNAL_CALL -> "external"
            ModifierKind.CACHE_ACCESS -> "cache"
            ModifierKind.REPOSITORY -> "repository"
            ModifierKind.CONTROLLER -> "controller"
            ModifierKind.SERVICE -> "service"
            ModifierKind.FACTORY -> "factory"
            ModifierKind.BUILDER -> "builder"
            ModifierKind.OBSERVER -> "observer"
            ModifierKind.STRATEGY -> "strategy"
            ModifierKind.SINGLETON -> "singleton"
            else -> kind.name.lowercase()
        }
    }
    
    /**
     * Extract return type from function.
     */
    private fun extractReturnType(content: String): String {
        val returnRegex = Regex(""":\s*(\w+(?:<[^>]+>)?)""")
        val match = returnRegex.find(content)
        return match?.let { ": ${it.groupValues[1]}" } ?: ""
    }
    
    /**
     * Extract parameters from function.
     */
    private fun extractParameters(content: String): String {
        val paramsRegex = Regex("""fun\s+\w+\s*\(([^)]*)\)""")
        val match = paramsRegex.find(content)
        return match?.groupValues?.get(1)?.trim()?.take(100) ?: ""
    }
    
    /**
     * Extract property type.
     */
    private fun extractPropertyType(content: String): String {
        val typeRegex = Regex("""(?:val|var)\s+\w+\s*:\s*(\w+(?:<[^>]+>)?)""")
        val match = typeRegex.find(content)
        return match?.groupValues?.get(1) ?: "Any"
    }
    
    /**
     * Extract documentation from symbol.
     */
    private fun extractDocumentation(symbol: com.i2vision.vslfc.Symbol): String? {
        // Look for KDoc comments
        val kdocRegex = Regex("""/\*\*\s*\n((?:\s*\*[^\n]*\n)+)\s*\*/""", RegexOption.MULTILINE)
        val match = kdocRegex.find(symbol.content)
        
        if (match != null) {
            return match.groupValues[1]
                .split("\n")
                .map { it.trim().removePrefix("*").trim() }
                .filter { it.isNotEmpty() && !it.startsWith("@") }
                .joinToString(" ")
                .trim()
        }
        
        // Look for single-line comments before symbol
        val commentRegex = Regex("""//\s*(.+)""")
        val comments = commentRegex.findAll(symbol.content)
            .map { it.groupValues[1].trim() }
            .toList()
        
        if (comments.isNotEmpty()) {
            return comments.joinToString(" ")
        }
        
        return null
    }
    
    /**
     * Find implementations (for interfaces/abstract classes).
     */
    private fun findImplementations(symbol: com.i2vision.vslfc.Symbol): List<String> {
        val implementations = mutableListOf<String>()
        
        if (symbol.kind == SymbolKind.INTERFACE) {
            // Look for classes implementing this interface
            val implRegex = Regex("""class\s+(\w+)\s*:\s*${Regex.escape(symbol.name)}""")
            implRegex.findAll(symbol.content)
                .map { it.groupValues[1] }
                .forEach { implementations.add(it) }
        } else if (symbol.kind == SymbolKind.CLASS) {
            // Check if it's abstract and look for subclasses
            if (Regex("""\babstract\b""").containsMatchIn(symbol.content)) {
                val subclassRegex = Regex("""class\s+(\w+)\s*:\s*${Regex.escape(symbol.name)}""")
                subclassRegex.findAll(symbol.content)
                    .map { it.groupValues[1] }
                    .forEach { implementations.add(it) }
            }
        }
        
        return implementations
    }
    
    /**
     * Generate natural language description from code context.
     */
    private fun generateCodeDescription(context: CodeContext): String {
        val sb = StringBuilder()
        
        // Add signature
        sb.append("Signature: ${context.signature}. ")
        
        // Add documentation if available
        if (context.documentation != null) {
            sb.append("Documentation: ${context.documentation}. ")
        }
        
        // Add implementation info
        if (context.implementations.isNotEmpty()) {
            sb.append("Implementations: ${context.implementations.joinToString(", ")}. ")
        }
        
        // Add symbol-specific info
        when (context.symbol.kind) {
            SymbolKind.CLASS -> {
                sb.append("Class definition with ${countMembers(context.symbol.content)} members.")
            }
            SymbolKind.INTERFACE -> {
                sb.append("Interface defining ${countMembers(context.symbol.content)} contracts.")
            }
            SymbolKind.FUNCTION -> {
                val params = extractParameters(context.symbol.content)
                sb.append("Function with ${params.split(",").filter { it.isNotEmpty() }.size} parameters.")
            }
            SymbolKind.PROPERTY, SymbolKind.VARIABLE -> {
                sb.append("Property/variable declaration.")
            }
            else -> {
                sb.append("${context.symbol.kind.name} symbol.")
            }
        }
        
        return sb.toString().trim()
    }
    
    /**
     * Count class/interface members.
     */
    private fun countMembers(content: String): Int {
        var count = 0
        count += Regex("""fun\s+\w+\s*\(""").findAll(content).count()
        count += Regex("""(?:val|var)\s+\w+""").findAll(content).count()
        return count
    }
    
    /**
     * Calculate confidence based on code analysis quality.
     */
    private fun calculateConfidence(symbol: com.i2vision.vslfc.Symbol, context: CodeContext): Double {
        var confidence = 0.6 // Base confidence for code symbols
        
        // Higher confidence if documentation exists
        if (context.documentation != null) {
            confidence += 0.2
        }
        
        // Higher confidence for well-structured code
        if (context.signature.isNotEmpty()) {
            confidence += 0.1
        }
        
        // Higher confidence if implementations found
        if (context.implementations.isNotEmpty()) {
            confidence += 0.1
        }
        
        return confidence.coerceAtMost(1.0)
    }
}
