package com.i2vision.intent

/**
 * DiscoveryIntent - High-level user intent for discovery operations.
 * 
 * Represents what the user wants to achieve with discovery, abstracting
 * away low-level parameter configuration. Intents are resolved to
 * DiscoveryStrategy via IntentResolutionEngine.
 * 
 * Separation of Concerns:
 * - DiscoveryIntent: User-facing intent specification
 * - IntentResolutionEngine: Converts intents to DiscoveryStrategy
 * - DiscoveryStrategy: Low-level parameter configuration
 */
data class DiscoveryIntent(
    val goal: IntentGoal,
    val focus: Set<LayerFocus> = LayerFocus.ALL,
    val depth: IntentDepth = IntentDepth.STANDARD,
    val quality: QualityFocus = QualityFocus.BALANCED,
    val constraints: Map<String, Any> = emptyMap()
) {
    /**
     * Validate the intent configuration.
     * Returns list of validation errors (empty if valid).
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        // Validate goal and focus compatibility
        if (goal == IntentGoal.FLOW_MAPPING && !focus.contains(LayerFocus.FLOW)) {
            errors.add("FLOW_MAPPING goal requires FOCUS layer to be included")
        }
        
        if (goal == IntentGoal.DOCUMENTATION_GENERATION && 
            !focus.contains(LayerFocus.STRUCTURE) && 
            !focus.contains(LayerFocus.LOGIC)) {
            errors.add("DOCUMENTATION_GENERATION goal requires STRUCTURE or LOGIC focus")
        }
        
        // Validate depth constraints
        if (depth == IntentDepth.BROWSE && quality == QualityFocus.QUALITY) {
            errors.add("BROWSE depth cannot be combined with QUALITY focus")
        }
        
        return errors
    }
    
    /**
     * Check if this intent is valid.
     */
    fun isValid(): Boolean = validate().isEmpty()
}

/**
 * High-level goals for discovery operations.
 */
enum class IntentGoal {
    /** Complete discovery of all layers */
    FULL_DISCOVERY,
    
    /** Focus on refactoring opportunities */
    REFACTORING_ANALYSIS,
    
    /** Audit architecture and dependencies */
    ARCHITECTURE_AUDIT,
    
    /** Map flows and interactions */
    FLOW_MAPPING,
    
    /** Generate documentation */
    DOCUMENTATION_GENERATION,
    
    /** Quick overview for exploration */
    QUICK_OVERVIEW
}

/**
 * Layers to focus discovery on.
 */
enum class LayerFocus {
    VISION,
    STRUCTURE,
    LOGIC,
    FLOW,
    CODE;
    
    companion object {
        val ALL = values().toSet()
        val CORE = setOf(STRUCTURE, LOGIC, FLOW)
        val ANALYSIS = setOf(LOGIC, FLOW)
    }
}

/**
 * Depth of discovery analysis.
 */
enum class IntentDepth {
    /** Shallow scan for quick overview */
    BROWSE,
    
    /** Standard depth for most use cases */
    STANDARD,
    
    /** Deep analysis with LLM enhancement */
    DEEP
}

/**
 * Quality vs quantity trade-off.
 */
enum class QualityFocus {
    /** Prioritize quality over quantity */
    QUALITY,
    
    /** Balanced approach */
    BALANCED,
    
    /** Prioritize quantity (maximum discovery) */
    QUANTITY
}

/**
 * Parse intent from CLI arguments.
 */
object IntentParser {
    /**
     * Parse intent from command-line arguments.
     * 
     * Supported arguments:
     * --intent=<goal>
     * --focus=<layers> (comma-separated: vision,structure,logic,flow,code)
     * --depth=<depth> (browse, standard, deep)
     * --quality=<quality> (quality, balanced, quantity)
     */
    fun parse(args: Map<String, String>): DiscoveryIntent? {
        val goalStr = args["intent"] ?: return null
        val goal = try {
            IntentGoal.valueOf(goalStr.uppercase())
        } catch (e: IllegalArgumentException) {
            return null
        }
        
        val focusStr = args["focus"]
        val focus = if (focusStr != null) {
            focusStr.split(",").mapNotNull { layer ->
                try {
                    LayerFocus.valueOf(layer.trim().uppercase())
                } catch (e: IllegalArgumentException) {
                    null
                }
            }.toSet()
        } else {
            LayerFocus.ALL
        }
        
        val depthStr = args["depth"]
        val depth = if (depthStr != null) {
            try {
                IntentDepth.valueOf(depthStr.uppercase())
            } catch (e: IllegalArgumentException) {
                IntentDepth.STANDARD
            }
        } else {
            IntentDepth.STANDARD
        }
        
        val qualityStr = args["quality"]
        val quality = if (qualityStr != null) {
            try {
                QualityFocus.valueOf(qualityStr.uppercase())
            } catch (e: IllegalArgumentException) {
                QualityFocus.BALANCED
            }
        } else {
            QualityFocus.BALANCED
        }
        
        // Parse additional constraints
        val constraints = args.filterKeys { 
            it !in listOf("intent", "focus", "depth", "quality") 
        }
        
        return DiscoveryIntent(
            goal = goal,
            focus = focus,
            depth = depth,
            quality = quality,
            constraints = constraints
        )
    }
}
