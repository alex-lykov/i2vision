package com.i2vision.mcp.tools.rollout

import com.i2vision.storage.impl.RolloutManager
import com.i2vision.mcp.server.ToolResult
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Rollout Tools - MCP tools for VSLFC structure rollout.
 */
class RolloutTools(
    private val projectRoot: String
) {
    
    private val log = LoggerFactory.getLogger(RolloutTools::class.java)
    private val rolloutManager = RolloutManager()
    
    /**
     * Rollout structure - Initialize VSLFC directory structure in the project.
     */
    suspend fun rolloutStructure(parameters: Map<String, Any>): ToolResult {
        log.debug("[MCP] rollout_structure called with parameters: {}", parameters)
        
        val force = parameters["force"] as? Boolean ?: false
        
        // Check if rollout is needed unless forced
        if (!force && !rolloutManager.needsRollout(File(projectRoot))) {
            val validation = rolloutManager.validate(File(projectRoot))
            return ToolResult.success(mapOf<String, Any>(
                "status" to "already_rolled_out",
                "valid" to validation.valid,
                "missing" to validation.missing,
                "invalid" to validation.invalid,
                "version" to (validation.version ?: "")
            ))
        }
        
        // Perform rollout
        val result = rolloutManager.initialize(File(projectRoot))
        
        if (result.success) {
            return ToolResult.success(mapOf<String, Any>(
                "status" to "success",
                "created" to result.created,
                "skipped" to result.skipped,
                "errors" to result.errors,
                "version" to result.version
            ))
        } else {
            return ToolResult.error("Rollout failed: ${result.errors.joinToString(", ")}")
        }
    }
    
    /**
     * Validate structure - Check if VSLFC directory structure is valid.
     */
    suspend fun validateStructure(parameters: Map<String, Any>): ToolResult {
        log.debug("[MCP] validate_structure called with parameters: {}", parameters)
        
        val result = rolloutManager.validate(File(projectRoot))
        
        return ToolResult.success(mapOf<String, Any>(
            "valid" to result.valid,
            "missing" to result.missing,
            "invalid" to result.invalid,
            "version" to (result.version ?: "")
        ))
    }
    
    /**
     * Check rollout status - Check if rollout is needed.
     */
    suspend fun checkRolloutStatus(parameters: Map<String, Any>): ToolResult {
        log.debug("[MCP] check_rollout_status called with parameters: {}", parameters)
        
        val needsRollout = rolloutManager.needsRollout(File(projectRoot))
        
        return ToolResult.success(mapOf<String, Any>(
            "needs_rollout" to needsRollout
        ))
    }
}
