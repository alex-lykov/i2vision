package com.alyk.ai.koog.core.orchestrator.tools

import ai.koog.agents.core.tools.ToolRegistry
import com.alyk.ai.koog.context.provider.ContextProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Builder for creating Koog ToolRegistry with file access tools
 * Implements Koog's core file access functionality:
 * - ListDirectoryTool: Explore project structure
 * - ReadFileTool: Read file contents
 * - RegexSearchTool: Search across files with patterns
 * 
 * Note: This provides the tool implementations. The actual ToolRegistry
 * integration with Koog's AIAgent will be done in ImplementationAgent.
 */
class KoogToolRegistryBuilder(
    private val contextProvider: ContextProvider
) {
    /**
     * Build a ToolRegistry with Koog's standard file access tools
     * For now, returns empty registry - tools will be registered via MCP integration
     * TODO: Integrate with Koog's actual ToolRegistry.Builder API when available
     */
    fun build(): ToolRegistry {
        // Return empty registry for now
        // Tools are provided via MCP integration in McpIntegration
        // This class provides the tool implementations that can be used
        return ToolRegistry.EMPTY
    }
    
    /**
     * Get file access tool implementations
     * These can be used to create actual Koog tools
     */
    fun getFileAccessTools(): FileAccessTools {
        val projectRoot = contextProvider.getProjectRoot() ?: "."
        return FileAccessTools(projectRoot)
    }
    
    /**
     * File access tools implementation
     */
    class FileAccessTools(private val projectRoot: String) {
        init {
            println("[TOOLS] FileAccessTools initialized with projectRoot: '$projectRoot'")
        }
        
        fun getProjectRoot(): String = projectRoot
        
        fun listDirectory(path: String): ListDirectoryOutput {
            // Resolve path relative to project root
            val resolvedPath = if (path == "." || path.isEmpty()) {
                projectRoot
            } else if (path.startsWith("/") || (path.length > 1 && path[1] == ':')) {
                // Absolute path, use as-is
                path
            } else {
                // Relative path, resolve against project root
                val projectPath = Paths.get(projectRoot).normalize()
                val relativePath = Paths.get(path).normalize()
                projectPath.resolve(relativePath).normalize().toString()
            }
            println("[TOOLS] listDirectory called: path='$path' -> resolved='$resolvedPath', projectRoot='$projectRoot'")
            return try {
            val fullPath = Paths.get(resolvedPath).normalize()
            
            if (!Files.exists(fullPath)) {
                return ListDirectoryOutput(
                    success = false,
                    error = "Directory not found: $path",
                    entries = emptyList()
                )
            }
            
            if (!Files.isDirectory(fullPath)) {
                return ListDirectoryOutput(
                    success = false,
                    error = "Path is not a directory: $path",
                    entries = emptyList()
                )
            }
            
            val entries = Files.list(fullPath).map { filePath ->
                val relativePath = if (this.projectRoot.isNotEmpty()) {
                    Paths.get(this.projectRoot).relativize(filePath).toString()
                } else {
                    filePath.toString()
                }
                
                DirectoryEntry(
                    name = filePath.fileName.toString(),
                    path = relativePath,
                    isDirectory = Files.isDirectory(filePath),
                    size = if (Files.isRegularFile(filePath)) Files.size(filePath) else null
                )
            }.toList()
            
            ListDirectoryOutput(
                success = true,
                error = null,
                entries = entries
            )
        } catch (e: Exception) {
            ListDirectoryOutput(
                success = false,
                error = "Error listing directory: ${e.message}",
                entries = emptyList()
            )
        }
        }
        
        fun readFile(path: String): ReadFileOutput {
            println("[TOOLS] readFile called: path='$path', projectRoot='$projectRoot'")
            return try {
            val fullPath = if (Path.of(path).isAbsolute) {
                Paths.get(path)
            } else {
                Paths.get(this.projectRoot, path)
            }
            
            if (!Files.exists(fullPath)) {
                return ReadFileOutput(
                    success = false,
                    error = "File not found: $path",
                    content = null,
                    lines = 0
                )
            }
            
            if (!Files.isRegularFile(fullPath)) {
                return ReadFileOutput(
                    success = false,
                    error = "Path is not a file: $path",
                    content = null,
                    lines = 0
                )
            }
            
            val content = fullPath.readText()
            val lines = content.lines().size
            
            ReadFileOutput(
                success = true,
                error = null,
                content = content,
                lines = lines
            )
        } catch (e: Exception) {
            ReadFileOutput(
                success = false,
                error = "Error reading file: ${e.message}",
                content = null,
                lines = 0
            )
            }
        }
        
        fun regexSearch(
            pattern: String,
            directory: String?,
            filePattern: String?
        ): RegexSearchOutput {
            println("[TOOLS] regexSearch called: pattern='$pattern', directory=$directory, filePattern=$filePattern")
            return try {
            val searchDir = if (directory != null) {
                val dirPath = if (Path.of(directory).isAbsolute) {
                    Paths.get(directory)
                } else {
                    Paths.get(this.projectRoot, directory)
                }
                if (Files.exists(dirPath) && Files.isDirectory(dirPath)) {
                    dirPath
                } else {
                    return RegexSearchOutput(
                        success = false,
                        error = "Directory not found: $directory",
                        matches = emptyList()
                    )
                }
            } else {
                Paths.get(this.projectRoot)
            }
            
            val regex = pattern.toRegex()
            val fileRegex = filePattern?.toRegex()
            val matches = mutableListOf<RegexMatch>()
            
            Files.walk(searchDir).use { stream ->
                stream.filter { path ->
                    Files.isRegularFile(path) && (fileRegex == null || fileRegex.matches(path.fileName.toString()))
                }.forEach { filePath ->
                    try {
                        val content = filePath.readText()
                        val relativePath = if (this.projectRoot.isNotEmpty()) {
                            Paths.get(this.projectRoot).relativize(filePath).toString()
                        } else {
                            filePath.toString()
                        }
                        
                        content.lineSequence().forEachIndexed { lineNumber, line ->
                            regex.findAll(line).forEach { matchResult ->
                                matches.add(
                                    RegexMatch(
                                        file = relativePath,
                                        line = lineNumber + 1,
                                        column = matchResult.range.first + 1,
                                        match = matchResult.value,
                                        context = line.trim()
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // Skip files that can't be read (binary files, etc.)
                    }
                }
            }
            
            RegexSearchOutput(
                success = true,
                error = null,
                matches = matches
            )
        } catch (e: Exception) {
            RegexSearchOutput(
                success = false,
                error = "Error during regex search: ${e.message}",
                matches = emptyList()
            )
            }
        }
        
        fun writeFile(path: String, content: String): WriteFileOutput {
            println("[TOOLS] writeFile called: path='$path', contentLength=${content.length}, projectRoot='$projectRoot'")
            println("[TOOLS] Content preview: ${content.take(100)}...")
            return try {
            val fullPath = if (Path.of(path).isAbsolute) {
                Paths.get(path)
            } else {
                Paths.get(this.projectRoot, path)
            }
            
            println("[TOOLS] Resolved fullPath: '$fullPath'")
            println("[TOOLS] File exists before write: ${Files.exists(fullPath)}")
            
            // Create parent directories if they don't exist
            fullPath.parent?.let { parent ->
                println("[TOOLS] Creating parent directories: '$parent'")
                Files.createDirectories(parent)
            }
            
            println("[TOOLS] Writing content to file...")
            fullPath.writeText(content)
            println("[TOOLS] Write completed successfully")
            
            val result = WriteFileOutput(
                success = true,
                error = null,
                path = if (this.projectRoot.isNotEmpty()) {
                    Paths.get(this.projectRoot).relativize(fullPath).toString()
                } else {
                    fullPath.toString()
                },
                bytesWritten = content.toByteArray().size
            )
            println("[TOOLS] Returning success: ${result.success}, path: ${result.path}, bytes: ${result.bytesWritten}")
            result
        } catch (e: Exception) {
            println("[TOOLS] Write failed with exception: ${e.message}")
            e.printStackTrace()
            WriteFileOutput(
                success = false,
                error = "Error writing file: ${e.message}",
                path = null,
                bytesWritten = 0
            )
            }
        }
        
        fun runCommand(command: String, timeoutMs: Long = 30000): RunCommandOutput {
            println("[TOOLS] runCommand called: command='$command', timeoutMs=$timeoutMs, projectRoot='$projectRoot'")
            return try {
                val process = ProcessBuilder()
                    .command(if (System.getProperty("os.name").lowercase().contains("windows")) {
                        command.split(" ")
                    } else {
                        listOf("sh", "-c", command)
                    })
                    .directory(Paths.get(projectRoot).toFile())
                    .redirectErrorStream(true)
                    .start()
                
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                
                RunCommandOutput(
                    success = exitCode == 0,
                    error = if (exitCode != 0) "Command failed with exit code $exitCode" else null,
                    output = output,
                    exitCode = exitCode
                )
            } catch (e: Exception) {
                println("[TOOLS] Command execution failed: ${e.message}")
                RunCommandOutput(
                    success = false,
                    error = "Error executing command: ${e.message}",
                    output = null,
                    exitCode = -1
                )
            }
        }
    }
}

// Input/Output data classes for tools

data class ListDirectoryInput(
    val path: String
)

data class ListDirectoryOutput(
    val success: Boolean,
    val error: String?,
    val entries: List<DirectoryEntry>
)

data class DirectoryEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long?
)

data class ReadFileInput(
    val path: String
)

data class ReadFileOutput(
    val success: Boolean,
    val error: String?,
    val content: String?,
    val lines: Int
)

data class RegexSearchInput(
    val pattern: String,
    val directory: String? = null,
    val filePattern: String? = null
)

data class RegexSearchOutput(
    val success: Boolean,
    val error: String?,
    val matches: List<RegexMatch>
)

data class RegexMatch(
    val file: String,
    val line: Int,
    val column: Int,
    val match: String,
    val context: String
)

data class WriteFileInput(
    val path: String,
    val content: String
)

data class WriteFileOutput(
    val success: Boolean,
    val error: String?,
    val path: String?,
    val bytesWritten: Int
)

data class RunCommandOutput(
    val success: Boolean,
    val error: String?,
    val output: String?,
    val exitCode: Int
)
