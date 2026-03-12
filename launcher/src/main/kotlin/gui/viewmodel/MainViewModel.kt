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
        println("[VIEWMODEL] Initializing MainViewModel...")
        
        // Subscribe to agent status stream
        coroutineScope.launch {
            println("[VIEWMODEL] Subscribing to agent status stream...")
            try {
                agentLauncher.getStatusStream().collect { status ->
                    val dto = mapToStatusDto(status)
                    val previousStatus = _agentStatus.value
                    
                    // Only log when status actually changes
                    if (previousStatus == null || 
                        previousStatus.filesLoaded != dto.filesLoaded ||
                        previousStatus.currentModelId != dto.currentModelId ||
                        kotlin.math.abs(previousStatus.contextUsage - dto.contextUsage) > 0.01f) {
                        println("[VIEWMODEL] Status update: model=${dto.currentModelId}, files=${dto.filesLoaded}, context=${(dto.contextUsage * 100).toInt()}%")
                    }
                    
                    _agentStatus.value = dto
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancellation is expected when view model is disposed
                println("[VIEWMODEL] Status stream cancelled (expected)")
            } catch (e: Exception) {
                println("[VIEWMODEL] ❌ Error in status stream: ${e.message}")
                e.printStackTrace()
            }
        }

        // Subscribe to MCP status
        coroutineScope.launch {
            println("[VIEWMODEL] Getting MCP status...")
            val mcpStatus = agentLauncher.getMcpStatus()
            println("[VIEWMODEL] MCP status: $mcpStatus")
            _mcpStatus.value = mapToMcpStatusDto(mcpStatus)
        }

        // Subscribe to MCP tools
        coroutineScope.launch {
            println("[VIEWMODEL] Subscribing to MCP tools...")
            agentLauncher.getAvailableMCPTools().collect { tool ->
                println("[VIEWMODEL] Received MCP tool: $tool")
                _availableMcpTools.value = _availableMcpTools.value + tool
                println("[VIEWMODEL] MCP tools count: ${_availableMcpTools.value.size}")
            }
        }
        
        println("[VIEWMODEL] MainViewModel initialization complete")
    }

    /**
     * Submit a task for processing
     */
    fun submitTask(task: String, mode: TaskMode) {
        println("[VIEWMODEL] submitTask called: task='$task', mode=$mode")
        
        if (task.isBlank()) {
            println("[VIEWMODEL] Task is blank, returning")
            return
        }

        coroutineScope.launch {
            val selectedAgent = _terminalState.value.selectedAgentType
            println("[VIEWMODEL] Selected agent: $selectedAgent")
            
            _terminalState.value = _terminalState.value.copy(
                isProcessing = true,
                inputText = ""
            )
            println("[VIEWMODEL] Terminal state updated: isProcessing=true")

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

    /**
     * Unload current project (clear selection; agent will have no project until one is selected again).
     */
    fun unloadProject(onResult: (Boolean, String?) -> Unit) {
        coroutineScope.launch {
            val result = agentLauncher.unloadProject()
            if (result.isSuccess) {
                addTerminalEvent(
                    TerminalEventDto(
                        id = UUID.randomUUID().toString(),
                        timestamp = Instant.now(),
                        type = TerminalEventDto.EventType.SYSTEM,
                        message = "Project unloaded"
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
                        message = "Failed to unload project: $error"
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
            currentModelId = status.currentModelId,
            // Enhanced monitoring
            totalRequests = status.totalRequests,
            totalTokensUsed = status.totalTokensUsed,
            totalTokensGenerated = status.totalTokensGenerated,
            maxResponseTimeMs = status.maxResponseTimeMs,
            toolUsageStats = status.toolUsageStats?.let {
                ToolUsageStatsDto(
                    totalToolCalls = it.totalToolCalls,
                    recentToolCalls = it.recentToolCalls,
                    successRate = it.successRate,
                    avgToolDurationMs = it.avgToolDurationMs,
                    toolBreakdown = it.toolBreakdown
                )
            },
            fileAccessStats = status.fileAccessStats?.let {
                FileAccessStatsDto(
                    totalFileOperations = it.totalFileOperations,
                    readOperations = it.readOperations,
                    writeOperations = it.writeOperations,
                    searchOperations = it.searchOperations,
                    listOperations = it.listOperations,
                    successfulOperations = it.successfulOperations,
                    failedOperations = it.failedOperations
                )
            },
            projectPath = status.projectPath,
            mcpToolsCount = status.mcpToolsCount
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
