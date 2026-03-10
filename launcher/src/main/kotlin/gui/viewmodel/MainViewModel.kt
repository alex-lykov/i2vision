package gui.viewmodel

import core.AgentLauncher
import core.OutputEvent
import core.TaskMode
import gui.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.*

/**
 * ViewModel for MainWindow
 * Manages all UI state and coordinates between components
 */
class MainViewModel(
    private val agentLauncher: AgentLauncher,
    private val coroutineScope: CoroutineScope
) {
    // Terminal state
    private val _terminalState = MutableStateFlow(
        TerminalStateDto(
            events = emptyList(),
            inputText = "",
            isProcessing = false,
            selectedAgentType = AgentTypeDto.IMPLEMENTATION
        )
    )
    val terminalState: StateFlow<TerminalStateDto> = _terminalState.asStateFlow()

    // Agent status
    private val _agentStatus = MutableStateFlow<AgentStatusDto?>(null)
    val agentStatus: StateFlow<AgentStatusDto?> = _agentStatus.asStateFlow()

    // MCP status
    private val _mcpStatus = MutableStateFlow<McpStatusDto>(McpStatusDto.NotConnected)
    val mcpStatus: StateFlow<McpStatusDto> = _mcpStatus.asStateFlow()

    // Available MCP tools
    private val _availableMcpTools = MutableStateFlow<List<String>>(emptyList())
    val availableMcpTools: StateFlow<List<String>> = _availableMcpTools.asStateFlow()

    init {
        // Subscribe to agent status stream
        coroutineScope.launch {
            agentLauncher.getStatusStream().collect { status ->
                _agentStatus.value = mapToStatusDto(status)
            }
        }

        // Subscribe to MCP status
        coroutineScope.launch {
            val mcpStatus = agentLauncher.getMcpStatus()
            _mcpStatus.value = mapToMcpStatusDto(mcpStatus)
        }

        // Subscribe to MCP tools
        coroutineScope.launch {
            agentLauncher.getAvailableMCPTools().collect { tool ->
                _availableMcpTools.value = _availableMcpTools.value + tool
            }
        }
    }

    /**
     * Submit a task for processing
     */
    fun submitTask(task: String, mode: TaskMode) {
        if (task.isBlank()) return

        coroutineScope.launch {
            val selectedAgent = _terminalState.value.selectedAgentType
            
            _terminalState.value = _terminalState.value.copy(
                isProcessing = true,
                inputText = ""
            )

            // Add user input event with agent info
            addTerminalEvent(
                TerminalEventDto(
                    id = UUID.randomUUID().toString(),
                    timestamp = Instant.now(),
                    type = TerminalEventDto.EventType.SYSTEM,
                    message = ">>> [$selectedAgent] $task"
                )
            )

            // Show MCP tools if available
            val tools = _availableMcpTools.value
            if (tools.isNotEmpty()) {
                addTerminalEvent(
                    TerminalEventDto(
                        id = UUID.randomUUID().toString(),
                        timestamp = Instant.now(),
                        type = TerminalEventDto.EventType.SYSTEM,
                        message = "🔧 Using MCP tools: ${tools.joinToString(", ")}"
                    )
                )
            }

            // Process task with selected agent type
            agentLauncher.processTask(task, mode, selectedAgent).collect { event ->
                addTerminalEvent(mapOutputEventToDto(event))
            }

            _terminalState.value = _terminalState.value.copy(isProcessing = false)
        }
    }
    
    /**
     * Set selected agent type
     */
    fun setSelectedAgentType(agentType: AgentTypeDto) {
        _terminalState.value = _terminalState.value.copy(selectedAgentType = agentType)
    }

    /**
     * Clear terminal events
     */
    fun clearTerminal() {
        _terminalState.value = _terminalState.value.copy(events = emptyList())
    }

    /**
     * Update terminal input text
     */
    fun updateInputText(text: String) {
        _terminalState.value = _terminalState.value.copy(inputText = text)
    }

    /**
     * Load a project
     */
    fun loadProject(projectPath: String, onResult: (Boolean, String?) -> Unit) {
        coroutineScope.launch {
            val result = agentLauncher.loadProject(projectPath)
            if (result.isSuccess) {
                addTerminalEvent(
                    TerminalEventDto(
                        id = UUID.randomUUID().toString(),
                        timestamp = Instant.now(),
                        type = TerminalEventDto.EventType.SYSTEM,
                        message = "Project loaded: $projectPath"
                    )
                )
                onResult(true, null)
            } else {
                val error = result.exceptionOrNull()?.message
                addTerminalEvent(
                    TerminalEventDto(
                        id = UUID.randomUUID().toString(),
                        timestamp = Instant.now(),
                        type = TerminalEventDto.EventType.ERROR,
                        message = "Failed to load project: $error"
                    )
                )
                onResult(false, error)
            }
        }
    }

    private fun addTerminalEvent(event: TerminalEventDto) {
        _terminalState.value = _terminalState.value.copy(
            events = _terminalState.value.events + event
        )
    }

    private fun mapOutputEventToDto(event: OutputEvent): TerminalEventDto {
        return when (event) {
            is OutputEvent.Standard -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.STANDARD,
                message = event.text
            )
            is OutputEvent.Success -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.SUCCESS,
                message = event.text
            )
            is OutputEvent.Warning -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.WARNING,
                message = event.text
            )
            is OutputEvent.Error -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.ERROR,
                message = event.text
            )
            is OutputEvent.System -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.SYSTEM,
                message = event.text
            )
            is OutputEvent.Debug -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.DEBUG,
                message = event.text
            )
            is OutputEvent.Progress -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.PROGRESS,
                message = event.message,
                progressPercent = event.percent
            )
            OutputEvent.Complete -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.COMPLETE,
                message = "Task Complete"
            )
        }
    }

    private fun mapToStatusDto(status: core.AgentStatus): AgentStatusDto {
        return AgentStatusDto(
            currentModel = mapModelType(status.currentModel),
            contextUsage = status.contextUsage,
            filesLoaded = status.filesLoaded,
            sessionTime = status.sessionTime,
            confidence = status.confidence,
            lastSwitch = status.lastSwitch,
            errors = status.errors,
            avgResponseTimeMs = status.avgResponseTimeMs,
            performanceWarnings = status.performanceWarnings,
            hasPerformanceAlert = status.hasPerformanceAlert,
            currentModelId = status.currentModelId
        )
    }

    private fun mapModelType(modelType: core.ModelType): ModelTypeDto {
        return when (modelType) {
            core.ModelType.LOCAL -> ModelTypeDto.LOCAL
            core.ModelType.CLOUD -> ModelTypeDto.CLOUD
            core.ModelType.SWITCHING -> ModelTypeDto.SWITCHING
            core.ModelType.ERROR -> ModelTypeDto.ERROR
            core.ModelType.CLOUD_OLLAMA -> ModelTypeDto.CLOUD_OLLAMA
            core.ModelType.CLOUD_HF -> ModelTypeDto.CLOUD_HF
            core.ModelType.CLOUD_REPLICATE -> ModelTypeDto.CLOUD_REPLICATE
            core.ModelType.CLOUD_ANYSCALE -> ModelTypeDto.CLOUD_ANYSCALE
        }
    }

    private fun mapToMcpStatusDto(status: com.alyk.ai.koog.core.orchestrator.mcp.McpStatus): McpStatusDto {
        return when (status) {
            is com.alyk.ai.koog.core.orchestrator.mcp.McpStatus.NOT_CONNECTED -> McpStatusDto.NotConnected
            is com.alyk.ai.koog.core.orchestrator.mcp.McpStatus.CONNECTED -> McpStatusDto.Connected(status.projectPath)
            is com.alyk.ai.koog.core.orchestrator.mcp.McpStatus.ERROR -> McpStatusDto.Error(status.message)
        }
    }
}
