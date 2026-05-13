/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.layer

/**
 * VSLFC Layer enumeration defining the five documentation layers.
 * 
 * Each layer represents a different level of abstraction in code documentation:
 * - VISION: High-level purpose and requirements from README/docs
 * - STRUCTURE: Module dependencies and component architecture
 * - LOGIC: Business rules, invariants, and state machines
 * - FLOW: Call graphs and sequence descriptions
 * - CODE: Detailed symbol-level documentation
 */
enum class VSLFCLayer(val displayName: String, val description: String) {
    /**
     * VISION layer - README/docs → purpose statement
     * Captures high-level purpose, requirements, and constraints.
     */
    VISION("Vision", "High-level purpose and requirements from documentation"),
    
    /**
     * STRUCTURE layer - Module dependencies → component graph
     * Describes architecture, components, and their relationships.
     */
    STRUCTURE("Structure", "Module dependencies and component architecture"),
    
    /**
     * LOGIC layer - Invariants/rules → decision tables
     * Documents business rules, invariants, and state machines.
     */
    LOGIC("Logic", "Business rules, invariants, and decision logic"),
    
    /**
     * FLOW layer - Call graphs → sequence descriptions
     * Describes sequences, API endpoints, and interactions.
     */
    FLOW("Flow", "Call graphs, sequences, and interaction patterns"),
    
    /**
     * CODE layer - Symbol documentation → API reference
     * Low-level symbol documentation and inline comments.
     */
    CODE("Code", "Symbol-level documentation and API reference");

    companion object {
        /**
         * Get layer by name (case-insensitive).
         */
        fun fromName(name: String): VSLFCLayer? {
            return entries.find { 
                it.name.equals(name, ignoreCase = true) || 
                it.displayName.equals(name, ignoreCase = true)
            }
        }

        /**
         * All layer names as strings.
         */
        fun names(): List<String> = entries.map { it.name }

        /**
         * Check if a string represents a valid layer.
         */
        fun isValid(name: String): Boolean = fromName(name) != null
    }
}