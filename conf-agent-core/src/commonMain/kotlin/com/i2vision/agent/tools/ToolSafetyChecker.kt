/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.tools

import com.i2vision.agent.config.SafetyConfig
import java.io.File

/**
 * Safety checks before executing tool operations.
 * 
 * This component validates write operations to prevent:
 * - Writes outside the workspace
 * - Writes to generated/build directories
 * - Writes to i2vision design files
 * - Writes to protected system files
 * 
 * ## Safety Rules
 * 
 * 1. **Workspace Boundary**: All writes must be within workspace root
 * 2. **Generated Paths**: Block writes to build/, target/, dist/, etc.
 * 3. **Design Files**: Block writes to .vision-ai/ (managed by i2vision)
 * 4. **System Files**: Block writes to system directories
 * 5. **Hidden Files**: Warn on writes to hidden files (starting with .)
 * 
 * @property workspaceRoot Workspace root path
 * @property config Safety configuration from YAML
 */
class ToolSafetyChecker(
    private val workspaceRoot: String,
    private val config: SafetyConfig
) {
    
    /**
     * Check if a write operation is allowed.
     * 
     * @param path Absolute or relative path to write
     * @return Safety result with allowed status and reason
     */
    fun checkWrite(path: String): SafetyResult {
        val absolutePath = if (File(path).isAbsolute) {
            path
        } else {
            File(workspaceRoot, path).absolutePath
        }
        
        val normalizedPath = File(absolutePath).canonicalPath
        
        // Rule 1: Block writes outside workspace
        val workspaceCanonical = File(workspaceRoot).canonicalPath
        if (!normalizedPath.startsWith(workspaceCanonical)) {
            return SafetyResult(
                allowed = false,
                reason = "Cannot write outside workspace: $path (resolved to $normalizedPath)",
                severity = SafetySeverity.ERROR
            )
        }
        
        // Rule 2: Block writes to generated/build directories
        for (blocked in config.blockGeneratedPaths) {
            if (normalizedPath.contains(File.separator + blocked + File.separator) ||
                normalizedPath.endsWith(File.separator + blocked)) {
                return SafetyResult(
                    allowed = false,
                    reason = "Cannot write to generated/build directory: $blocked",
                    severity = SafetySeverity.ERROR
                )
            }
        }
        
        // Rule 3: Block writes to .vision-ai design files
        if (normalizedPath.contains(".vision-ai" + File.separator)) {
            return SafetyResult(
                allowed = false,
                reason = "Cannot modify i2vision design files directly (.vision-ai/)",
                severity = SafetySeverity.ERROR
            )
        }
        
        // Rule 4: Block writes to protected system files
        for (protected in config.protectedPaths) {
            if (normalizedPath.contains(protected)) {
                return SafetyResult(
                    allowed = false,
                    reason = "Cannot write to protected path: $protected",
                    severity = SafetySeverity.ERROR
                )
            }
        }
        
        // Rule 5: Warn on writes to hidden files
        val fileName = File(normalizedPath).name
        if (fileName.startsWith(".") && fileName != "." && fileName != "..") {
            // Check if hidden files are allowed
            if (!config.allowHiddenFileWrites) {
                return SafetyResult(
                    allowed = false,
                    reason = "Cannot write to hidden files: $fileName",
                    severity = SafetySeverity.WARNING
                )
            }
            // Return warning but allow
            return SafetyResult(
                allowed = true,
                reason = "Warning: Writing to hidden file: $fileName",
                severity = SafetySeverity.WARNING
            )
        }
        
        // Rule 6: Check file size limits for writes
        val maxFileSize = config.maxFileSizeBytes
        // Note: We can't check size before writing, but we can check for very large content
        // This would be checked in the actual write operation
        
        // All checks passed
        return SafetyResult(
            allowed = true,
            reason = null,
            severity = SafetySeverity.OK
        )
    }
    
    /**
     * Check if a read operation is allowed.
     * 
     * @param path Path to read
     * @return Safety result
     */
    fun checkRead(path: String): SafetyResult {
        val absolutePath = if (File(path).isAbsolute) {
            path
        } else {
            File(workspaceRoot, path).absolutePath
        }
        
        val file = File(absolutePath)
        
        // Check if file exists
        if (!file.exists()) {
            return SafetyResult(
                allowed = false,
                reason = "File not found: $path",
                severity = SafetySeverity.ERROR
            )
        }
        
        // Check if readable
        if (!file.canRead()) {
            return SafetyResult(
                allowed = false,
                reason = "File is not readable: $path",
                severity = SafetySeverity.ERROR
            )
        }
        
        // Block reads from protected paths
        val normalizedPath = file.canonicalPath
        for (protected in config.protectedPaths) {
            if (normalizedPath.contains(protected)) {
                return SafetyResult(
                    allowed = false,
                    reason = "Cannot read from protected path: $protected",
                    severity = SafetySeverity.ERROR
                )
            }
        }
        
        return SafetyResult(
            allowed = true,
            reason = null,
            severity = SafetySeverity.OK
        )
    }
    
    /**
     * Check if a directory listing is allowed.
     * 
     * @param path Directory path
     * @return Safety result
     */
    fun checkListDirectory(path: String): SafetyResult {
        val absolutePath = if (File(path).isAbsolute) {
            path
        } else {
            File(workspaceRoot, path).absolutePath
        }
        
        val dir = File(absolutePath)
        
        // Check if directory exists
        if (!dir.exists()) {
            return SafetyResult(
                allowed = false,
                reason = "Directory not found: $path",
                severity = SafetySeverity.ERROR
            )
        }
        
        // Check if it's a directory
        if (!dir.isDirectory) {
            return SafetyResult(
                allowed = false,
                reason = "Not a directory: $path",
                severity = SafetySeverity.ERROR
            )
        }
        
        // Check if readable
        if (!dir.canRead()) {
            return SafetyResult(
                allowed = false,
                reason = "Directory is not readable: $path",
                severity = SafetySeverity.ERROR
            )
        }
        
        return SafetyResult(
            allowed = true,
            reason = null,
            severity = SafetySeverity.OK
        )
    }
    
    /**
     * Validate a file path for safety.
     * 
     * @param path Path to validate
     * @param operation Operation type: read, write, list
     * @return Safety result
     */
    fun validate(path: String, operation: String): SafetyResult {
        return when (operation.lowercase()) {
            "write" -> checkWrite(path)
            "read" -> checkRead(path)
            "list", "list_directory" -> checkListDirectory(path)
            else -> SafetyResult(
                allowed = false,
                reason = "Unknown operation: $operation",
                severity = SafetySeverity.ERROR
            )
        }
    }
}

/**
 * Result from a safety check.
 * 
 * @property allowed Whether the operation is allowed
 * @property reason Reason for the result (null if allowed without warnings)
 * @property severity Severity level of the result
 */
data class SafetyResult(
    val allowed: Boolean,
    val reason: String? = null,
    val severity: SafetySeverity = SafetySeverity.OK
) {
    /**
     * Check if result has a warning.
     */
    fun hasWarning(): Boolean = severity == SafetySeverity.WARNING
    
    /**
     * Check if result is an error.
     */
    fun isError(): Boolean = severity == SafetySeverity.ERROR
}

/**
 * Severity level of a safety check result.
 */
enum class SafetySeverity {
    /** Operation is safe */
    OK,
    
    /** Operation has warnings but is allowed */
    WARNING,
    
    /** Operation is blocked due to safety concerns */
    ERROR
}

/**
 * Extension function to check if a path is safe for writing.
 */
fun ToolSafetyChecker.isSafeToWrite(path: String): Boolean =
    checkWrite(path).allowed

/**
 * Extension function to check if a path is safe for reading.
 */
fun ToolSafetyChecker.isSafeToRead(path: String): Boolean =
    checkRead(path).allowed
