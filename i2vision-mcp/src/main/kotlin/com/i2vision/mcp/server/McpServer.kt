package com.i2vision.mcp.server

import org.slf4j.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import com.i2vision.mcp.tools.discovery.DiscoveryTools
import com.i2vision.mcp.tools.context.ContextTools
import com.i2vision.mcp.tools.contract.ContractTools
import com.i2vision.mcp.tools.ToolRegistry
import com.i2vision.mcp.tools.intelligence.IntelligenceTools
import com.i2vision.mcp.tools.rollout.RolloutTools

/**
 * MCP Server - Exposes i2vision tools via MCP protocol.
 * 
 * This server provides a Model Context Protocol (MCP) interface to i2vision's
 * discovery and context capabilities, allowing LLMs to access code intelligence
 * through a standardized protocol.
 */
class McpServer(
    private val projectRoot: String
) {
    
    private val log = LoggerFactory.getLogger(McpServer::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private val toolRegistry = ToolRegistry()
    private val jsonRpcHandler = JsonRpcHandler(this)
    
    init {
        registerTools()
    }
    
    /**
     * Register all available tools.
     */
    private fun registerTools() {
        log.info("[MCP] Registering tools")
        
        val discoveryTools = DiscoveryTools(projectRoot)
        val contextTools = ContextTools(projectRoot)
        val contractTools = ContractTools(projectRoot)
        val intelligenceTools = IntelligenceTools(projectRoot)
        val rolloutTools = RolloutTools(projectRoot)
        
        // Register discovery tools
        toolRegistry.register("discover_project", discoveryTools::discoverProject)
        toolRegistry.register("analyze_file", discoveryTools::analyzeFile)
        
        // Register context tools
        toolRegistry.register("get_instant_context", contextTools::getInstantContext)
        toolRegistry.register("prefetch_context", contextTools::prefetchContext)
        
        // Register contract tools
        toolRegistry.register("validate_contract", contractTools::validateContract)
        toolRegistry.register("list_contracts", contractTools::listContracts)
        
        // Register rollout tools
        toolRegistry.register("rollout_structure", rolloutTools::rolloutStructure)
        toolRegistry.register("validate_structure", rolloutTools::validateStructure)
        toolRegistry.register("check_rollout_status", rolloutTools::checkRolloutStatus)
        
        log.info("[MCP] Registered {} tools", toolRegistry.size())
    }
    
    /**
     * Start the MCP server.
     */
    fun start() {
        log.info("[MCP] Starting MCP server for project: {}", projectRoot)
        // TODO: Implement actual MCP protocol server
        log.info("[MCP] MCP server started (mock implementation)")
    }
    
    /**
     * Stop the MCP server.
     */
    fun stop() {
        log.info("[MCP] Stopping MCP server")
        serverJob?.cancel()
        log.info("[MCP] MCP server stopped")
    }
    
    /**
     * Execute a tool call.
     * 
     * @param toolName Name of the tool to execute
     * @param parameters Parameters for the tool
     * @return Tool execution result
     */
    suspend fun executeTool(toolName: String, parameters: Map<String, Any>): ToolResult {
        log.debug("[MCP] Executing tool: {}", toolName)
        
        val tool = toolRegistry.get(toolName)
            ?: return ToolResult.error("Tool not found: $toolName")
        
        return try {
            tool(parameters)
        } catch (e: Exception) {
            log.error("[MCP] Tool execution failed: {}", e.message, e)
            ToolResult.error("Tool execution failed: ${e.message}")
        }
    }
    
    /**
     * List all available tools.
     * 
     * @return List of tool descriptions
     */
    fun listTools(): List<ToolDescription> {
        return toolRegistry.listTools().map { name ->
            ToolDescription(
                name = name,
                description = "Tool: $name",
                parameters = emptyMap()
            )
        }
    }
    
    /**
     * Handle JSON-RPC request
     */
    suspend fun handleJsonRpcRequest(request: String): String {
        return jsonRpcHandler.handleRequest(request)
    }
}

/**
 * Tool execution result.
 */
data class ToolResult(
    val success: Boolean,
    val data: Map<String, Any>? = null,
    val error: String? = null
) {
    companion object {
        fun success(data: Map<String, Any>) = ToolResult(success = true, data = data)
        fun error(message: String) = ToolResult(success = false, error = message)
    }
}

/**
 * Tool description.
 */
data class ToolDescription(
    val name: String,
    val description: String,
    val parameters: Map<String, ParameterSchema>
)

/**
 * Parameter schema.
 */
data class ParameterSchema(
    val type: String,
    val description: String,
    val required: Boolean = false
)
