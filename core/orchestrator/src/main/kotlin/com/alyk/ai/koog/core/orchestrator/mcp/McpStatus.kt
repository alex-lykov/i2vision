package com.alyk.ai.koog.core.orchestrator.mcp

/**
 * Status of MCP integration
 */
sealed class McpStatus {
    /**
     * MCP is not connected to any project
     */
    object NOT_CONNECTED : McpStatus()
    
    /**
     * MCP is connected to a specific project
     */
    data class CONNECTED(val projectPath: String) : McpStatus()
    
    /**
     * MCP connection failed
     */
    data class ERROR(val message: String) : McpStatus()
}
