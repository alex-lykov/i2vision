package com.i2vision.mcp.transport

import com.i2vision.mcp.server.McpServer
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * HTTP Transport - MCP transport layer for remote clients
 * Communicates via HTTP using JSON-RPC 2.0 protocol
 */
class HttpTransport(
    private val mcpServer: McpServer,
    private val port: Int = 8080,
    private val host: String = "0.0.0.0"
) {
    
    private val logger = LoggerFactory.getLogger(HttpTransport::class.java)
    private var server: NettyApplicationEngine? = null
    
    /**
     * Start the HTTP transport
     */
    fun start() {
        logger.info("[HTTP] Starting HTTP transport on {}:{}", host, port)
        mcpServer.start()
        
        server = embeddedServer(Netty, port = port, host = host) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            
            routing {
                post("/mcp") {
                    val requestText = call.receiveText()
                    logger.debug("[HTTP] Received request: {}", requestText)
                    
                    val response = mcpServer.handleJsonRpcRequest(requestText)
                    logger.debug("[HTTP] Sending response: {}", response)
                    
                    call.respondText(response, io.ktor.http.ContentType.Application.Json)
                }
                
                get("/health") {
                    logger.debug("[HTTP] Health check")
                    call.respond(mapOf("status" to "healthy"))
                }
                
                get("/") {
                    call.respondText("i2vision MCP Server - HTTP Transport")
                }
            }
        }
        
        server?.start(wait = false)
        logger.info("[HTTP] HTTP transport started")
    }
    
    /**
     * Stop the HTTP transport
     */
    fun stop() {
        logger.info("[HTTP] Stopping HTTP transport")
        server?.stop()
        mcpServer.stop()
        logger.info("[HTTP] HTTP transport stopped")
    }
}

/**
 * Main entry point for HTTP transport
 * Used when running as HTTP MCP server
 */
fun main(args: Array<String>) {
    val projectRoot = System.getProperty("user.dir") ?: "."
    val port = System.getProperty("http.port", "8080").toInt()
    val host = System.getProperty("http.host", "0.0.0.0")
    
    val mcpServer = McpServer(projectRoot)
    val transport = HttpTransport(mcpServer, port, host)
    
    try {
        transport.start()
        
        // Keep the server running
        Runtime.getRuntime().addShutdownHook(Thread {
            println("Shutting down MCP server...")
            transport.stop()
        })
        
        Thread.currentThread().join()
    } catch (e: Exception) {
        System.err.println("Error starting MCP server: ${e.message}")
        e.printStackTrace()
        System.exit(1)
    }
}
