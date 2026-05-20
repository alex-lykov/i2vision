/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.layer

import com.i2vision.arch.signature.EnrichedSymbol
import com.i2vision.arch.signature.toEnriched
import com.i2vision.intent.DiscoveryIntent
import com.i2vision.vslfc.SymbolKind
import com.i2vision.vslfc.VerbalizationResult
import com.i2vision.verbalization.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Vision layer verbalizer - transforms README/docs into purpose statements.
 * 
 * This verbalizer extracts high-level purpose, requirements, and constraints
 * from documentation files and maps them to the Vision layer context.
 * 
 * REFACTORED: Uses structured detection where applicable, minimal changes needed.
 */
class VisionVerbalizer : LayerVerbalizer {
    
    override val layerName: String = "Vision"
    
    override fun canHandle(symbol: EnrichedSymbol): Boolean {
        // Vision layer handles documentation-related symbols
        return symbol.symbol.filePath.endsWith(".md") || 
               symbol.symbol.filePath.contains("/docs/") ||
               symbol.symbol.filePath.endsWith("README.md") ||
               symbol.symbol.kind == SymbolKind.OBJECT && symbol.symbol.name.contains("Config")
    }
    
    override suspend fun verbalize(
        symbol: EnrichedSymbol,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): VerbalizationResult = withContext(Dispatchers.Default) {
        val purposeStatement = extractPurposeStatement(symbol)
        val requirements = extractRequirements(symbol)
        val constraints = extractConstraints(symbol)
        val technicalDecisions = extractTechnicalDecisions(symbol)
        val tradeoffs = extractTradeoffs(symbol)
        val architectureStyle = extractArchitectureStyle(symbol)
        
        val visionContext = VisionContext(
            architectureStyle = architectureStyle,
            qualityAttributes = requirements,
            technicalDecisions = technicalDecisions,
            tradeoffs = tradeoffs,
            structureContext = StructureContext(),
            logicContext = LogicContext(),
            flowContext = FlowContext()
        )
        
        val description = generateVisionDescription(visionContext, purposeStatement)
        
        VerbalizationResult(
            symbol = symbol.symbol,
            description = description,
            confidence = calculateConfidence(symbol, visionContext),
            strategy = intent.verbalization.strategy,
            metadata = mapOf(
                "layer" to "VISION",
                "purpose_length" to purposeStatement.length.toString(),
                "requirements_count" to requirements.size.toString(),
                "constraints_count" to constraints.size.toString()
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
     * Extract purpose statement from documentation or symbol metadata.
     * 
     * REFACTORED: Can use structured metadata if available.
     */
    private fun extractPurposeStatement(enriched: EnrichedSymbol): String {
        // Try to extract from structured metadata first
        val metadataPurpose = enriched.symbol.metadata["purpose"]
        if (metadataPurpose != null && metadataPurpose.toString().isNotEmpty()) {
            return metadataPurpose.toString()
        }
        
        // Try to extract from first paragraph of markdown
        val markdownPurposeRegex = Regex("""^#\s*(.+?)\n""", setOf(RegexOption.MULTILINE))
        val match = markdownPurposeRegex.find(enriched.symbol.content)
        
        if (match != null) {
            return match.groupValues[1].trim()
        }
        
        // Try to extract from class/object documentation
        val docRegex = Regex("""/\*\*\s*\n\s*\*([^*]*)""")
        val docMatch = docRegex.find(enriched.symbol.content)
        if (docMatch != null) {
            return docMatch.groupValues[1].trim().split("\n").firstOrNull()?.trim() 
                ?: "No purpose statement found"
        }
        
        // Fallback to symbol name with context
        return "Provides ${enriched.symbol.name} functionality for the system"
    }
    
    /**
     * Extract requirements from documentation.
     * 
     * REFACTORED: Can use structured metadata if available.
     */
    private fun extractRequirements(enriched: EnrichedSymbol): List<String> {
        // Try structured metadata first
        val metadataReqs = enriched.symbol.metadata["requirements"]
        if (metadataReqs is List<*>) {
            return metadataReqs.filterIsInstance<String>().ifEmpty { 
                listOf("System should fulfill its intended purpose") 
            }
        }
        
        val requirements = mutableListOf<String>()
        
        // Look for "## Requirements" or "## Features" sections
        val sectionRegex = Regex("""##\s*(?:Requirements|Features|Goals)\s*\n((?:[-*]\s*.+\n?)+)""", RegexOption.MULTILINE)
        val sectionMatch = sectionRegex.find(enriched.symbol.content)
        
        if (sectionMatch != null) {
            val items = sectionMatch.groupValues[1]
                .split(Regex("""\n(?=[-*])"""))
                .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
                .filter { it.isNotEmpty() }
            requirements.addAll(items)
        }
        
        // Also look for numbered requirements
        val numberedRegex = Regex("""\d+\.\s*([A-Z].+?)(?=\n\d+\.|\n\n|$)""", RegexOption.MULTILINE)
        numberedRegex.findAll(enriched.symbol.content)
            .map { it.groupValues[1].trim() }
            .forEach { requirements.add(it) }
        
        return requirements.ifEmpty { listOf("System should fulfill its intended purpose") }
    }
    
    /**
     * Extract constraints from documentation.
     * 
     * REFACTORED: Can use structured metadata if available.
     */
    private fun extractConstraints(enriched: EnrichedSymbol): List<String> {
        // Try structured metadata first
        val metadataConstraints = enriched.symbol.metadata["constraints"]
        if (metadataConstraints is List<*>) {
            return metadataConstraints.filterIsInstance<String>()
        }
        
        val constraints = mutableListOf<String>()
        
        // Look for "## Constraints" or "## Non-functional Requirements" sections
        val sectionRegex = Regex("""##\s*(?:Constraints|Non-functional Requirements|Limitations)\s*\n((?:[-*]\s*.+\n?)+)""", RegexOption.MULTILINE)
        val sectionMatch = sectionRegex.find(enriched.symbol.content)
        
        if (sectionMatch != null) {
            val items = sectionMatch.groupValues[1]
                .split(Regex("""\n(?=[-*])"""))
                .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
                .filter { it.isNotEmpty() }
            constraints.addAll(items)
        }
        
        // Look for "must", "should", "cannot" patterns
        val constraintPatterns = listOf(
            Regex("""(.*?)(?:must|shall)\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(.*?)(?:cannot|must not|shall not)\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(.*?)(?:limited to|restricted to)\s+(.+)""", RegexOption.IGNORE_CASE)
        )
        
        constraintPatterns.forEach { pattern ->
            pattern.findAll(enriched.symbol.content)
                .map { it.groupValues[2].trim() }
                .forEach { constraints.add(it) }
        }
        
        return constraints
    }
    
    /**
     * Extract technical decisions from documentation.
     */
    private fun extractTechnicalDecisions(enriched: EnrichedSymbol): List<String> {
        val decisions = mutableListOf<String>()
        
        // Look for "## Technical Decisions" or "## Architecture Decisions" sections
        val sectionRegex = Regex("""##\s*(?:Technical Decisions|Architecture Decisions|ADRs)\s*\n((?:[-*]\s*.+\n?)+)""", RegexOption.MULTILINE)
        val sectionMatch = sectionRegex.find(enriched.symbol.content)
        
        if (sectionMatch != null) {
            val items = sectionMatch.groupValues[1]
                .split(Regex("""\n(?=[-*])"""))
                .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
                .filter { it.isNotEmpty() }
            decisions.addAll(items)
        }
        
        return decisions.ifEmpty { listOf("Uses appropriate technology stack") }
    }
    
    /**
     * Extract tradeoffs from documentation.
     */
    private fun extractTradeoffs(enriched: EnrichedSymbol): List<String> {
        val tradeoffs = mutableListOf<String>()
        
        // Look for "## Tradeoffs" or "## Considerations" sections
        val sectionRegex = Regex("""##\s*(?:Tradeoffs|Considerations|Pros and Cons)\s*\n((?:[-*]\s*.+\n?)+)""", RegexOption.MULTILINE)
        val sectionMatch = sectionRegex.find(enriched.symbol.content)
        
        if (sectionMatch != null) {
            val items = sectionMatch.groupValues[1]
                .split(Regex("""\n(?=[-*])"""))
                .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
                .filter { it.isNotEmpty() }
            tradeoffs.addAll(items)
        }
        
        return tradeoffs
    }
    
    /**
     * Extract architecture style from documentation.
     */
    private fun extractArchitectureStyle(enriched: EnrichedSymbol): String {
        // Look for architecture style mentions
        val stylePatterns = listOf(
            "microservices" to "Microservices",
            "monolithic" to "Monolithic",
            "layered" to "Layered",
            "event-driven" to "Event-Driven",
            "hexagonal" to "Hexagonal",
            "clean architecture" to "Clean Architecture",
            "domain-driven" to "Domain-Driven Design"
        )
        
        stylePatterns.forEach { (pattern, style) ->
            if (Regex("""\b$pattern\b""", RegexOption.IGNORE_CASE).containsMatchIn(enriched.symbol.content)) {
                return style
            }
        }
        
        return "Not specified"
    }
    
    /**
     * Generate natural language description from vision context.
     */
    private fun generateVisionDescription(context: VisionContext, purposeStatement: String): String {
        val sb = StringBuilder()
        
        sb.append("$purposeStatement. ")
        
        if (context.architectureStyle != "Not specified") {
            sb.append("Uses ${context.architectureStyle} architecture. ")
        }
        
        if (context.qualityAttributes.isNotEmpty()) {
            sb.append("Key requirements: ${context.qualityAttributes.take(3).joinToString(", ")}. ")
        }
        
        if (context.technicalDecisions.isNotEmpty()) {
            sb.append("Technical decisions: ${context.technicalDecisions.take(2).joinToString(", ")}. ")
        }
        
        return sb.toString().trim()
    }
    
    /**
     * Calculate confidence based on documentation quality.
     */
    private fun calculateConfidence(enriched: EnrichedSymbol, context: VisionContext): Double {
        var confidence = 0.5 // Base confidence for documentation
        
        // Higher confidence if purpose statement is clear
        if (enriched.symbol.metadata["purpose"] != null) confidence += 0.2
        
        // Higher confidence if requirements are documented
        if (context.qualityAttributes.isNotEmpty()) confidence += 0.15
        
        // Higher confidence if architecture is specified
        if (context.architectureStyle != "Not specified") confidence += 0.15
        
        return confidence.coerceAtMost(0.95)
    }
}
