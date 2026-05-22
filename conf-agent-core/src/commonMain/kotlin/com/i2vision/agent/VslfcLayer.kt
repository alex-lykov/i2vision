/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent

/**
 * VSLFC layers define the scope and perspective of an agent.
 * 
 * Each layer represents a different level of abstraction in software systems:
 * - **VISION**: Requirements, goals, constraints, business objectives
 * - **STRUCTURE**: Components, modules, architecture, dependencies
 * - **LOGIC**: Business rules, invariants, algorithms, data transformations
 * - **FLOW**: Sequences, API calls, interactions, state transitions
 * - **CODE**: Implementation details, syntax, patterns, best practices
 * 
 * Agents are bound to a specific layer, which influences:
 * - Which tools are available
 * - What context is prioritized
 * - How responses are structured
 * - What contracts are validated
 */
enum class VslfcLayer(
    val displayName: String,
    val description: String
) {
    VISION(
        displayName = "Vision",
        description = "Requirements, goals, constraints"
    ),
    STRUCTURE(
        displayName = "Structure",
        description = "Components, modules, dependencies"
    ),
    LOGIC(
        displayName = "Logic",
        description = "Business rules, invariants"
    ),
    FLOW(
        displayName = "Flow",
        description = "Sequences, API calls, interactions"
    ),
    CODE(
        displayName = "Code",
        description = "Implementation details"
    );
    
    companion object {
        /**
         * Parse a VslfcLayer from a string (case-insensitive).
         * @param s The string to parse
         * @return The matching VslfcLayer
         * @throws IllegalArgumentException if no matching layer is found
         */
        fun fromString(s: String): VslfcLayer =
            entries.find { it.name.equals(s, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown layer: $s")
        
        /**
         * Try to parse a VslfcLayer from a string, returning null on failure.
         * @param s The string to parse
         * @return The matching VslfcLayer, or null if not found
         */
        fun fromStringOrNull(s: String): VslfcLayer? =
            entries.find { it.name.equals(s, ignoreCase = true) }
    }
}
