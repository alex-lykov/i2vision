/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.tools

/**
 * Discovery intent for i2vision-discover.
 * 
 * Specifies the goal and depth of discovery analysis.
 * 
 * @property intent The discovery intent type
 * @property depth How deep the analysis should go
 */
data class DiscoveryIntent(
    val intent: Intent = Intent.QUICK_OVERVIEW,
    val depth: Depth = Depth.STANDARD
) {
    companion object {
        /**
         * Parse DiscoveryIntent from string.
         */
        fun fromString(value: String): DiscoveryIntent = when (value.lowercase()) {
            "full_discovery" -> DiscoveryIntent(Intent.FULL_DISCOVERY, Depth.DEEP)
            "refactoring_analysis" -> DiscoveryIntent(Intent.REFACTORING_ANALYSIS, Depth.DEEP)
            "quick_overview" -> DiscoveryIntent(Intent.QUICK_OVERVIEW, Depth.BROWSE)
            "architecture_audit" -> DiscoveryIntent(Intent.ARCHITECTURE_AUDIT, Depth.STANDARD)
            "flow_mapping" -> DiscoveryIntent(Intent.FLOW_MAPPING, Depth.STANDARD)
            "documentation_generation" -> DiscoveryIntent(Intent.DOCUMENTATION_GENERATION, Depth.STANDARD)
            else -> DiscoveryIntent(Intent.QUICK_OVERVIEW, Depth.STANDARD)
        }
    }
    
    /**
     * Discovery intent types.
     */
    enum class Intent {
        /** Full comprehensive discovery */
        FULL_DISCOVERY,
        
        /** Analysis for refactoring purposes */
        REFACTORING_ANALYSIS,
        
        /** Quick overview of the codebase */
        QUICK_OVERVIEW,
        
        /** Architecture pattern audit */
        ARCHITECTURE_AUDIT,
        
        /** Map data and control flows */
        FLOW_MAPPING,
        
        /** Generate documentation */
        DOCUMENTATION_GENERATION
    }
    
    /**
     * Discovery depth levels.
     */
    enum class Depth {
        /** Quick browse, minimal analysis */
        BROWSE,
        
        /** Standard analysis depth */
        STANDARD,
        
        /** Deep comprehensive analysis */
        DEEP
    }
}
