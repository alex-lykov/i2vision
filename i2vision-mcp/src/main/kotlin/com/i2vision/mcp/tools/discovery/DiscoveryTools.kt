package com.i2vision.mcp.tools.discovery

import com.i2vision.discover.pipeline.DiscoveryPipelineImpl
import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.impl.FileCacheStore
import com.i2vision.discover.intent.IntentResolverImpl
import com.i2vision.discover.api.models.DiscoveryDepth
import com.i2vision.mcp.server.ToolResult
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Discovery Tools - MCP tools for project discovery.
 */
class DiscoveryTools(
    private val projectRoot: String
) {

    private val log = LoggerFactory.getLogger(DiscoveryTools::class.java)

    /**
     * Discover project - Run discovery analysis on a project.
     */
    suspend fun discoverProject(parameters: Map<String, Any>): ToolResult {
        log.debug("[MCP] discover_project called with parameters: {}", parameters)

        val depth = parameters["depth"] as? String ?: "STANDARD"
        val cluster = parameters["cluster"] as? String

        val discoveryDepth = try {
            DiscoveryDepth.valueOf(depth.uppercase())
        } catch (e: IllegalArgumentException) {
            return ToolResult.error("Invalid depth: $depth. Valid options: BROWSE, STANDARD, DEEP")
        }

        val intentResolver = IntentResolverImpl()
        val cacheStore = FileCacheStore(File(projectRoot))
        val pipeline = DiscoveryPipelineImpl(projectRoot, intentResolver, cacheStore)

        val result = pipeline.discover(
            depth = discoveryDepth,
            clusterId = cluster,
            contracts = emptyList()
        )

        return ToolResult.success(
            mapOf(
                "success" to result.success,
                "artifacts" to result.artifacts.size,
                "errors" to result.errors,
                "metadata" to result.metadata
            )
        )
    }

    /**
     * Analyze file - Analyze a specific file.
     */
    suspend fun analyzeFile(parameters: Map<String, Any>): ToolResult {
        log.debug("[MCP] analyze_file called with parameters: {}", parameters)

        val filePath = parameters["file"] as? String
            ?: return ToolResult.error("Missing required parameter: file")

        val file = File(projectRoot, filePath)
        if (!file.exists()) {
            return ToolResult.error("File not found: $filePath")
        }

        // TODO: Implement file analysis using index-provider
        return ToolResult.success(
            mapOf(
                "file" to filePath,
                "exists" to true,
                "size" to file.length()
            )
        )
    }
}
