/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.tools

import java.io.File

/**
 * File system operation tools.
 * 
 * These tools provide file manipulation capabilities:
 * - **read_file**: Read file contents
 * - **write_file**: Create or overwrite files
 * - **edit_file**: Make targeted edits
 * - **list_directory**: List directory contents
 * - **regex_search**: Search with regex patterns
 * 
 * All write operations are validated by ToolSafetyChecker before execution.
 * 
 * @property workspaceRoot Workspace root path for resolving relative paths
 * @property safetyChecker Safety validation for write operations
 */
class FileSystemTools(
    private val workspaceRoot: String,
    private val safetyChecker: ToolSafetyChecker
) {
    
    /**
     * Read the contents of a file.
     * 
     * Supports optional line range for reading partial files.
     */
    fun readFile(): Tool = Tool(
        name = "read_file",
        aliases = listOf("readfile", "cat", "view"),
        description = "Read the contents of a file. Supports optional line range for partial reads.",
        category = ToolCategory.FILE_SYSTEM,
        isReadOnly = true,
        isExpensive = false,
        cacheResults = false,
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Path relative to workspace root"
            ),
            ToolParameter(
                name = "startLine",
                type = "integer",
                description = "Start line (1-based, inclusive)",
                required = false
            ),
            ToolParameter(
                name = "endLine",
                type = "integer",
                description = "End line (1-based, inclusive)",
                required = false
            )
        ),
        handler = { args ->
            val pathArg = args["path"] as? String ?: return@Tool ToolResult.failure("path is required")
            val path = resolvePath(pathArg)
            val file = File(path)
            
            if (!file.exists()) {
                return@Tool ToolResult.failure("File not found: $pathArg")
            }
            
            if (!file.isFile) {
                return@Tool ToolResult.failure("Not a file: $pathArg")
            }
            
            val content = try {
                if (args["startLine"] != null) {
                    val lines = file.readLines()
                    val start = maxOf(0, (args["startLine"] as Int) - 1)
                    val end = minOf(lines.size, args["endLine"] as? Int ?: lines.size)
                    
                    if (start >= lines.size) {
                        return@Tool ToolResult.failure("startLine exceeds file length")
                    }
                    
                    lines.subList(start, end).joinToString("\n")
                } else {
                    file.readText()
                }
            } catch (e: Exception) {
                return@Tool ToolResult.failure("Failed to read file: ${e.message}")
            }
            
            ToolResult.success(
                output = content,
                metadata = mapOf(
                    "path" to pathArg,
                    "absolutePath" to path,
                    "size" to file.length().toString(),
                    "lines" to (if (args["startLine"] != null) "partial" else file.readLines().size.toString())
                )
            )
        }
    )
    
    /**
     * Create a new file or overwrite an existing file.
     * 
     * This tool will create parent directories if they don't exist.
     * All writes are validated by ToolSafetyChecker.
     */
    fun writeFile(): Tool = Tool(
        name = "write_file",
        aliases = listOf("writefile", "create_file", "save_file"),
        description = "Create a new file or overwrite an existing file. " +
                      "Parent directories will be created automatically. " +
                      "All writes are validated for safety before execution.",
        category = ToolCategory.FILE_SYSTEM,
        isReadOnly = false,
        isExpensive = false,
        cacheResults = false,
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Path relative to workspace root"
            ),
            ToolParameter(
                name = "content",
                type = "string",
                description = "Content to write to the file"
            )
        ),
        handler = { args ->
            val pathArg = args["path"] as? String ?: return@Tool ToolResult.failure("path is required")
            val content = args["content"] as? String ?: return@Tool ToolResult.failure("content is required")
            
            val path = resolvePath(pathArg)
            
            // Safety check
            val safetyResult = safetyChecker.checkWrite(path)
            if (!safetyResult.allowed) {
                return@Tool ToolResult.failure("Write blocked: ${safetyResult.reason}")
            }
            
            val file = File(path)
            
            try {
                // Create parent directories if needed
                file.parentFile?.mkdirs()
                
                // Write content
                file.writeText(content)
                
                val operation = if (file.exists() && file.length() > 0) "overwrite" else "create"
                
                ToolResult.success(
                    output = "File $operation: $pathArg (${content.length} characters)",
                    metadata = mapOf(
                        "path" to pathArg,
                        "absolutePath" to path,
                        "size" to file.length().toString(),
                        "operation" to operation
                    )
                )
            } catch (e: Exception) {
                return@Tool ToolResult.failure("Failed to write file: ${e.message}")
            }
        }
    )
    
    /**
     * Make targeted edits to a file using search and replace.
     * 
     * The search text must match exactly once in the file.
     * If it matches multiple times, provide more context to make it unique.
     */
    fun editFile(): Tool = Tool(
        name = "edit_file",
        aliases = listOf("editfile", "modify_file", "replace"),
        description = "Make targeted edits to a file using search and replace. " +
                      "The search text must match exactly once. " +
                      "If it matches multiple times, provide more context to make it unique.",
        category = ToolCategory.FILE_SYSTEM,
        isReadOnly = false,
        isExpensive = false,
        cacheResults = false,
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Path relative to workspace root"
            ),
            ToolParameter(
                name = "search",
                type = "string",
                description = "Text to search for (must match exactly once)"
            ),
            ToolParameter(
                name = "replace",
                type = "string",
                description = "Replacement text"
            ),
            ToolParameter(
                name = "contextLines",
                type = "integer",
                description = "Number of context lines to include in verification",
                required = false,
                default = 3
            )
        ),
        handler = { args ->
            val pathArg = args["path"] as? String ?: return@Tool ToolResult.failure("path is required")
            val search = args["search"] as? String ?: return@Tool ToolResult.failure("search is required")
            val replace = args["replace"] as? String ?: return@Tool ToolResult.failure("replace is required")
            
            val path = resolvePath(pathArg)
            val file = File(path)
            
            // Safety check
            val safetyResult = safetyChecker.checkWrite(path)
            if (!safetyResult.allowed) {
                return@Tool ToolResult.failure("Write blocked: ${safetyResult.reason}")
            }
            
            if (!file.exists()) {
                return@Tool ToolResult.failure("File not found: $pathArg")
            }
            
            val content = try {
                file.readText()
            } catch (e: Exception) {
                return@Tool ToolResult.failure("Failed to read file: ${e.message}")
            }
            
            // Find occurrences
            val occurrences = content.countOccurrences(search)
            
            when (occurrences) {
                0 -> return@Tool ToolResult.failure(
                    "Search text not found in file. " +
                    "Make sure the text matches exactly, including whitespace and indentation."
                )
                in 2..Int.MAX_VALUE -> return@Tool ToolResult.failure(
                    "Search text found $occurrences times. " +
                    "Provide more context (surrounding lines) to make the match unique."
                )
            }
            
            // Apply edit
            val newContent = content.replaceFirst(search, replace)
            
            try {
                file.writeText(newContent)
                
                ToolResult.success(
                    output = "Edit applied to $pathArg",
                    metadata = mapOf(
                        "path" to pathArg,
                        "absolutePath" to path,
                        "operation" to "edit",
                        "searchLength" to search.length.toString(),
                        "replaceLength" to replace.length.toString(),
                        "changeSize" to (replace.length - search.length).toString()
                    )
                )
            } catch (e: Exception) {
                return@Tool ToolResult.failure("Failed to write edit: ${e.message}")
            }
        }
    )
    
    /**
     * List contents of a directory.
     * 
     * Supports recursive listing and file pattern filtering.
     */
    fun listDirectory(): Tool = Tool(
        name = "list_directory",
        aliases = listOf("list_dir", "ls", "dir", "list"),
        description = "List contents of a directory. " +
                      "Supports recursive listing and file pattern filtering.",
        category = ToolCategory.FILE_SYSTEM,
        isReadOnly = true,
        isExpensive = false,
        cacheResults = false,
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Path relative to workspace root"
            ),
            ToolParameter(
                name = "recursive",
                type = "boolean",
                description = "List contents recursively",
                required = false,
                default = false
            ),
            ToolParameter(
                name = "pattern",
                type = "string",
                description = "File pattern to filter (e.g., *.kt, *.java)",
                required = false
            )
        ),
        handler = { args ->
            val pathArg = args["path"] as? String ?: return@Tool ToolResult.failure("path is required")
            val recursive = args["recursive"] as? Boolean ?: false
            val pattern = args["pattern"] as? String
            
            val path = resolvePath(pathArg)
            val dir = File(path)
            
            if (!dir.exists()) {
                return@Tool ToolResult.failure("Path not found: $pathArg")
            }
            
            if (!dir.isDirectory) {
                return@Tool ToolResult.failure("Not a directory: $pathArg")
            }
            
            val files = try {
                if (recursive) {
                    dir.walkTopDown().toList()
                } else {
                    dir.listFiles()?.toList() ?: emptyList()
                }
            } catch (e: Exception) {
                return@Tool ToolResult.failure("Failed to list directory: ${e.message}")
            }
            
            val filtered = if (pattern != null) {
                val regex = pattern.replace("*", ".*").replace("?", ".").toRegex()
                files.filter { it.name.matches(regex) }
            } else {
                files
            }
            
            // Sort: directories first, then files, alphabetically
            val sorted = filtered.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            
            ToolResult.success(
                output = sorted.joinToString("\n") { formatFileEntry(it, path) },
                metadata = mapOf(
                    "path" to pathArg,
                    "absolutePath" to path,
                    "totalFiles" to sorted.count { it.isFile }.toString(),
                    "totalDirectories" to sorted.count { it.isDirectory }.toString(),
                    "recursive" to recursive.toString(),
                    "pattern" to (pattern ?: "none")
                )
            )
        }
    )
    
    /**
     * Search for a regex pattern across files.
     * 
     * Returns matching lines with file path and line numbers.
     */
    fun regexSearch(): Tool = Tool(
        name = "regex_search",
        aliases = listOf("grep_search", "search", "find", "grep"),
        description = "Search for a regex pattern across files. " +
                      "Returns matching lines with file path and line numbers.",
        category = ToolCategory.FILE_SYSTEM,
        isReadOnly = true,
        isExpensive = true,
        cacheResults = false,
        parameters = listOf(
            ToolParameter(
                name = "pattern",
                type = "string",
                description = "Regex pattern to search for"
            ),
            ToolParameter(
                name = "path",
                type = "string",
                description = "Directory or file to search in"
            ),
            ToolParameter(
                name = "filePattern",
                type = "string",
                description = "File pattern filter (e.g., *.kt, *.java)",
                required = false
            ),
            ToolParameter(
                name = "maxResults",
                type = "integer",
                description = "Maximum number of results to return",
                required = false,
                default = 50
            ),
            ToolParameter(
                name = "contextLines",
                type = "integer",
                description = "Number of context lines before and after match",
                required = false,
                default = 0
            )
        ),
        handler = { args ->
            val patternStr = args["pattern"] as? String ?: return@Tool ToolResult.failure("pattern is required")
            val pathArg = args["path"] as? String ?: return@Tool ToolResult.failure("path is required")
            val filePattern = args["filePattern"] as? String
            val maxResults = args["maxResults"] as? Int ?: 50
            val contextLines = args["contextLines"] as? Int ?: 0
            
            val pattern = try {
                Regex(patternStr)
            } catch (e: Exception) {
                return@Tool ToolResult.failure("Invalid regex pattern: ${e.message}")
            }
            
            val path = resolvePath(pathArg)
            val root = File(path)
            
            if (!root.exists()) {
                return@Tool ToolResult.failure("Path not found: $pathArg")
            }
            
            val files = if (root.isDirectory) {
                root.walkTopDown()
                    .filter { it.isFile }
                    .toList()
            } else {
                listOf(root)
            }
            
            val filteredFiles = if (filePattern != null) {
                val regex = filePattern.replace("*", ".*").replace("?", ".").toRegex()
                files.filter { it.name.matches(regex) }
            } else {
                files
            }
            
            val results = mutableListOf<String>()
            var filesSearched = 0
            
            for (file in filteredFiles) {
                if (results.size >= maxResults) break
                
                try {
                    val lines = file.readLines()
                    filesSearched++
                    
                    lines.forEachIndexed { index, line ->
                        if (results.size >= maxResults) return@forEachIndexed
                        
                        if (pattern.containsMatchIn(line)) {
                            val relativePath = file.relativeTo(File(workspaceRoot)).path
                            val lineNum = index + 1
                            
                            val result = if (contextLines > 0) {
                                val start = maxOf(0, index - contextLines)
                                val end = minOf(lines.size, index + contextLines + 1)
                                val context = lines.subList(start, end).joinToString("\n")
                                "$relativePath:$lineNum:\n$context"
                            } else {
                                "$relativePath:$lineNum: $line"
                            }
                            
                            results.add(result)
                        }
                    }
                } catch (e: Exception) {
                    // Skip files that can't be read
                }
            }
            
            ToolResult.success(
                output = if (results.isEmpty()) {
                    "No matches found for pattern '$patternStr'"
                } else {
                    results.joinToString("\n\n")
                },
                metadata = mapOf(
                    "pattern" to patternStr,
                    "path" to pathArg,
                    "totalMatches" to results.size.toString(),
                    "filesSearched" to filesSearched.toString(),
                    "maxResults" to maxResults.toString(),
                    "filePattern" to (filePattern ?: "none")
                )
            )
        }
    )
    
    /**
     * Resolve a path relative to workspace root.
     */
    private fun resolvePath(path: String): String {
        return if (File(path).isAbsolute) {
            path
        } else {
            File(workspaceRoot, path).absolutePath
        }
    }
    
    /**
     * Format a file entry for listing output.
     */
    private fun formatFileEntry(file: File, basePath: String): String {
        val relativePath = try {
            file.relativeTo(File(basePath)).path
        } catch (e: Exception) {
            file.path
        }
        
        val type = if (file.isDirectory) "[DIR]" else "[FILE]"
        val size = if (file.isFile) " (${file.length()} bytes)" else ""
        
        return "$type $relativePath$size"
    }
}

/**
 * Count occurrences of a substring in a string.
 */
private fun String.countOccurrences(substring: String): Int {
    if (substring.isEmpty()) return 0
    
    var count = 0
    var index = 0
    
    while (index <= length - substring.length) {
        if (this.substring(index, index + substring.length) == substring) {
            count++
            index += substring.length
        } else {
            index++
        }
    }
    
    return count
}
