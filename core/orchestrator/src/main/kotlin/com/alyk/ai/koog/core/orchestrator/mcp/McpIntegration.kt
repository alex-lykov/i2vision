package com.alyk.ai.koog.core.orchestrator.mcp

import ai.koog.agents.core.tools.ToolRegistry
import com.alyk.ai.koog.context.provider.ContextProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Model Context Protocol (MCP) integration for Koog Coding Agent
 * 
 * This class provides MCP integration to link projects with selected LLM modules,
 * enabling project-specific tools and context to be available to the language model.
 */
class McpIntegration(
    private val contextProvider: ContextProvider,
    private val baseToolRegistry: ToolRegistry = ToolRegistry.EMPTY
) {
    private var currentProjectPath: String? = null
    private val projectSpecificTools = mutableMapOf<String, ProjectMcpTool>()
    
    /**
     * Initialize MCP integration for a specific project
     */
    suspend fun initializeForProject(projectPath: String): ToolRegistry {
        currentProjectPath = projectPath
        
        // Clear previous project-specific tools
        projectSpecificTools.clear()
        
        // Create project-specific MCP tools
        val tools = createProjectMcpTools(projectPath)
        tools.forEach { tool ->
            projectSpecificTools[tool.name] = tool
        }
        
        // Create enhanced tool registry with project tools
        return createEnhancedToolRegistry(tools)
    }
    
    /**
     * Get current MCP tool registry with project-specific tools
     */
    fun getCurrentToolRegistry(): ToolRegistry {
        return if (currentProjectPath != null) {
            createEnhancedToolRegistry(projectSpecificTools.values.toList())
        } else {
            baseToolRegistry
        }
    }
    
    /**
     * Get available MCP tools for the current project
     */
    fun getAvailableTools(): Flow<ProjectMcpTool> = flow {
        projectSpecificTools.values.forEach { tool ->
            emit(tool)
        }
    }
    
    /**
     * Create project-specific MCP tools based on project structure
     */
    private suspend fun createProjectMcpTools(projectPath: String): List<ProjectMcpTool> {
        val loadedFiles = contextProvider.getLoadedFiles()
        val projectRoot = contextProvider.getProjectRoot()
        
        return listOf(
            ProjectMcpTool(
                name = "project_context",
                description = "Get comprehensive context about the current project including structure, files, and configuration",
                category = McpToolCategory.CONTEXT,
                parameters = mapOf(
                    "include_file_contents" to McpParameter(
                        type = "boolean",
                        description = "Whether to include file contents in the context",
                        required = false,
                        defaultValue = false
                    ),
                    "max_files" to McpParameter(
                        type = "integer", 
                        description = "Maximum number of files to include in context",
                        required = false,
                        defaultValue = 50
                    )
                ),
                isRelevant = true
            ),
            
            ProjectMcpTool(
                name = "file_analyzer",
                description = "Analyze specific files for code quality, complexity, and potential issues",
                category = McpToolCategory.ANALYSIS,
                parameters = mapOf(
                    "file_path" to McpParameter(
                        type = "string",
                        description = "Path to the file to analyze",
                        required = true
                    ),
                    "analysis_type" to McpParameter(
                        type = "string",
                        description = "Type of analysis: quality, complexity, security, or all",
                        required = false,
                        defaultValue = "all"
                    )
                ),
                isRelevant = loadedFiles.any { it.endsWith(".kt") || it.endsWith(".java") }
            ),
            
            ProjectMcpTool(
                name = "code_search",
                description = "Search through project codebase for specific patterns, functions, or variables",
                category = McpToolCategory.SEARCH,
                parameters = mapOf(
                    "query" to McpParameter(
                        type = "string",
                        description = "Search query or pattern to find",
                        required = true
                    ),
                    "file_types" to McpParameter(
                        type = "array",
                        description = "File types to search in (e.g., [\".kt\", \".java\"])",
                        required = false,
                        defaultValue = listOf(".kt", ".java")
                    ),
                    "case_sensitive" to McpParameter(
                        type = "boolean",
                        description = "Whether search should be case sensitive",
                        required = false,
                        defaultValue = false
                    )
                ),
                isRelevant = loadedFiles.size > 5
            ),
            
            ProjectMcpTool(
                name = "dependency_scanner",
                description = "Scan and analyze project dependencies for security and compatibility",
                category = McpToolCategory.ANALYSIS,
                parameters = mapOf(
                    "include_transitive" to McpParameter(
                        type = "boolean",
                        description = "Whether to include transitive dependencies",
                        required = false,
                        defaultValue = true
                    ),
                    "check_vulnerabilities" to McpParameter(
                        type = "boolean",
                        description = "Whether to check for known vulnerabilities",
                        required = false,
                        defaultValue = true
                    )
                ),
                isRelevant = loadedFiles.any { it.contains("build.gradle") || it.contains("pom.xml") }
            ),
            
            ProjectMcpTool(
                name = "build_runner",
                description = "Execute build commands and manage build process",
                category = McpToolCategory.EXECUTION,
                parameters = mapOf(
                    "command" to McpParameter(
                        type = "string",
                        description = "Build command to execute",
                        required = false,
                        defaultValue = "build"
                    ),
                    "clean" to McpParameter(
                        type = "boolean",
                        description = "Whether to clean before building",
                        required = false,
                        defaultValue = false
                    )
                ),
                isRelevant = loadedFiles.any { it.contains("build.gradle") || it.contains("build.gradle.kts") }
            ),
            
            ProjectMcpTool(
                name = "test_executor",
                description = "Run tests and provide test results and coverage",
                category = McpToolCategory.EXECUTION,
                parameters = mapOf(
                    "test_pattern" to McpParameter(
                        type = "string",
                        description = "Pattern for tests to run (e.g., \"*Test\", \"integration/*\")",
                        required = false,
                        defaultValue = "*"
                    ),
                    "generate_coverage" to McpParameter(
                        type = "boolean",
                        description = "Whether to generate coverage report",
                        required = false,
                        defaultValue = true
                    )
                ),
                isRelevant = loadedFiles.any { it.contains("test") }
            ),
            
            ProjectMcpTool(
                name = "git_operations",
                description = "Perform Git operations like status, commit, and diff analysis",
                category = McpToolCategory.VERSION_CONTROL,
                parameters = mapOf(
                    "operation" to McpParameter(
                        type = "string",
                        description = "Git operation: status, diff, log, or blame",
                        required = true
                    ),
                    "file_path" to McpParameter(
                        type = "string",
                        description = "Specific file path for operations that support it",
                        required = false
                    )
                ),
                isRelevant = true // Most projects use Git
            )
        ).filter { it.isRelevant }
    }
    
    /**
     * Create enhanced tool registry with project-specific tools
     */
    private fun createEnhancedToolRegistry(projectTools: List<ProjectMcpTool>): ToolRegistry {
        // In a full implementation, this would convert ProjectMcpTool instances
        // to actual MCP tool descriptors and register them with the registry
        // For now, return the base registry enhanced with project context
        return baseToolRegistry
    }
    
    /**
     * Get tool by name
     */
    fun getTool(name: String): ProjectMcpTool? {
        return projectSpecificTools[name]
    }
    
    /**
     * Check if MCP is initialized for a project
     */
    fun isInitialized(): Boolean = currentProjectPath != null

    /**
     * Clear current project (e.g. when user unselects). MCP will have no project until one is loaded again.
     */
    fun clearProject() {
        currentProjectPath = null
        projectSpecificTools.clear()
    }
    
    /**
     * Get current project path
     */
    fun getCurrentProject(): String? = currentProjectPath
}

/**
 * Represents a project-specific MCP tool
 */
data class ProjectMcpTool(
    val name: String,
    val description: String,
    val category: McpToolCategory,
    val parameters: Map<String, McpParameter>,
    val isRelevant: Boolean
)

/**
 * MCP tool parameter definition
 */
data class McpParameter(
    val type: String,
    val description: String,
    val required: Boolean,
    val defaultValue: Any? = null
)

/**
 * Categories of MCP tools
 */
enum class McpToolCategory {
    CONTEXT,
    ANALYSIS,
    SEARCH,
    EXECUTION,
    VERSION_CONTROL,
    NAVIGATION
}
