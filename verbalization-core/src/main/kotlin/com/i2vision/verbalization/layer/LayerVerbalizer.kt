/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.layer

import com.i2vision.arch.signature.EnrichedSymbol
import com.i2vision.intent.DiscoveryIntent
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.VerbalizationResult
import com.i2vision.verbalization.*

/**
 * Base interface for all layer verbalizers.
 * Each layer verbalizer transforms code artifacts into natural language descriptions
 * specific to that VSLFC layer.
 */
interface LayerVerbalizer {
    
    /**
     * The layer this verbalizer handles.
     */
    val layerName: String
    
    /**
     * Verbalize a single symbol for this layer.
     *
     * @param symbol The symbol to verbalize
     * @param context Additional context from other layers
     * @param intent The discovery intent for configuration
     * @return Verbalization result for this layer
     */
    suspend fun verbalize(
        symbol: EnrichedSymbol,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): VerbalizationResult
    
    /**
     * Verbalize multiple symbols for this layer.
     *
     * @param symbols The symbols to verbalize
     * @param context Additional context from other layers
     * @param intent The discovery intent for configuration
     * @return List of verbalization results
     */
    suspend fun verbalizeAll(
        symbols: List<EnrichedSymbol>,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): List<VerbalizationResult>
    
    /**
     * Check if this verbalizer can handle the given symbol.
     *
     * @param symbol The symbol to check
     * @return true if this verbalizer can handle the symbol
     */
    fun canHandle(symbol: EnrichedSymbol): Boolean
}

/**
 * Context passed between layer verbalizers.
 * Contains information from previous/other layers for cross-layer understanding.
 */
data class LayerVerbalizationContext(
    val visionContext: VisionContext? = null,
    val structureContext: StructureContext? = null,
    val logicContext: LogicContext? = null,
    val flowContext: FlowContext? = null,
    val codeContext: CodeContext? = null,
    val clusterId: String = ""
)

