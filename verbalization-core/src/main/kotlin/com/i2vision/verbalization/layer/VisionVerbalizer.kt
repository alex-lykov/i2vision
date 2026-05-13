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
 * Vision layer verbalizer - transforms README/docs into purpose statements.
 * 
 * This verbalizer extracts high-level purpose, requirements, and constraints
 * from documentation files and maps them to the Vision layer context.
 */
class VisionVerbalizer : LayerVerbalizer {
    
    override val layerName: String = "Vision"
    
    override fun canHandle(symbol: Symbol): Boolean {
        // Vision layer handles documentation-related symbols
        return symbol.filePath.endsWith(".md") || 
               symbol.filePath.contains("/docs/") ||
               symbol.filePath.endsWith("README.md") ||
               symbol.kind == SymbolKind.OBJECT && symbol.name.contains("Config")
    }
    
    override suspend fun verbalize(
        symbol: Symbol,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): VerbalizationResult = withContext(Dispatchers.Default) {
        val purposeStatement = extractPurposeStatement(symbol)
        val requirements = extractRequirements(symbol)
        val constraints = extractConstraints(symbol)
        
        val visionContext = VisionContext(
            purposeStatement = purposeStatement,
            requirements = requirements,
            constraints = constraints,
            stakeholders = extractStakeholders(symbol)
        )
        
        val description = generateVisionDescription(visionContext)
        
        VerbalizationResult(
            symbol = symbol,
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
        symbols: List<Symbol>,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {
        return symbols.filter { canHandle(it) }
            .map { verbalize(it, context, intent) }
    }
    
    /**
     * Extract purpose statement from documentation or symbol metadata.
     */
    private fun extractPurposeStatement(symbol: Symbol): String {
        // Try to extract from first paragraph of markdown
        val markdownPurposeRegex = Regex("""^#\s*(.+?)\n""", setOf(RegexOption.MULTILINE))
        val match = markdownPurposeRegex.find(symbol.content)
        
        if (match != null) {
            return match.groupValues[1].trim()
        }
        
        // Try to extract from class/object documentation
        val docRegex = Regex("""/\*\*\s*\n\s*\*([^*]*)""")
        val docMatch = docRegex.find(symbol.content)
        if (docMatch != null) {
            return docMatch.groupValues[1].trim().split("\n").firstOrNull()?.trim() ?: "No purpose statement found"
        }
        
        // Fallback to symbol name with context
        return "Provides ${symbol.name} functionality for the system"
    }
    
    /**
     * Extract requirements from documentation.
     */
    private fun extractRequirements(symbol: Symbol): List<String> {
        val requirements = mutableListOf<String>()
        
        // Look for "## Requirements" or "## Features" sections
        val sectionRegex = Regex("""##\s*(?:Requirements|Features|Goals)\s*\n((?:[-*]\s*.+\n?)+)""", RegexOption.MULTILINE)
        val sectionMatch = sectionRegex.find(symbol.content)
        
        if (sectionMatch != null) {
            val items = sectionMatch.groupValues[1]
                .split(Regex("""\n(?=[-*])"""))
                .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
                .filter { it.isNotEmpty() }
            requirements.addAll(items)
        }
        
        // Also look for numbered requirements
        val numberedRegex = Regex("""\d+\.\s*([A-Z].+?)(?=\n\d+\.|\n\n|$)""", RegexOption.MULTILINE)
        numberedRegex.findAll(symbol.content)
            .map { it.groupValues[1].trim() }
            .forEach { requirements.add(it) }
        
        return requirements.ifEmpty { listOf("System should fulfill its intended purpose") }
    }
    
    /**
     * Extract constraints from documentation.
     */
    private fun extractConstraints(symbol: Symbol): List<String> {
        val constraints = mutableListOf<String>()
        
        // Look for "## Constraints" or "## Non-functional Requirements" sections
        val sectionRegex = Regex("""##\s*(?:Constraints|Non-functional Requirements|Limitations)\s*\n((?:[-*]\s*.+\n?)+)""", RegexOption.MULTILINE)
        val sectionMatch = sectionRegex.find(symbol.content)
        
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
            pattern.findAll(symbol.content)
                .map { it.groupValues[2].trim() }
                .forEach { constraints.add(it) }
        }
        
        return constraints.ifEmpty { emptyList() }
    }
    
    /**
     * Extract stakeholders from documentation.
     */
    private fun extractStakeholders(symbol: Symbol): List<String> {
        val stakeholders = mutableListOf<String>()
        
        // Look for stakeholder sections
        val stakeholderRegex = Regex("""##\s*(?:Stakeholders|Users|Roles)\s*\n((?:[-*]\s*.+\n?)+)""", RegexOption.MULTILINE)
        val match = stakeholderRegex.find(symbol.content)
        
        if (match != null) {
            val items = match.groupValues[1]
                .split(Regex("""\n(?=[-*])"""))
                .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
                .filter { it.isNotEmpty() }
                .map { it.split(":").firstOrNull()?.trim() ?: it }
            stakeholders.addAll(items)
        }
        
        return stakeholders.ifEmpty { listOf("System Users", "Developers") }
    }
    
    /**
     * Generate natural language description from vision context.
     */
    private fun generateVisionDescription(context: VisionContext): String {
        val sb = StringBuilder()
        
        sb.append("Purpose: ${context.purposeStatement}. ")
        
        if (context.requirements.isNotEmpty()) {
            sb.append("Key requirements include: ${context.requirements.take(3).joinToString(", ")}. ")
        }
        
        if (context.constraints.isNotEmpty()) {
            sb.append("Constraints: ${context.constraints.take(2).joinToString(", ")}. ")
        }
        
        if (context.stakeholders.isNotEmpty()) {
            sb.append("Primary stakeholders: ${context.stakeholders.take(3).joinToString(", ")}.")
        }
        
        return sb.toString().trim()
    }
    
    /**
     * Calculate confidence based on documentation quality.
     */
    private fun calculateConfidence(symbol: Symbol, context: VisionContext): Double {
        var confidence = 0.5
        
        // Higher confidence if we found a purpose statement
        if (context.purposeStatement.isNotEmpty() && context.purposeStatement.length > 10) {
            confidence += 0.2
        }
        
        // Higher confidence if we found requirements
        if (context.requirements.isNotEmpty()) {
            confidence += 0.15
        }
        
        // Higher confidence for actual documentation files
        if (symbol.filePath.endsWith(".md")) {
            confidence += 0.15
        }
        
        return minOf(confidence, 0.95)
    }
}
