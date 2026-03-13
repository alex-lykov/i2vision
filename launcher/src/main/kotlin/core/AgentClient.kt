package core

import com.alyk.ai.koog.core.orchestrator.AgentOrchestrator
import com.alyk.ai.koog.core.orchestrator.router.AgentResponseChunk
import com.alyk.ai.koog.core.orchestrator.router.AgentType
import gui.data.AgentTypeDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class AgentClient(
    private val orchestrator: AgentOrchestrator
) {
    fun processTask(task: String, mode: TaskMode, agentType: AgentTypeDto = AgentTypeDto.IMPLEMENTATION, sessionId: String): Flow<OutputEvent> = flow {
        emit(OutputEvent.System(">>> [${agentType.name}] $task"))
        emit(OutputEvent.System("Routing task using ${mode.name} mode with ${agentType.name} agent"))
        emit(OutputEvent.Progress(10, "Analyzing request"))
        
        // Map UI DTO to domain AgentType
        val domainAgentType = mapAgentTypeDtoToDomain(agentType)
        
        // Use streaming router for real-time updates
        orchestrator.routeTaskStreaming(task, sessionId = sessionId, agentType = domainAgentType).collect { chunk ->
            when (chunk) {
                is AgentResponseChunk.Text -> {
                    emit(OutputEvent.Standard(chunk.content))
                }
                is AgentResponseChunk.Progress -> {
                    emit(OutputEvent.Progress(chunk.percent, chunk.message))
                }
                is AgentResponseChunk.ToolCall -> {
                    emit(OutputEvent.System("🔧 Calling tool: ${chunk.toolName}"))
                }
                is AgentResponseChunk.Complete -> {
                    emit(OutputEvent.Complete)
                }
                is AgentResponseChunk.Error -> {
                    emit(OutputEvent.Error(chunk.message))
                }
            }
        }
    }

    suspend fun initialize(projectPath: String? = null) {
        orchestrator.initialize(projectPath)
    }

    fun shutdown() {
        // Shutdown connection
    }
    
    private fun mapAgentTypeDtoToDomain(dto: AgentTypeDto): AgentType {
        return when (dto) {
            AgentTypeDto.IDEA -> AgentType.IDEA
            AgentTypeDto.ARCHITECTURE -> AgentType.ARCHITECTURE
            AgentTypeDto.MODULE -> AgentType.MODULE
            AgentTypeDto.TEST -> AgentType.TEST
            AgentTypeDto.IMPLEMENTATION -> AgentType.IMPLEMENTATION
        }
    }
}
