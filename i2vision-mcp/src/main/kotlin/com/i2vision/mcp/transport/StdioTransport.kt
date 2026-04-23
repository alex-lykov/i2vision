/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.mcp.transport

import com.i2vision.mcp.server.McpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Stdio Transport - MCP transport layer for Claude Desktop
 * Communicates via stdin/stdout using JSON-RPC 2.0 protocol
 */
class StdioTransport(
    private val mcpServer: McpServer
) {

    private val log = LoggerFactory.getLogger(StdioTransport::class.java)
    private val reader = BufferedReader(InputStreamReader(System.`in`))
    private var running = false

    /**
     * Start the stdio transport
     */
    fun start() {
        log.info("[STDIO] Starting stdio transport")
        running = true
        mcpServer.start()

        runBlocking {
            launch(Dispatchers.IO) {
                while (running) {
                    try {
                        val line = reader.readLine()
                        if (line != null) {
                            log.debug("[STDIO] Received: {}", line)
                            val response = mcpServer.handleJsonRpcRequest(line)
                            println(response)
                            System.out.flush()
                        } else {
                            // EOF reached
                            log.info("[STDIO] EOF received, shutting down")
                            stop()
                        }
                    } catch (e: Exception) {
                        log.error("[STDIO] Error processing request: {}", e.message, e)
                        // Send error response
                        val errorResponse =
                            """{"jsonrpc":"2.0","error":{"code":-32603,"message":"Internal error","data":"${e.message}"},"id":null}"""
                        println(errorResponse)
                        System.out.flush()
                    }
                }
            }
        }
    }

    /**
     * Stop the stdio transport
     */
    fun stop() {
        log.info("[STDIO] Stopping stdio transport")
        running = false
        mcpServer.stop()
    }
}

/**
 * Main entry point for stdio transport
 * Used when running as Claude Desktop MCP server
 */
fun main(args: Array<String>) {
    val projectRoot = System.getProperty("user.dir") ?: "."
    val mcpServer = McpServer(projectRoot)
    val transport = StdioTransport(mcpServer)

    try {
        transport.start()
    } catch (e: Exception) {
        System.err.println("Error starting MCP server: ${e.message}")
        e.printStackTrace()
        System.exit(1)
    }
}
