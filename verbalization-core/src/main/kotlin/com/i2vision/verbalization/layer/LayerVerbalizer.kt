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
        symbol: Symbol,
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
        symbols: List<Symbol>,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): List<VerbalizationResult>
    
    /**
     * Check if this verbalizer can handle the given symbol.
     *
     * @param symbol The symbol to check
     * @return true if this verbalizer can handle the symbol
     */
    fun canHandle(symbol: Symbol): Boolean
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

/**
 * Vision layer context - purpose and requirements.
 */
data class VisionContext(
    val purposeStatement: String,
    val requirements: List<String>,
    val constraints: List<String>,
    val stakeholders: List<String> = emptyList()
)

/**
 * Structure layer context - module dependencies and components.
 */
data class StructureContext(
    val components: List<ComponentInfo>,
    val dependencies: List<DependencyInfo>,
    val architecturePattern: String,
    val modules: List<String>
)

/**
 * Component information for structure verbalization.
 */
data class ComponentInfo(
    val name: String,
    val type: String,
    val responsibilities: List<String>,
    val filePath: String
)

/**
 * Dependency information for structure verbalization.
 */
data class DependencyInfo(
    val from: String,
    val to: String,
    val type: String,
    val strength: DependencyStrength
)

enum class DependencyStrength {
    STRONG, WEAK, TRANSITIVE
}

/**
 * Logic layer context - invariants and business rules.
 */
data class LogicContext(
    val invariants: List<InvariantInfo>,
    val rules: List<RuleInfo>,
    val stateMachines: List<StateMachineInfo>
)

/**
 * Invariant information for logic verbalization.
 */
data class InvariantInfo(
    val condition: String,
    val description: String,
    val severity: InvariantSeverity
)

enum class InvariantSeverity {
    MANDATORY, RECOMMENDED, OPTIONAL
}

/**
 * Rule information for logic verbalization.
 */
data class RuleInfo(
    val name: String,
    val condition: String,
    val action: String,
    val priority: Int
)

/**
 * State machine information for logic verbalization.
 */
data class StateMachineInfo(
    val name: String,
    val states: List<String>,
    val transitions: List<TransitionInfo>
)

data class TransitionInfo(
    val from: String,
    val to: String,
    val trigger: String,
    val guard: String?
)

/**
 * Flow layer context - call graphs and sequences.
 */
data class FlowContext(
    val sequences: List<SequenceInfo>,
    val apiEndpoints: List<ApiEndpointInfo>,
    val interactions: List<InteractionInfo>
)

/**
 * Sequence information for flow verbalization.
 */
data class SequenceInfo(
    val name: String,
    val steps: List<SequenceStep>,
    val participants: List<String>,
    val description: String
)

/**
 * Step in a sequence diagram.
 */
data class SequenceStep(
    val order: Int,
    val from: String,
    val to: String,
    val action: String,
    val type: StepType
)

enum class StepType {
    SYNCHRONOUS, ASYNCHRONOUS, RETURN, CREATE, DELETE
}

/**
 * API endpoint information for flow verbalization.
 */
data class ApiEndpointInfo(
    val method: String,
    val path: String,
    val handler: String,
    val description: String
)

/**
 * Interaction information for flow verbalization.
 */
data class InteractionInfo(
    val type: String,
    val participants: List<String>,
    val description: String
)

/**
 * Code layer context - symbol details.
 */
data class CodeContext(
    val symbol: Symbol,
    val signature: String,
    val documentation: String?,
    val implementations: List<String>
)