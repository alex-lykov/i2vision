package com.alyk.ai.koog.core.orchestrator.router

import com.alyk.ai.koog.core.orchestrator.agents.*
import kotlinx.coroutines.flow.Flow

/**
 * Router Layer: Routes tasks to appropriate specialized agents
 * based on task intent and context.
 */
class AgentRouter(
    private val ideaAgent: IdeaAgent,
    private val architectureAgent: ArchitectureAgent,
    private val moduleAgent: ModuleAgent,
    private val testAgent: TestAgent,
    private val implementationAgent: EnhancedImplementationAgent
) {
    /**
     * Route a task to the appropriate agent
     * Simplified: Always uses ImplementationAgent (can be controlled via UI)
     */
    suspend fun routeTask(task: String, context: TaskContext, agentType: AgentType = AgentType.IMPLEMENTATION): AgentResponse {
        return when (agentType) {
            AgentType.IDEA -> ideaAgent.process(task, context)
            AgentType.ARCHITECTURE -> architectureAgent.process(task, context)
            AgentType.MODULE -> moduleAgent.process(task, context)
            AgentType.TEST -> testAgent.process(task, context)
            AgentType.IMPLEMENTATION -> implementationAgent.process(task, context)
        }
    }
    
    /**
     * Route task with streaming response
     * Simplified: Always uses ImplementationAgent (can be controlled via UI)
     */
    suspend fun routeTaskStreaming(task: String, context: TaskContext, agentType: AgentType = AgentType.IMPLEMENTATION): Flow<AgentResponseChunk> {
        return when (agentType) {
            AgentType.IDEA -> ideaAgent.processStreaming(task, context)
            AgentType.ARCHITECTURE -> architectureAgent.processStreaming(task, context)
            AgentType.MODULE -> moduleAgent.processStreaming(task, context)
            AgentType.TEST -> testAgent.processStreaming(task, context)
            AgentType.IMPLEMENTATION -> implementationAgent.processStreaming(task, context)
        }
    }
}

/**
 * Task context passed to agents
 */
data class TaskContext(
    val projectPath: String?,
    val currentFiles: List<String> = emptyList(),
    val sessionId: String,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Task intent analysis result
 */
data class TaskIntent(
    val agentType: AgentType,
    val confidence: Double
)

/**
 * Agent types in the layered architecture
 */
enum class AgentType {
    IDEA,
    ARCHITECTURE,
    MODULE,
    TEST,
    IMPLEMENTATION
}

/**
 * Agent response
 */
data class AgentResponse(
    val agentType: AgentType,
    val result: String,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Streaming response chunk
 */
sealed class AgentResponseChunk {
    data class Text(val content: String) : AgentResponseChunk()
    data class Progress(val percent: Int, val message: String) : AgentResponseChunk()
    data class ToolCall(val toolName: String, val parameters: Map<String, Any>) : AgentResponseChunk()
    object Complete : AgentResponseChunk()
    data class Error(val message: String) : AgentResponseChunk()
}
