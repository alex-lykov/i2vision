/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.layer

import com.i2vision.intent.DiscoveryIntent
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.VerbalizationResult

/**
 * Factory for creating and managing layer verbalizers.
 * Provides access to all VSLFC layer verbalizers in a unified way.
 */
object LayerVerbalizerFactory {
    
    /**
     * Get all available layer verbalizers.
     */
    fun getAllVerbalizers(): List<LayerVerbalizer> {
        return listOf(
            VisionVerbalizer(),
            StructureVerbalizer(),
            LogicVerbalizer(),
            FlowVerbalizer(),
            CodeVerbalizer()
        )
    }
    
    /**
     * Get verbalizer for a specific layer.
     */
    fun getVerbalizerForLayer(layer: VSLFCLayer): LayerVerbalizer {
        return when (layer) {
            VSLFCLayer.VISION -> VisionVerbalizer()
            VSLFCLayer.STRUCTURE -> StructureVerbalizer()
            VSLFCLayer.LOGIC -> LogicVerbalizer()
            VSLFCLayer.FLOW -> FlowVerbalizer()
            VSLFCLayer.CODE -> CodeVerbalizer()
        }
    }
    
    /**
     * Get verbalizer by layer name.
     */
    fun getVerbalizerByName(layerName: String): LayerVerbalizer? {
        return getAllVerbalizers().find { it.layerName.equals(layerName, ignoreCase = true) }
    }
}

/**
 * Multi-layer verbalizer that coordinates verbalization across all VSLFC layers.
 * 
 * This class orchestrates the verbalization process by:
 * 1. Collecting context from all layers
 * 2. Invoking appropriate verbalizers for each symbol
 * 3. Aggregating results with cross-layer context
 */
class MultiLayerVerbalizer(
    private val verbalizers: List<LayerVerbalizer> = LayerVerbalizerFactory.getAllVerbalizers()
) {
    
    /**
     * Verbalize symbols across all applicable layers.
     * 
     * @param symbols The symbols to verbalize
     * @param intent The discovery intent for configuration
     * @return List of verbalization results from all applicable layers
     */
    suspend fun verbalize(
        symbols: List<Symbol>,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {
        val results = mutableListOf<VerbalizationResult>()
        val clusterId = (intent.constraints["clusterId"] as? String) ?: "default"
        
        // Build initial context
        val initialContext = LayerVerbalizationContext(clusterId = clusterId)
        
        // Process each symbol through applicable verbalizers
        for (symbol in symbols) {
            val applicableVerbalizers = verbalizers.filter { it.canHandle(symbol) }
            
            for (verbalizer in applicableVerbalizers) {
                try {
                    val result = verbalizer.verbalize(symbol, initialContext, intent)
                    results.add(result)
                } catch (e: Exception) {
                    // Log error but continue with other verbalizers
                    println("Error in ${verbalizer.layerName} verbalizer: ${e.message}")
                }
            }
        }
        
        return results
    }
    
    /**
     * Verbalize symbols for a specific layer only.
     */
    suspend fun verbalizeForLayer(
        symbols: List<Symbol>,
        layer: VSLFCLayer,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {
        val verbalizer = LayerVerbalizerFactory.getVerbalizerForLayer(layer)
        val context = LayerVerbalizationContext(clusterId = (intent.constraints["clusterId"] as? String) ?: "default")
        
        return verbalizer.verbalizeAll(symbols, context, intent)
    }
    
    /**
     * Get verbalization results grouped by layer.
     */
    suspend fun verbalizeGroupedByLayer(
        symbols: List<Symbol>,
        intent: DiscoveryIntent
    ): Map<String, List<VerbalizationResult>> {
        val results = verbalize(symbols, intent)
        return results.groupBy { result ->
            result.metadata["layer"] ?: "UNKNOWN"
        }
    }
}
