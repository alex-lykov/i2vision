/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization

import com.i2vision.arch.signature.StructuralRole
import com.i2vision.vslfc.Symbol

/**
 * Shared types for verbalization context and information classes.
 * These are used across multiple verbalizer implementations.
 */

// ============ Structure Types ============

data class ComponentInfo(
    val name: String,
    val type: String,
    val role: StructuralRole?,
    val modifiers: List<String>,
    val description: String
)

data class DependencyInfo(
    val from: String,
    val to: String,
    val type: String,
    val strength: DependencyStrength
)

enum class DependencyStrength {
    STRONG,
    MODERATE,
    WEAK
}

data class StructureContext(
    val components: List<ComponentInfo> = emptyList(),
    val dependencies: List<DependencyInfo> = emptyList(),
    val patterns: List<String> = emptyList(),
    val layers: List<String> = emptyList()
)

// ============ Logic Types ============

data class InvariantInfo(
    val name: String,
    val condition: String,
    val severity: InvariantSeverity,
    val description: String
)

enum class InvariantSeverity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}

data class RuleInfo(
    val name: String,
    val condition: String,
    val action: String,
    val priority: Int,
    val description: String
)

data class StateMachineInfo(
    val states: List<String>,
    val transitions: List<String>,
    val initialState: String,
    val finalStates: List<String>
)

data class LogicContext(
    val invariants: List<InvariantInfo> = emptyList(),
    val rules: List<RuleInfo> = emptyList(),
    val stateMachine: StateMachineInfo? = null,
    val algorithms: List<String> = emptyList()
)

// ============ Flow Types ============

data class SequenceInfo(
    val name: String,
    val steps: List<SequenceStep>,
    val participants: List<String>,
    val description: String
)

data class SequenceStep(
    val order: Int,
    val from: String,
    val to: String,
    val action: String,
    val type: StepType
)

enum class StepType {
    SYNCHRONOUS,
    ASYNCHRONOUS
}

data class ApiEndpointInfo(
    val method: String,
    val path: String,
    val handler: String,
    val description: String
)

data class InteractionInfo(
    val type: String,
    val participants: List<String>,
    val description: String
)

data class FlowContext(
    val sequences: List<SequenceInfo> = emptyList(),
    val apiEndpoints: List<ApiEndpointInfo> = emptyList(),
    val interactions: List<InteractionInfo> = emptyList()
)

// ============ Vision Types ============

data class VisionContext(
    val architectureStyle: String,
    val qualityAttributes: List<String>,
    val technicalDecisions: List<String>,
    val tradeoffs: List<String>,
    val structureContext: StructureContext = StructureContext(),
    val logicContext: LogicContext = LogicContext(),
    val flowContext: FlowContext = FlowContext()
)

// ============ Code Types ============

data class CodeContext(
    val symbol: Symbol,
    val signature: String,
    val documentation: String?,
    val implementations: List<String>
)
