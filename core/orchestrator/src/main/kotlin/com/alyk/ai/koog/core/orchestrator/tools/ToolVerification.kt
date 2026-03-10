package com.alyk.ai.koog.core.orchestrator.tools

import com.alyk.ai.koog.context.provider.ContextProvider
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Verification mechanism to test file access tools
 * This can be called to verify that tools are working correctly
 */
class ToolVerification(
    private val contextProvider: ContextProvider,
    private val toolUsageTracker: ToolUsageTracker
) {
    
    /**
     * Verify all file access tools work correctly
     * Returns a verification report
     */
    fun verifyFileAccessTools(): VerificationReport {
        val projectRoot = contextProvider.getProjectRoot() ?: "."
        val fileAccessTools = KoogToolRegistryBuilder(contextProvider).getFileAccessTools()
        val results = mutableListOf<VerificationResult>()
        
        // Test 1: List directory
        val listResult = try {
            val startTime = System.currentTimeMillis()
            val output = fileAccessTools.listDirectory(projectRoot)
            val duration = System.currentTimeMillis() - startTime
            toolUsageTracker.recordToolUsage("list_directory", output.success, duration)
            
            VerificationResult(
                toolName = "list_directory",
                success = output.success,
                message = if (output.success) {
                    "Successfully listed ${output.entries.size} entries"
                } else {
                    "Failed: ${output.error}"
                },
                durationMs = duration
            )
        } catch (e: Exception) {
            toolUsageTracker.recordToolUsage("list_directory", false, 0)
            VerificationResult(
                toolName = "list_directory",
                success = false,
                message = "Exception: ${e.message}",
                durationMs = 0
            )
        }
        results.add(listResult)
        
        // Test 2: Read file (try to read a common file like README or build.gradle)
        val readResult = try {
            val testFiles = listOf("README.md", "build.gradle.kts", "settings.gradle.kts")
            var foundFile: String? = null
            var readSuccess = false
            var readError: String? = null
            
            for (file in testFiles) {
                val filePath = Paths.get(projectRoot, file)
                if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                    foundFile = file
                    val startTime = System.currentTimeMillis()
                    val output = fileAccessTools.readFile(file)
                    val duration = System.currentTimeMillis() - startTime
                    readSuccess = output.success
                    readError = output.error
                    toolUsageTracker.recordToolUsage("read_file", output.success, duration)
                    break
                }
            }
            
            VerificationResult(
                toolName = "read_file",
                success = readSuccess,
                message = if (readSuccess && foundFile != null) {
                    "Successfully read $foundFile (${fileAccessTools.readFile(foundFile).content?.length ?: 0} bytes)"
                } else {
                    "Failed: ${readError ?: "No test file found (tried: ${testFiles.joinToString()})"}"
                },
                durationMs = if (foundFile != null) System.currentTimeMillis() else 0
            )
        } catch (e: Exception) {
            toolUsageTracker.recordToolUsage("read_file", false, 0)
            VerificationResult(
                toolName = "read_file",
                success = false,
                message = "Exception: ${e.message}",
                durationMs = 0
            )
        }
        results.add(readResult)
        
        // Test 3: Regex search
        val searchResult = try {
            val startTime = System.currentTimeMillis()
            val output = fileAccessTools.regexSearch("class.*Agent", projectRoot, "*.kt")
            val duration = System.currentTimeMillis() - startTime
            toolUsageTracker.recordToolUsage("regex_search", output.success, duration)
            
            VerificationResult(
                toolName = "regex_search",
                success = output.success,
                message = if (output.success) {
                    "Found ${output.matches.size} matches in ${output.matches.size} files"
                } else {
                    "Failed: ${output.error}"
                },
                durationMs = duration
            )
        } catch (e: Exception) {
            toolUsageTracker.recordToolUsage("regex_search", false, 0)
            VerificationResult(
                toolName = "regex_search",
                success = false,
                message = "Exception: ${e.message}",
                durationMs = 0
            )
        }
        results.add(searchResult)
        
        // Test 4: Write file (create a test file, then delete it)
        val writeResult = try {
            val testFilePath = Paths.get(projectRoot, ".koog-test-verification.txt")
            val testContent = "Koog Tool Verification Test - ${System.currentTimeMillis()}"
            
            val startTime = System.currentTimeMillis()
            val output = fileAccessTools.writeFile(testFilePath.toString(), testContent)
            val duration = System.currentTimeMillis() - startTime
            val writeSuccess = output.success
            
            // Clean up test file
            try {
                if (Files.exists(testFilePath)) {
                    Files.delete(testFilePath)
                }
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
            
            toolUsageTracker.recordToolUsage("write_file", writeSuccess, duration)
            
            VerificationResult(
                toolName = "write_file",
                success = writeSuccess,
                message = if (writeSuccess) {
                    "Successfully wrote and cleaned up test file"
                } else {
                    "Failed: ${output.error}"
                },
                durationMs = duration
            )
        } catch (e: Exception) {
            toolUsageTracker.recordToolUsage("write_file", false, 0)
            VerificationResult(
                toolName = "write_file",
                success = false,
                message = "Exception: ${e.message}",
                durationMs = 0
            )
        }
        results.add(writeResult)
        
        val allSuccess = results.all { it.success }
        val totalDuration = results.sumOf { it.durationMs }
        
        return VerificationReport(
            projectRoot = projectRoot,
            allToolsWorking = allSuccess,
            results = results,
            totalDurationMs = totalDuration,
            timestamp = System.currentTimeMillis()
        )
    }
}

data class VerificationResult(
    val toolName: String,
    val success: Boolean,
    val message: String,
    val durationMs: Long
)

data class VerificationReport(
    val projectRoot: String,
    val allToolsWorking: Boolean,
    val results: List<VerificationResult>,
    val totalDurationMs: Long,
    val timestamp: Long
) {
    fun toSummaryString(): String {
        val status = if (allToolsWorking) "✅" else "❌"
        return """
            |$status Tool Verification Report
            |Project: $projectRoot
            |Status: ${if (allToolsWorking) "All tools working" else "Some tools failed"}
            |Total Duration: ${totalDurationMs}ms
            |
            |Results:
            |${results.joinToString("\n") { "  ${if (it.success) "✅" else "❌"} ${it.toolName}: ${it.message} (${it.durationMs}ms)" }}
        """.trimMargin()
    }
}
