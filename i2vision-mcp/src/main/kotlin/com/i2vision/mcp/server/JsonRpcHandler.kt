/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.mcp.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory

/**
 * JSON-RPC 2.0 Handler
 * Implements full JSON-RPC 2.0 specification for MCP protocol
 */
class JsonRpcHandler(
    private val mcpServer: McpServer
) {

    private val log = LoggerFactory.getLogger(JsonRpcHandler::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    /**
     * Handle incoming JSON-RPC request
     */
    suspend fun handleRequest(request: String): String {
        return try {
            val jsonRpcRequest = objectMapper.readValue(request, JsonRpcRequest::class.java)
            val response = processRequest(jsonRpcRequest)
            objectMapper.writeValueAsString(response)
        } catch (e: Exception) {
            log.error("[JSON-RPC] Request handling failed: {}", e.message, e)
            val errorResponse = JsonRpcResponse.error(
                id = null,
                code = -32700,
                message = "Parse error",
                data = mapOf("details" to (e.message ?: "Unknown error"))
            )
            objectMapper.writeValueAsString(errorResponse)
        }
    }

    /**
     * Process JSON-RPC request
     */
    private suspend fun processRequest(request: JsonRpcRequest): JsonRpcResponse {
        log.debug("[JSON-RPC] Processing request: method={}, id={}", request.method, request.id)

        // Validate request
        if (request.jsonrpc != "2.0") {
            return JsonRpcResponse.error(
                id = request.id,
                code = -32600,
                message = "Invalid Request",
                data = mapOf("reason" to "jsonrpc version must be 2.0")
            )
        }

        // Handle notifications (requests without id)
        if (request.id == null) {
            return processNotification(request)
        }

        // Handle method calls
        return when (request.method) {
            "tools/list" -> handleToolsList(request)
            "tools/call" -> handleToolCall(request)
            "initialize" -> handleInitialize(request)
            "shutdown" -> handleShutdown(request)
            else -> handleToolMethod(request)
        }
    }

    /**
     * Process notification (request without id - no response expected)
     */
    private suspend fun processNotification(request: JsonRpcRequest): JsonRpcResponse {
        log.debug("[JSON-RPC] Processing notification: method={}", request.method)

        when (request.method) {
            "notifications/initialized" -> {
                log.info("[JSON-RPC] Client initialized")
                // No response for notifications
                return JsonRpcResponse.empty()
            }

            else -> {
                log.warn("[JSON-RPC] Unknown notification method: {}", request.method)
                return JsonRpcResponse.empty()
            }
        }
    }

    /**
     * Handle tools/list method
     */
    private fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
        log.debug("[JSON-RPC] Handling tools/list")

        val tools = mcpServer.listTools()
        val result: Map<String, Any> = mapOf(
            "tools" to tools.map { tool ->
                val properties: Map<String, Map<String, String>> = tool.parameters.mapValues { (_, param) ->
                    mapOf(
                        "type" to param.type,
                        "description" to param.description
                    )
                }
                mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "inputSchema" to mapOf(
                        "type" to "object",
                        "properties" to properties,
                        "required" to tool.parameters.filter { it.value.required }.keys
                    )
                )
            }
        )

        return JsonRpcResponse.success(request.id, result)
    }

    /**
     * Handle tools/call method
     */
    private suspend fun handleToolCall(request: JsonRpcRequest): JsonRpcResponse {
        log.debug("[JSON-RPC] Handling tools/call")

        val params = request.params as? Map<*, *>
        if (params == null) {
            return JsonRpcResponse.error(
                id = request.id,
                code = -32602,
                message = "Invalid params",
                data = mapOf("reason" to "params must be an object")
            )
        }

        val toolName = params["name"] as? String
        if (toolName == null) {
            return JsonRpcResponse.error(
                id = request.id,
                code = -32602,
                message = "Invalid params",
                data = mapOf("reason" to "tool name is required")
            )
        }

        val arguments = params["arguments"] as? Map<*, *> ?: emptyMap<String, Any>()
        val typedArguments: Map<String, Any> = arguments.mapKeys { it.key as String }.mapValues { it.value as Any }

        return try {
            val result = mcpServer.executeTool(toolName, typedArguments)
            if (result.success) {
                val successResult: Map<String, Any> = mapOf(
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to objectMapper.writeValueAsString(result.data)
                        )
                    ),
                    "isError" to false
                )
                JsonRpcResponse.success(request.id, successResult)
            } else {
                val errorContent: List<Map<String, String>> = listOf(
                    mapOf(
                        "type" to "text",
                        "text" to (result.error ?: "Unknown error")
                    )
                )
                val errorResult: Map<String, Any> = mapOf(
                    "content" to errorContent,
                    "isError" to true
                )
                JsonRpcResponse.success(request.id, errorResult)
            }
        } catch (e: Exception) {
            log.error("[JSON-RPC] Tool call failed: {}", e.message, e)
            JsonRpcResponse.error(
                id = request.id,
                code = -32603,
                message = "Internal error",
                data = mapOf("details" to (e.message ?: "Unknown error"))
            )
        }
    }

    /**
     * Handle initialize method
     */
    private fun handleInitialize(request: JsonRpcRequest): JsonRpcResponse {
        log.debug("[JSON-RPC] Handling initialize")

        val result = mapOf(
            "protocolVersion" to "2024-11-05",
            "serverInfo" to mapOf(
                "name" to "i2vision-mcp",
                "version" to "1.0.0"
            ),
            "capabilities" to mapOf(
                "tools" to mapOf(
                    "listChanged" to true
                ),
                "resources" to mapOf(
                    "subscribe" to true,
                    "listChanged" to true
                )
            )
        )

        return JsonRpcResponse.success(request.id, result)
    }

    /**
     * Handle shutdown method
     */
    private fun handleShutdown(request: JsonRpcRequest): JsonRpcResponse {
        log.debug("[JSON-RPC] Handling shutdown")
        mcpServer.stop()
        return JsonRpcResponse.success(request.id, null)
    }

    /**
     * Handle direct tool method calls (for backward compatibility)
     */
    private suspend fun handleToolMethod(request: JsonRpcRequest): JsonRpcResponse {
        log.debug("[JSON-RPC] Handling direct tool method: {}", request.method)

        val params = request.params as? Map<*, *> ?: emptyMap<String, Any>()
        val typedParams: Map<String, Any> = params.mapKeys { it.key as String }.mapValues { it.value as Any }

        return try {
            val result = mcpServer.executeTool(request.method, typedParams)
            if (result.success) {
                JsonRpcResponse.success(request.id, result.data)
            } else {
                val errorData: Map<String, Any> = mapOf("details" to (result.error ?: "Unknown error"))
                JsonRpcResponse.error(
                    id = request.id,
                    code = -32603,
                    message = "Tool execution failed",
                    data = errorData
                )
            }
        } catch (e: Exception) {
            log.error("[JSON-RPC] Tool method failed: {}", e.message, e)
            JsonRpcResponse.error(
                id = request.id,
                code = -32601,
                message = "Method not found",
                data = mapOf("method" to request.method)
            )
        }
    }
}

/**
 * JSON-RPC 2.0 Request
 */
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: Any? = null,
    val id: Any? = null
)

/**
 * JSON-RPC 2.0 Response
 */
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val result: Any? = null,
    val error: JsonRpcError? = null,
    val id: Any?
) {
    companion object {
        fun success(id: Any?, result: Any?) = JsonRpcResponse(
            jsonrpc = "2.0",
            result = result,
            error = null,
            id = id
        )

        fun error(id: Any?, code: Int, message: String, data: Any? = null) = JsonRpcResponse(
            jsonrpc = "2.0",
            result = null,
            error = JsonRpcError(code, message, data),
            id = id
        )

        fun empty() = JsonRpcResponse(
            jsonrpc = "2.0",
            result = null,
            error = null,
            id = null
        )
    }
}

/**
 * JSON-RPC 2.0 Error
 */
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
)
