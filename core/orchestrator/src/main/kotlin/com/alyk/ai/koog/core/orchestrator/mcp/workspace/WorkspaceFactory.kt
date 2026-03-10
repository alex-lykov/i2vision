package com.alyk.ai.koog.core.orchestrator.mcp.workspace

import com.alyk.ai.koog.context.provider.ContextProvider
import com.alyk.ai.koog.core.orchestrator.mcp.ProjectMcpTool

/**
 * Factory for creating MCP workspaces for different agent types
 */
class WorkspaceFactory(
    private val contextProvider: ContextProvider,
    private val projectMcpTools: List<ProjectMcpTool> = emptyList()
) {
    /**
     * Create workspace for a specific agent type
     */
    fun createWorkspace(workspaceType: WorkspaceType, projectContext: String = ""): McpWorkspace {
        return when (workspaceType) {
            WorkspaceType.IDEA -> IdeaWorkspace(projectContext)
            WorkspaceType.ARCHITECTURE -> ArchitectureWorkspace(projectContext)
            WorkspaceType.MODULE -> ModuleWorkspace(projectContext)
            WorkspaceType.TEST -> TestWorkspace(projectContext)
            WorkspaceType.IMPLEMENTATION -> ImplementationWorkspace(projectContext, projectMcpTools)
        }
    }
    
    /**
     * Create all workspaces for a project
     */
    suspend fun createAllWorkspaces(projectPath: String?): Map<WorkspaceType, McpWorkspace> {
        val projectContext = projectPath?.let {
            contextProvider.getContextForTask("workspace initialization", level = 1)
        } ?: ""
        
        return WorkspaceType.values().associateWith { type ->
            createWorkspace(type, projectContext)
        }
    }
}
