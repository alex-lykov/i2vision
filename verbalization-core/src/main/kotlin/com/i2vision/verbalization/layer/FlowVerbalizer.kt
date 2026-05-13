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
 * Flow layer verbalizer - transforms call graphs into sequence descriptions.
 * 
 * REFACTORED: Uses structured detection from EnrichedSymbol. No fallback.
 */
class FlowVerbalizer : LayerVerbalizer {
    
    override val layerName: String = "Flow"
    
    override fun canHandle(symbol: EnrichedSymbol): Boolean {
        return symbol.symbol.kind in listOf(SymbolKind.FUNCTION, SymbolKind.CLASS, SymbolKind.INTERFACE) ||
               symbol.symbol.filePath.contains("/api/") ||
               symbol.symbol.filePath.contains("/controller/") ||
               symbol.symbol.filePath.contains("/handler/") ||
               symbol.symbol.filePath.contains("/service/")
    }
    
    override suspend fun verbalize(
        symbol: EnrichedSymbol,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): VerbalizationResult = withContext(Dispatchers.Default) {
        val sequences = extractSequences(symbol)
        val apiEndpoints = extractApiEndpoints(symbol)
        val interactions = extractInteractions(symbol)
        
        val flowContext = FlowContext(
            sequences = sequences,
            apiEndpoints = apiEndpoints,
            interactions = interactions
        )
        
        val description = generateFlowDescription(flowContext)
        
        VerbalizationResult(
            symbol = symbol.symbol,
            description = description,
            confidence = calculateConfidence(symbol, flowContext),
            strategy = intent.verbalization.strategy,
            metadata = mapOf(
                "layer" to "FLOW",
                "sequences_count" to sequences.size.toString(),
                "api_endpoints_count" to apiEndpoints.size.toString(),
                "interactions_count" to interactions.size.toString(),
                "flows" to symbol.flows.joinToString(",")
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
    
    private fun extractSequences(enriched: EnrichedSymbol): List<SequenceInfo> {
        return enriched.flows.map { flowName ->
            SequenceInfo(
                name = flowName,
                steps = emptyList(),
                participants = listOf(enriched.symbol.name),
                description = "Part of $flowName"
            )
        }
    }
    
    private fun extractApiEndpoints(enriched: EnrichedSymbol): List<ApiEndpointInfo> {
        if (enriched.structuralRole != StructuralRole.CONTROLLER) {
            return emptyList()
        }
        
        // Would extract from structured metadata in full implementation
        return emptyList()
    }
    
    private fun extractInteractions(enriched: EnrichedSymbol): List<InteractionInfo> {
        val interactions = mutableListOf<InteractionInfo>()
        
        if (enriched.hasModifier(ModifierKind.EVENT_PUBLISHER)) {
            interactions.add(
                InteractionInfo(
                    type = "EVENT",
                    participants = listOf(enriched.symbol.name),
                    description = "Publishes events"
                )
            )
        }
        
        if (enriched.hasModifier(ModifierKind.EVENT_CONSUMER)) {
            interactions.add(
                InteractionInfo(
                    type = "EVENT",
                    participants = listOf(enriched.symbol.name),
                    description = "Consumes events"
                )
            )
        }
        
        return interactions
    }
    
    private fun generateFlowDescription(context: FlowContext): String {
        val sb = StringBuilder()
        
        if (context.sequences.isNotEmpty()) {
            sb.append("Part of ${context.sequences.first().name} flow")
            if (context.sequences.first().steps.isNotEmpty()) {
                sb.append(" with ${context.sequences.first().steps.size} steps")
            }
        }
        
        if (context.apiEndpoints.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(". ")
            sb.append("Exposes ${context.apiEndpoints.size} API endpoint(s)")
        }
        
        if (context.interactions.isNotEmpty()) {
            val eventInteractions = context.interactions.count { it.type == "EVENT" }
            if (eventInteractions > 0) {
                if (sb.isNotEmpty()) sb.append(". ")
                sb.append("Participates in $eventInteractions event interaction(s)")
            }
        }
        
        return sb.toString().ifEmpty { "Defines execution flow" }
    }
    
    private fun calculateConfidence(enriched: EnrichedSymbol, context: FlowContext): Double {
        var confidence = 0.6
        
        if (enriched.flows.isNotEmpty()) confidence += 0.2
        if (enriched.hasModifier(ModifierKind.EVENT_PUBLISHER) || enriched.hasModifier(ModifierKind.EVENT_CONSUMER)) confidence += 0.1
        if (context.sequences.isNotEmpty()) confidence += 0.1
        
        return minOf(confidence, 0.95)
    }
}

