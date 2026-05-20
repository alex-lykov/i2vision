/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.layer

import com.i2vision.arch.signature.EnrichedSymbol
import com.i2vision.arch.signature.ModifierKind
import com.i2vision.intent.DiscoveryIntent
import com.i2vision.vslfc.SymbolKind
import com.i2vision.vslfc.VerbalizationResult
import com.i2vision.verbalization.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Logic layer verbalizer - transforms invariants/rules into decision tables.
 * 
 * REFACTORED: Uses structured detection from EnrichedSymbol. No fallback.
 */
class LogicVerbalizer : LayerVerbalizer {
    
    override val layerName: String = "Logic"
    
    override fun canHandle(symbol: EnrichedSymbol): Boolean {
        return symbol.symbol.kind in listOf(SymbolKind.CLASS, SymbolKind.OBJECT, SymbolKind.FUNCTION) ||
               symbol.symbol.filePath.contains("/rule/") ||
               symbol.symbol.filePath.contains("/validation/") ||
               symbol.symbol.filePath.contains("/business/") ||
               symbol.symbol.filePath.contains("/domain/")
    }
    
    override suspend fun verbalize(
        symbol: EnrichedSymbol,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): VerbalizationResult = withContext(Dispatchers.Default) {
        val invariants = extractInvariants(symbol)
        val rules = extractRules(symbol)
        val stateMachines = extractStateMachines(symbol)
        
        val logicContext = LogicContext(
            invariants = invariants,
            rules = rules,
            stateMachine = stateMachines.firstOrNull(),
            algorithms = emptyList()
        )
        
        val description = generateLogicDescription(logicContext)
        
        VerbalizationResult(
            symbol = symbol.symbol,
            description = description,
            confidence = calculateConfidence(symbol, logicContext),
            strategy = intent.verbalization.strategy,
            metadata = mapOf(
                "layer" to "LOGIC",
                "invariants_count" to invariants.size.toString(),
                "rules_count" to rules.size.toString(),
                "state_machines_count" to stateMachines.size.toString(),
                "business_rules" to symbol.businessRules.joinToString(";")
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
    
    private fun extractInvariants(enriched: EnrichedSymbol): List<InvariantInfo> {
        val invariants = mutableListOf<InvariantInfo>()
        
        // Use pre-extracted business rules
        enriched.businessRules.forEach { rule ->
            invariants.add(
                InvariantInfo(
                    name = "Invariant ${rule.hashCode()}",
                    condition = rule,
                    severity = InvariantSeverity.HIGH,
                    description = "Business rule"
                )
            )
        }
        
        // Also extract from content if no pre-extracted rules
        if (invariants.isEmpty()) {
            val content = enriched.symbol.content
            val requirePattern = Regex("""require\s*\((?:[^()]|\([^)]*\))+\)\s*\{\s*["']([^"']+)["']\s*\}""")
            requirePattern.findAll(content).forEach { match ->
                invariants.add(
                    InvariantInfo(
                        name = "Invariant ${match.groupValues[1].hashCode()}",
                        condition = match.groupValues[1],
                        severity = InvariantSeverity.HIGH,
                        description = "Validation requirement"
                    )
                )
            }
        }
        
        return invariants
    }
    
    private fun extractRules(enriched: EnrichedSymbol): List<RuleInfo> {
        val rules = mutableListOf<RuleInfo>()
        
        // Use pre-extracted business rules
        enriched.businessRules.forEachIndexed { index, rule ->
            rules.add(
                RuleInfo(
                    name = "Business Rule ${index + 1}",
                    condition = rule,
                    action = "Enforce rule",
                    priority = index + 1,
                    description = "Business rule enforcement"
                )
            )
        }
        
        // Also extract from content if no pre-extracted rules
        if (rules.isEmpty()) {
            val content = enriched.symbol.content
            
            // Match when expressions with various patterns
            val whenPatterns = listOf(
                // Pattern 1: when(condition) { branches }
                Regex("""when\s*\(\s*[^)]+\s*\)\s*\{([^}]+)\}""", RegexOption.DOT_MATCHES_ALL),
                // Pattern 2: when condition { branches }
                Regex("""when\s+\w+\s+\{([^}]+)\}""", RegexOption.DOT_MATCHES_ALL)
            )
            
            for (pattern in whenPatterns) {
                pattern.find(content)?.let { match ->
                    val whenBody = match.groupValues[1]
                    // Split by branches: look for "->" as delimiter
                    val branchPattern = Regex("""\s*([A-Za-z0-9_.\[\]]+)\s*->""")
                    branchPattern.findAll(whenBody).forEachIndexed { index, branchMatch ->
                        val condition = branchMatch.groupValues[1]
                        // Find the action after ->
                        val afterArrow = whenBody.substring(branchMatch.range.last + 1)
                        val actionMatch = Regex("""\s*([A-Za-z0-9_]+)\s*\(""").find(afterArrow)
                        val action = actionMatch?.groupValues?.get(1) ?: "handle"
                        
                        rules.add(
                            RuleInfo(
                                name = "Branch Rule ${index + 1}",
                                condition = condition,
                                action = action,
                                priority = index + 1,
                                description = "State transition rule"
                            )
                        )
                    }
                    if (rules.isNotEmpty()) return@let
                }
            }
        }
        
        return rules
    }
    
    private fun extractStateMachines(enriched: EnrichedSymbol): List<StateMachineInfo> {
        // Would extract from structured enum data in full implementation
        return emptyList()
    }
    
    private fun generateLogicDescription(context: LogicContext): String {
        val sb = StringBuilder()
        
        if (context.invariants.isNotEmpty()) {
            sb.append("Enforces ${context.invariants.size} invariant(s)")
            if (context.invariants.size <= 3) {
                val conditions = context.invariants.map { it.condition }.take(2)
                sb.append(": ${conditions.joinToString(", ")}")
            }
        }
        
        if (context.rules.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(". ")
            sb.append("Implements ${context.rules.size} business rule(s)")
        }
        
        if (context.stateMachine != null) {
            if (sb.isNotEmpty()) sb.append(". ")
            sb.append("Manages state machine with ${context.stateMachine.states.size} state(s)")
        }
        
        return sb.toString().ifEmpty { "Contains business logic" }
    }
    
    private fun calculateConfidence(enriched: EnrichedSymbol, context: LogicContext): Double {
        var confidence = 0.6
        
        if (enriched.businessRules.isNotEmpty()) confidence += 0.2
        if (enriched.hasModifier(ModifierKind.VALIDATION) || enriched.hasModifier(ModifierKind.INVARIANT)) confidence += 0.15
        if (context.rules.isNotEmpty()) confidence += 0.1
        
        return minOf(confidence, 0.95)
    }
}

