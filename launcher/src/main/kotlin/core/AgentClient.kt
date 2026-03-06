package core

import com.alyk.ai.koog.core.orchestrator.AgentOrchestrator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class AgentClient(
    private val orchestrator: AgentOrchestrator
) {
    fun processTask(task: String, mode: TaskMode): Flow<OutputEvent> = flow {
        emit(OutputEvent.System("Routing task using ${mode.name} mode"))
        emit(OutputEvent.Progress(20, "Analyzing request"))
        val result = orchestrator.routeTask(task, sessionId = "default")
        emit(OutputEvent.Progress(100, "Completed"))
        emit(OutputEvent.Success(result))
        emit(OutputEvent.Complete)
    }

    fun initialize() {
        orchestrator.initialize()
    }

    fun shutdown() {
        // Shutdown connection
    }
}
