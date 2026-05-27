/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.server

import com.i2vision.agent.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.Duration

/**
 * JSON-RPC server that exposes the Kotlin agent to VS Code.
 * 
 * Features:
 * - **JSON-RPC 2.0** - Standard protocol
 * - **Session Management** - One agent instance per session
 * - **Streaming** - Server notifications for streaming events via WebSocket
 * - **Cancellation** - Cancel running requests
 * 
 * Endpoints:
 * - `POST /rpc` - JSON-RPC HTTP endpoint
 * - `WS /ws` - WebSocket for streaming notifications
 * - `GET /health` - Health check endpoint
 * 
 * @param port Server port (default: 8080)
 * @param host Server host (default: localhost)
 * @param agentProvider Provider for creating agent instances
 * @param idleTimeoutMs Session idle timeout in milliseconds
 */
class AgentJsonRpcServer(
    private val port: Int = 8080,
    private val host: String = "localhost",
    private val agentProvider: I2VisionAgentProvider,
    private val idleTimeoutMs: Long = Duration.ofMinutes(30).toMillis()
) {
    /**
     * Session manager for this server instance.
     */
    private val sessionManager = SessionManager(idleTimeoutMs, agentProvider)
    
    /**
     * JSON-RPC handler.
     */
    private lateinit var handler: AgentJsonRpcHandler
    
    /**
     * Ktor server engine.
     */
    private var server: EmbeddedServer<*, *>? = null
    
    /**
     * Scope for server coroutines.
     */
    private val serverScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * Active WebSocket sessions for broadcasting notifications.
     */
    private val webSocketSessions = mutableSetOf<DefaultWebSocketServerSession>()
    
    /**
     * JSON serializer configuration.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        prettyPrint = false
    }
    
    /**
     * Check if the server is running.
     */
    val isRunning: Boolean get() = server != null
    
    /**
     * Get the server URL.
     */
    val serverUrl: String get() = "http://$host:$port"
    
    /**
     * Start the server.
     * 
     * @throws Exception if the server fails to start
     */
    fun start() {
        if (isRunning) {
            throw IllegalStateException("Server is already running")
        }
        
        println("Starting JSON-RPC server on $host:$port")
        
        // Initialize handler
        handler = AgentJsonRpcHandler(sessionManager, json)
        handler.onNotification = { notification ->
            // Broadcast to all WebSocket clients
            serverScope.launch {
                broadcastNotification(notification)
            }
        }
        
        // Configure and start Ktor server
        server = embeddedServer(
            factory = CIO,
            port = port,
            host = host
        ) {
            configureServer()
        }.start(wait = false)
        
        println("JSON-RPC server started at $serverUrl")
    }
    
    /**
     * Configure the Ktor server application.
     */
    private fun Application.configureServer() {
        // Install ContentNegotiation for JSON
        install(ContentNegotiation) {
            json(json)
        }
        
        // Install WebSocket support
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(30)
            timeout = Duration.ofMinutes(5)
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        
        // Configure routing
        routing {
            // Health check endpoint
            get("/health") {
                call.respondText(
                    """{"status":"ok","timestamp":${System.currentTimeMillis()}}""",
                    ContentType.Application.Json
                )
            }
            
            // JSON-RPC HTTP endpoint
            post("/rpc") {
                this@AgentJsonRpcServer.handleHttpRpc(call)
            }
            
            // WebSocket endpoint for streaming
            webSocket("/ws") {
                handleWebSocket()
            }
            
            // CORS preflight handling
            options("/rpc") {
                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.response.headers.append("Access-Control-Allow-Methods", "POST, OPTIONS")
                call.response.headers.append("Access-Control-Allow-Headers", "Content-Type")
                call.respond(HttpStatusCode.OK)
            }
        }
    }
    
    /**
     * Handle HTTP JSON-RPC request.
     */
    private suspend fun handleHttpRpc(call: ApplicationCall) {
        try {
            val requestBody = call.receiveText()
            println("Received RPC request: $requestBody")
            
            val request = json.decodeFromString<JsonRpcRequest>(requestBody)
            val response = handler.handleRequest(request)
            
            val responseBody = when (response) {
                is JsonRpcSuccessResponse -> json.encodeToString(response)
                is JsonRpcErrorResponse -> json.encodeToString(response)
            }
            
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.response.headers.append("Content-Type", "application/json")
            call.respondText(responseBody)
        } catch (e: Exception) {
            println("Error handling RPC request: ${e.message}")
            val errorResponse = JsonRpcErrorResponse(
                jsonrpc = "2.0",
                error = JsonRpcError(code = JsonRpcError.INTERNAL_ERROR, message = e.message ?: "Internal error"),
                id = null
            )
            val responseBody = json.encodeToString(errorResponse)
            call.respondText(responseBody, status = HttpStatusCode.InternalServerError)
        }
    }
    
    /**
     * Handle WebSocket connection for streaming.
     */
    private suspend fun DefaultWebSocketServerSession.handleWebSocket() {
        println("WebSocket client connected")
        
        // Add to active sessions
        webSocketSessions.add(this)
        
        try {
            // Keep connection alive and handle incoming messages
            for (frame in incoming) {
                frame as? Frame.Text ?: continue
                val text = frame.readText()
                
                try {
                    val request = json.decodeFromString<JsonRpcRequest>(text)
                    val response = handler.handleRequest(request)
                    
                    // Send response back
                    val responseBody = when (response) {
                        is JsonRpcSuccessResponse -> json.encodeToString(response)
                        is JsonRpcErrorResponse -> json.encodeToString(response)
                    }
                    send(Frame.Text(responseBody))
                } catch (e: Exception) {
                    println("Error handling WebSocket message: ${e.message}")
                    val errorResponse = JsonRpcErrorResponse(
                        jsonrpc = "2.0",
                        error = JsonRpcError(code = JsonRpcError.INTERNAL_ERROR, message = e.message ?: "Internal error"),
                        id = null
                    )
                    val responseBody = json.encodeToString(errorResponse)
                    send(Frame.Text(responseBody))
                }
            }
        } catch (e: Exception) {
            println("WebSocket error: ${e.message}")
        } finally {
            // Remove from active sessions
            webSocketSessions.remove(this)
            println("WebSocket client disconnected")
        }
    }
    
    /**
     * Broadcast a notification to all connected WebSocket clients.
     */
    private suspend fun broadcastNotification(notification: JsonRpcNotification) {
        if (webSocketSessions.isEmpty()) return
        
        val message = json.encodeToString(notification)
        val frame = Frame.Text(message)
        
        // Send to all sessions concurrently
        webSocketSessions.mapNotNull { session ->
            runCatching { session.send(frame) }.exceptionOrNull()
        }.forEach { error ->
            println("Failed to send notification to WebSocket client: ${error?.message}")
        }
    }
    
    /**
     * Stop the server.
     */
    suspend fun stop() {
        if (!isRunning) {
            return
        }
        
        println("Stopping JSON-RPC server...")
        
        // Stop Ktor server
        server?.stop(1000, 5000)
        server = null
        
        // Dispose session manager
        sessionManager.dispose()
        
        // Close all WebSocket connections
        webSocketSessions.forEach { session ->
            runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "Server shutting down")) }
        }
        webSocketSessions.clear()
        
        println("JSON-RPC server stopped")
    }
    
    /**
     * Get session manager for testing purposes.
     */
    fun getSessionManager(): SessionManager = sessionManager
}

