package com.alyk.ai.koog.context.provider

import com.alyk.ai.koog.context.hierarchy.HierarchyBuilder

/**
 * Serve appropriate hierarchy levels based on request, with optimization for model type.
 */
class ContextProvider(
    private val hierarchyBuilder: HierarchyBuilder
) {
    suspend fun getContext(level: Int, task: String): String {
        // TODO: Implement context retrieval
        return "Context (stub)"
    }
}
