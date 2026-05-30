/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.server

import com.i2vision.agent.tools.DiscoveryCache
import com.i2vision.agent.tools.DiscoveryCacheFactory
import com.i2vision.agent.tools.InstantContextProvider
import com.i2vision.agent.tools.InstantContextProviderFactory
import com.i2vision.llm.OllamaLlmClient
import com.i2vision.storage.I2VisionPaths
import com.i2vision.storage.impl.FileCacheStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path

/**
 * Main entry point for the i2Vision Agent JSON-RPC Server.
 * 
 * Usage:
 *   ./gradlew :conf-agent-core:runAgentServer --args="path/to/configs 8765"
 * 
 * Arguments:
 *   1. configDir - Directory containing agent YAML configurations (default: .vision-ai)
 *   2. port - Server port (default: 8765)
 *   3. workspaceRoot - Project workspace root (default: current directory)
 */
fun main(args: Array<String>) {
    val configDir = args.getOrElse(0) { ".vision-ai" }
    val port = args.getOrElse(1) { "8765" }.toInt()
    val host = args.getOrElse(2) { "localhost" }
    val workspaceRoot = args.getOrElse(3) { System.getProperty("user.dir") }

    println("╔══════════════════════════════════════════════════════════╗")
    println("║         i2Vision Agent JSON-RPC Server                   ║")
    println("╠══════════════════════════════════════════════════════════╣")
    println("║ Config Dir:    $configDir")
    println("║ Workspace:     $workspaceRoot")
    println("║ Host:          $host")
    println("║ Port:          $port")
    println("╚══════════════════════════════════════════════════════════╝")

    // Validate config directory
    val configPath = Path.of(configDir)
    if (!configPath.toFile().exists()) {
        println("⚠ Warning: Config directory does not exist: $configDir")
        println("  Creating directory...")
        configPath.toFile().mkdirs()
    }

    // Validate workspace root
    val workspacePath = File(workspaceRoot)
    if (!workspacePath.exists()) {
        println("❌ Error: Workspace root does not exist: $workspaceRoot")
        System.exit(1)
    }

    try {
        // Create dependencies
        val llmClient = OllamaLlmClient()
        val instantContextProvider = InstantContextProviderFactory.create(workspaceRoot)
        val discoveryCache = DiscoveryCacheFactory.create(
            FileCacheStore(I2VisionPaths.getProjectCacheDir(workspaceRoot))
        )

        // Create agent provider
        val agentProvider = DefaultAgentProvider(
            configDir = configPath,
            workspaceRoot = workspaceRoot,
            llmClient = llmClient,
            instantContextProvider = instantContextProvider,
            discoveryCache = discoveryCache
        )

        // Create and start server
        val server = agentJsonRpcServer {
            port(port)
            host(host)
            agentProvider(agentProvider)
        }

        // Add shutdown hook for graceful cleanup
        Runtime.getRuntime().addShutdownHook(Thread {
            println("\n🛑 Shutting down i2Vision Agent Server...")
            runBlocking {
                runCatching { server.stop() }
            }
        })

        // Start server
        println("\n✅ Server starting...")
        runBlocking {
            server.start()
            
            println("\n🚀 Server is running!")
            println("   Health:    http://$host:$port/health")
            println("   RPC:       http://$host:$port/rpc")
            println("   WebSocket: ws://$host:$port/ws")
            println("\n   Press Ctrl+C to stop\n")

            // Keep server running
            Thread.sleep(Long.MAX_VALUE)
        }

    } catch (e: Exception) {
        println("❌ Error starting server: ${e.message}")
        e.printStackTrace()
        System.exit(1)
    }
}
