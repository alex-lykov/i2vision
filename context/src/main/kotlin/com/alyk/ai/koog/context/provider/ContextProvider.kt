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
        hierarchyBuilder.initialize(projectPath)
        loadedFiles = hierarchyBuilder.scanProjectFiles()
        isInitialized = true
    }

    /**
     * Get context for a specific task based on required level
     */
    suspend fun getContextForTask(task: String, level: Int = 2): String {
        if (!isInitialized) {
            return "No project loaded"
        }

        val relevantFiles = findRelevantFiles(task)
        return assembleContext(relevantFiles, level)
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
