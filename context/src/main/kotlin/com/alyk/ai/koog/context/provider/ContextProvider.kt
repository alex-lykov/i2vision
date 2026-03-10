package com.alyk.ai.koog.context.provider

import com.alyk.ai.koog.context.hierarchy.HierarchyBuilder

/**
 * Serve appropriate hierarchy levels based on request, with optimization for model type.
 */
class ContextProvider(
    private val hierarchyBuilder: HierarchyBuilder
) {
    private var loadedFiles: List<String> = emptyList()
    private var isInitialized: Boolean = false

    /**
     * Initialize with project path and scan files
     */
    suspend fun loadProject(projectPath: String) {
        println("[CONTEXT] Loading project: $projectPath")
        hierarchyBuilder.initialize(projectPath)
        println("[CONTEXT] Hierarchy builder initialized")
        loadedFiles = hierarchyBuilder.scanProjectFiles()
        println("[CONTEXT] Scanned project files: ${loadedFiles.size} files found")
        if (loadedFiles.isNotEmpty()) {
            println("[CONTEXT] Sample files: ${loadedFiles.take(5).joinToString(", ")}")
        }
        isInitialized = true
        println("[CONTEXT] ✅ Project loaded successfully")
    }

    /**
     * Get context for a specific task based on required level
     */
    suspend fun getContextForTask(task: String, level: Int = 2): String {
        // Only log for non-status-check tasks to reduce noise
        if (task != "status check") {
            println("[CONTEXT] getContextForTask: task='$task', level=$level, initialized=$isInitialized")
        }
        if (!isInitialized) {
            if (task != "status check") {
                println("[CONTEXT] ⚠️ No project loaded, returning empty context")
            }
            return "No project loaded"
        }

        val relevantFiles = findRelevantFiles(task)
        if (task != "status check" && relevantFiles.isNotEmpty()) {
            println("[CONTEXT] Found ${relevantFiles.size} relevant files for task")
        }
        val context = assembleContext(relevantFiles, level)
        return context
    }

    /**
     * Get all loaded project files
     */
    fun getLoadedFiles(): List<String> = loadedFiles

    /**
     * Check if project is loaded
     */
    fun isProjectLoaded(): Boolean = isInitialized

    /**
     * Get project root path
     */
    fun getProjectRoot(): String? = hierarchyBuilder.getProjectRoot()

    private fun findRelevantFiles(task: String): List<String> {
        // Simple keyword matching - can be enhanced with embeddings
        val keywords = task.lowercase().split(" ", ".", "_", "-")
        return loadedFiles.filter { file ->
            keywords.any { keyword ->
                file.lowercase().contains(keyword)
            }
        }.take(10) // Limit to top 10 relevant files
    }

    private suspend fun assembleContext(files: List<String>, level: Int): String {
        if (files.isEmpty()) {
            return "Project files: ${loadedFiles.size} total files loaded"
        }

        return buildString {
            appendLine("Project Context:")
            appendLine("Total files: ${loadedFiles.size}")
            appendLine("Relevant files: ${files.size}")
            appendLine()
            files.forEach { file ->
                appendLine("- $file")
            }
        }
    }

    suspend fun getContext(level: Int, task: String): String {
        return getContextForTask(task, level)
    }
}
