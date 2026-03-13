package com.alyk.ai.koog.core.session

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Enhanced ToolRegistry with dynamic tool management capabilities
 * Supports adding/removing tools during runtime as described in Koog session management
 * Note: Since ToolRegistry is final, we use composition instead of inheritance
 */
class DynamicToolRegistry(
    initialTools: List<Tool<*, *>> = emptyList()
) {
    private val internalTools = ConcurrentHashMap<String, Tool<*, *>>()
    
    init {
        initialTools.forEach { tool ->
            internalTools[tool.name] = tool
        }
    }
    
    /**
     * Get all tools as a list for compatibility with ToolRegistry interface
     */
    fun getTools(): List<Tool<*, *>> {
        return internalTools.values.toList()
    }
    
    /**
     * Add a tool to the registry
     */
    fun appendTool(tool: Tool<*, *>) {
        internalTools[tool.name] = tool
    }
    
    /**
     * Remove a tool by name
     */
    fun removeTool(toolName: String): Tool<*, *>? {
        return internalTools.remove(toolName)
    }
    
    /**
     * Remove tools by class type
     */
    fun removeTool(toolClass: Class<out Tool<*, *>>) {
        internalTools.values.removeIf { toolClass.isInstance(it) }
    }
    
    /**
     * Check if a tool exists
     */
    fun hasTool(toolName: String): Boolean {
        return internalTools.containsKey(toolName)
    }
    
    /**
     * Get a specific tool by name
     */
    fun getTool(toolName: String): Tool<*, *>? {
        return internalTools[toolName]
    }
    
    /**
     * Get tools by category (if tool supports categorization)
     */
    fun getToolsByCategory(category: String): List<Tool<*, *>> {
        return internalTools.values.filter { tool ->
            // Check if tool has category property or method
            try {
                val categoryField = tool.javaClass.getDeclaredField("category")
                categoryField.isAccessible = true
                categoryField.get(tool) == category
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * Clear all tools
     */
    fun clear() {
        internalTools.clear()
    }
    
    /**
     * Get current tool count
     */
    fun getToolCount(): Int = internalTools.size
    
    /**
     * Create a snapshot of current tools
     */
    fun createSnapshot(): Map<String, Tool<*, *>> {
        return internalTools.toMap()
    }
    
    /**
     * Restore from a snapshot
     */
    fun restoreFromSnapshot(snapshot: Map<String, Tool<*, *>>) {
        internalTools.clear()
        internalTools.putAll(snapshot)
    }
    
    /**
     * Combine with another ToolRegistry
     */
    operator fun plus(other: ToolRegistry): DynamicToolRegistry {
        val combined = DynamicToolRegistry(this.getTools())
        other.tools.forEach { tool ->
            internalTools[tool.name] = tool
        }
        return combined
    }
    
    /**
     * Get tool names
     */
    fun getToolNames(): Set<String> {
        return internalTools.keys
    }
    
    /**
     * Check if registry is empty
     */
    fun isEmpty(): Boolean = internalTools.isEmpty()
    
    /**
     * Get registry size
     */
    fun size(): Int = internalTools.size
}
