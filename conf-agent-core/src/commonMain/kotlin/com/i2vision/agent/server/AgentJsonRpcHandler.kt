/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.server

import com.i2vision.agent.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * Handles JSON-RPC requests and routes them to appropriate methods.
 * 
 * Supported methods:
 * - `initialize` - Initialize a new session
 * - `process` - Execute agent task (blocking)
 * - `processStreaming` - Execute with streaming (notifications)
 * - `cancel` - Cancel running request
 * - `ping` - Health check
 * - `listConfigurations` - List available agent configs
 * - `getConfiguration` - Get specific agent config
 * - `updateConfiguration` - Update agent config
 * - `shutdown` - Shutdown session
 * 
 * @param sessionManager Session manager instance
 * @param json JSON serializer
 */
class AgentJsonRpcHandler(
    private val sessionManager: SessionManager,
    private val json: Json
) {
    /**
     * Callback for sending notifications (streaming events).
     */
    var onNotification: ((JsonRpcNotification) -> Unit)? = null
    
    /**
     * Scope for handling requests.
     */
    private val handlerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * Handle a JSON-RPC request.
     * 
     * @param request The JSON-RPC request
     * @return The JSON-RPC response
     */
    suspend fun handleRequest(request: JsonRpcRequest): JsonRpcResponse {
        try {
            val params = request.params?.let { json.decodeFromString<JsonObject>(it.toString()) }
            
            val result = when (request.method) {
                "initialize" -> handleInitialize(params, request.id)
                "process" -> handleProcess(params, request.id)
                "processStreaming" -> handleProcessStreaming(params, request.id)
                "cancel" -> handleCancel(params, request.id)
                "ping" -> handlePing(request.id)
                "listConfigurations" -> handleListConfigurations(params, request.id)
                "getConfiguration" -> handleGetConfiguration(params, request.id)
                "updateConfiguration" -> handleUpdateConfiguration(params, request.id)
                "shutdown" -> handleShutdown(params, request.id)
                else -> JsonRpcErrorResponse(
                    jsonrpc = "2.0",
                    error = JsonRpcError(code = JsonRpcError.METHOD_NOT_FOUND, message = "Method not found: ${request.method}"),
                    id = request.id
                )
            }
            
            return result
        } catch (e: Exception) {
            println("Error handling request: ${e.message}")
            return JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INTERNAL_ERROR, message = e.message ?: "Internal error"),
                id = request.id
            )
        }
    }
    
    /**
     * Handle initialize method.
     */
    private suspend fun handleInitialize(params: JsonObject?, id: Long?): JsonRpcResponse {
        val sessionId = params?.get("sessionId")?.jsonPrimitive?.contentOrNull
        val configId = params?.get("configId")?.jsonPrimitive?.contentOrNull ?: "default"
        val layerName = params?.get("layer")?.jsonPrimitive?.contentOrNull ?: "koog"
        
        val session = sessionManager.createSession(sessionId, configId, layerName)
        
        val result = buildJsonObject {
            put("sessionId", session.id)
            put("displayName", session.displayName)
            put("layer", session.layer.name)
        }
        
        return JsonRpcSuccessResponse(
            jsonrpc = "2.0",
            result = result,
            id = id
        )
    }
    
    /**
     * Handle process method (blocking).
     */
    private suspend fun handleProcess(params: JsonObject?, id: Long?): JsonRpcResponse {
        val sessionId = params?.get("sessionId")?.jsonPrimitive?.content
            ?: return JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INVALID_PARAMS, message = "sessionId is required"),
                id = id
            )
        
        val task = params?.get("task")?.jsonPrimitive?.content
            ?: return JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INVALID_PARAMS, message = "task is required"),
                id = id
            )
        
        val session = sessionManager.getSession(sessionId)
            ?: return JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INVALID_PARAMS, message = "Session not found: $sessionId"),
                id = id
            )
        
        val response = session.process(task)
        
        val result = buildJsonObject {
            put("answer", response.answer)
            if (response.usage != null) {
                put("usage", buildJsonObject {
                    put("promptTokens", response.usage.promptTokens)
                    put("completionTokens", response.usage.completionTokens)
                    put("totalTokens", response.usage.totalTokens)
                })
            }
        }
        
        return JsonRpcSuccessResponse(
            jsonrpc = "2.0",
            result = result,
            id = id
        )
    }
    
    /**
     * Handle processStreaming method.
     */
    private suspend fun handleProcessStreaming(params: JsonObject?, id: Long?): JsonRpcResponse {
        val sessionId = params?.get("sessionId")?.jsonPrimitive?.content
            ?: return JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INVALID_PARAMS, message = "sessionId is required"),
                id = id
            )
        
        val task = params?.get("task")?.jsonPrimitive?.content
            ?: return JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INVALID_PARAMS, message = "task is required"),
                id = id
            )
        
        val session = sessionManager.getSession(sessionId)
            ?: return JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INVALID_PARAMS, message = "Session not found: $sessionId"),
                id = id
            )
        
        // Start streaming in background
        handlerScope.launch {
            try {
                session.processStreaming(task) { chunk ->
                    val notification = JsonRpcNotification(
                        jsonrpc = "2.0",
                        method = "streamChunk",
                        params = buildJsonObject {
                            put("sessionId", sessionId)
                            put("chunk", chunk.toJson())
                        }
                    )
                    onNotification?.invoke(notification)
                }
                
                // Send completion notification
                val completionNotification = JsonRpcNotification(
                    jsonrpc = "2.0",
                    method = "streamComplete",
                    params = buildJsonObject {
                        put("sessionId", sessionId)
                    }
                )
                onNotification?.invoke(completionNotification)
            } catch (e: Exception) {
                val errorNotification = JsonRpcNotification(
                    jsonrpc = "2.0",
                    method = "streamError",
                    params = buildJsonObject {
                        put("sessionId", sessionId)
                        put("error", e.message ?: "Unknown error")
                    }
                )
                onNotification?.invoke(errorNotification)
            }
        }
        
        // Return immediately with acknowledgment
        val result = buildJsonObject {
            put("status", "started")
            put("sessionId", sessionId)
        }
        
        return JsonRpcSuccessResponse(
            jsonrpc = "2.0",
            result = result,
            id = id
        )
    }
    
    /**
     * Handle cancel method.
     */
    private suspend fun handleCancel(params: JsonObject?, id: Long?): JsonRpcResponse {
        val sessionId = params?.get("sessionId")?.jsonPrimitive?.content
            ?: return JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INVALID_PARAMS, message = "sessionId is required"),
                id = id
            )
        
        val session = sessionManager.getSession(sessionId)
            ?: return JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INVALID_PARAMS, message = "Session not found: $sessionId"),
                id = id
            )
        
        session.cancel()
        
        val result = buildJsonObject {
            put("status", "cancelled")
            put("sessionId", sessionId)
        }
        
        return JsonRpcSuccessResponse(
            jsonrpc = "2.0",
            result = result,
            id = id
        )
    }
    
    /**
     * Handle ping method.
     */
    private suspend fun handlePing(id: Long?): JsonRpcResponse {
        val result = buildJsonObject {
            put("status", "ok")
            put("timestamp", System.currentTimeMillis())
        }
        
        return JsonRpcSuccessResponse(
            jsonrpc = "2.0",
            result = result,
            id = id
        )
    }
    
    /**
     * Handle listConfigurations method.
     */
    private suspend fun handleListConfigurations(params: JsonObject?, id: Long?): JsonRpcResponse {
        val configs = sessionManager.listConfigurations()
        
        val result = JsonArray(configs.map { config ->
            buildJsonObject {
                put("id", config.id)
                put("displayName", config.displayName)
                put("description", config.description ?: "")
                put("layer", config.layer.name)
            }
        })
        
        return JsonRpcSuccessResponse(
            jsonrpc = "2.0",
            result = result,
            id = id
        )
    }
    
    /**
     * Handle getConfiguration method.
     */
    private suspend fun handleGetConfiguration(params: JsonObject?, id: Long?): JsonRpcResponse {
        val configId = params?.get("configId")?.jsonPrimitive?.content
            ?: return JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INVALID_PARAMS, message = "configId is required"),
                id = id
            )
        
        val config = sessionManager.getConfiguration(configId)
            ?: return JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INVALID_PARAMS, message = "Configuration not found: $configId"),
                id = id
            )
        
        val result = agentConfigToJson(config)
        
        return JsonRpcSuccessResponse(
            jsonrpc = "2.0",
            result = result,
            id = id
        )
    }
    
    /**
     * Handle updateConfiguration method.
     */
    private suspend fun handleUpdateConfiguration(params: JsonObject?, id: Long?): JsonRpcResponse {
        val configId = params?.get("configId")?.jsonPrimitive?.content
            ?: return JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INVALID_PARAMS, message = "configId is required"),
                id = id
            )
        
        val configJson = params?.get("configuration")
            ?: return JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INVALID_PARAMS, message = "configuration is required"),
                id = id
            )
        
        val config = jsonConfigToAgentConfig(configId, configJson)
        sessionManager.updateConfiguration(config)
        
        val result = buildJsonObject {
            put("status", "updated")
            put("configId", configId)
        }
        
        return JsonRpcSuccessResponse(
            jsonrpc = "2.0",
            result = result,
            id = id
        )
    }
    
    /**
     * Handle shutdown method.
     */
    private suspend fun handleShutdown(params: JsonObject?, id: Long?): JsonRpcResponse {
        val sessionId = params?.get("sessionId")?.jsonPrimitive?.content
            ?: return JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INVALID_PARAMS, message = "sessionId is required"),
                id = id
            )
        
        sessionManager.removeSession(sessionId)
        
        val result = buildJsonObject {
            put("status", "shutdown")
            put("sessionId", sessionId)
        }
        
        return JsonRpcSuccessResponse(
            jsonrpc = "2.0",
            result = result,
            id = id
        )
    }
    
    /**
     * Convert AgentConfig to JsonElement.
     */
    private fun agentConfigToJson(config: AgentConfig): JsonElement {
        return buildJsonObject {
            put("id", config.id)
            put("displayName", config.displayName)
            if (config.description != null) {
                put("description", config.description!!)
            }
            put("layer", config.layer.name)
            if (config.modelProvider != null) {
                put("modelProvider", config.modelProvider!!)
            }
            if (config.modelId != null) {
                put("modelId", config.modelId!!)
            }
            if (config.maxContextTokens != null) {
                put("maxContextTokens", config.maxContextTokens!!)
            }
            if (config.toolTimeoutSeconds != null) {
                put("toolTimeoutSeconds", config.toolTimeoutSeconds!!)
            }
            if (config.maxToolRetries != null) {
                put("maxToolRetries", config.maxToolRetries!!)
            }
            if (config.enableKickstart != null) {
                put("enableKickstart", config.enableKickstart!!)
            }
            if (config.maxKickstarts != null) {
                put("maxKickstarts", config.maxKickstarts!!)
            }
        }
    }
    
    /**
     * Convert JsonElement to AgentConfig.
     */
    private fun jsonConfigToAgentConfig(configId: String, jsonElement: JsonElement): AgentConfig {
        val obj = jsonElement.jsonObject
        return AgentConfig(
            id = configId,
            displayName = obj["displayName"]?.jsonPrimitive?.content ?: configId,
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            layer = VslfcLayer.valueOf(obj["layer"]?.jsonPrimitive?.content?.uppercase() ?: "KOOG"),
            modelProvider = obj["modelProvider"]?.jsonPrimitive?.contentOrNull,
            modelId = obj["modelId"]?.jsonPrimitive?.contentOrNull,
            maxContextTokens = obj["maxContextTokens"]?.jsonPrimitive?.longOrNull,
            toolTimeoutSeconds = obj["toolTimeoutSeconds"]?.jsonPrimitive?.longOrNull,
            maxToolRetries = obj["maxToolRetries"]?.jsonPrimitive?.intOrNull,
            enableKickstart = obj["enableKickstart"]?.jsonPrimitive?.booleanOrNull,
            maxKickstarts = obj["maxKickstarts"]?.jsonPrimitive?.intOrNull
        )
    }
}