/**
 * Builder for creating AgentJsonRpcServer instances.
 */
class AgentJsonRpcServerBuilder {
    private var port: Int = 8080
    private var host: String = "localhost"
    private var idleTimeoutMs: Long = Duration.ofMinutes(30).toMillis()
    private var agentProvider: I2VisionAgentProvider? = null
    
    fun port(port: Int) = apply { this.port = port }
    fun host(host: String) = apply { this.host = host }
    fun idleTimeout(timeout: Duration) = apply { this.idleTimeoutMs = timeout.toMillis() }
    fun idleTimeout(timeoutMs: Long) = apply { this.idleTimeoutMs = timeoutMs }
    fun agentProvider(provider: I2VisionAgentProvider) = apply { this.agentProvider = provider }
    
    fun build(): AgentJsonRpcServer {
        require(agentProvider != null) { "agentProvider must be set" }
        return AgentJsonRpcServer(
            port = port,
            host = host,
            agentProvider = agentProvider!!,
            idleTimeoutMs = idleTimeoutMs
        )
    }
}

/**
 * DSL function for building AgentJsonRpcServer with a builder.
 * 
 * Example:
 * ```
 * val server = agentJsonRpcServer {
 *     port = 8080
 *     host = "localhost"
 *     agentProvider = myProvider
 * }
 * ```
 */
fun agentJsonRpcServer(block: AgentJsonRpcServerBuilder.() -> Unit): AgentJsonRpcServer {
    return AgentJsonRpcServerBuilder().apply(block).build()
}
