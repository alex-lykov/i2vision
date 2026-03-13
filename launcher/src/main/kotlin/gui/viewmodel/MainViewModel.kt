package gui.viewmodel

import com.alyk.ai.koog.database.settings.TerminalOutputFilter
import com.alyk.ai.koog.database.settings.TerminalSettingKey
import com.alyk.ai.koog.database.settings.TerminalSettingState
import com.alyk.ai.koog.database.settings.TerminalSettingsRepository
import core.AgentLauncher
import core.OutputEvent
import core.TaskMode
import gui.data.*
import gui.output.TerminalOutputMapping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.*

/**
 * ViewModel for MainWindow
 * Manages all UI state and coordinates between components.
 * When [terminalSettingsRepository] is provided, terminal output is filtered by TerminalSettingsState
 * via [TerminalOutputFilter] and [TerminalOutputMapping].
 */
class MainViewModel(
    private val agentLauncher: AgentLauncher,
    private val coroutineScope: CoroutineScope,
    private val terminalSettingsRepository: TerminalSettingsRepository? = null
) {
    /** Current terminal display settings (key -> enabled). Empty = show all. */
    private val _terminalDisplaySettings = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Display options for terminal (timestamps, compact) from settings. For use by Terminal.kt. */
    private val _terminalDisplayOptions = MutableStateFlow(TerminalDisplayOptionsDto())
    val terminalDisplayOptions: StateFlow<TerminalDisplayOptionsDto> = _terminalDisplayOptions.asStateFlow()

    /** Settings for the filter popup (category -> list). Loaded when dialog opens. */
    private val _filterSettings = MutableStateFlow<Map<TerminalSettingKey.Category, List<TerminalSettingState>>>(emptyMap())
    val filterSettings: StateFlow<Map<TerminalSettingKey.Category, List<TerminalSettingState>>> = _filterSettings.asStateFlow()

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

        // Terminal output settings: load and subscribe so we can filter events
        terminalSettingsRepository?.let { repo ->
            fun applyDisplayOptions(settings: Map<String, Boolean>) {
                _terminalDisplayOptions.value = TerminalDisplayOptionsDto(
                    showTimestamps = settings["show_timestamps"] ?: false,
                    compactMode = settings["compact_mode"] ?: false,
                    showStatusBar = settings["show_status_bar"] ?: true
                )
            }
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    val keys = TerminalSettingKey.allSettings.map { it.key }
                    val initial = repo.areEnabled(keys)
                    _terminalDisplaySettings.value = initial
                    applyDisplayOptions(initial)
                }
                repo.settingsFlow.collect { sessionSettings ->
                    sessionSettings?.settings?.let { map ->
                        _terminalDisplaySettings.value = map
                        applyDisplayOptions(map)
                    }
                }
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

            // Add user input event with agent info (respect show_user_prompts)
            addTerminalEvent(
                TerminalEventDto(
                    id = UUID.randomUUID().toString(),
                    timestamp = Instant.now(),
                    type = TerminalEventDto.EventType.SYSTEM,
                    message = ">>> [$selectedAgent] $task",
                    outputSettingKey = TerminalOutputMapping.KEY_USER_PROMPT
                )
            )

            // Show MCP tools if available (respect show_command_execution)
            val tools = _availableMcpTools.value
            if (tools.isNotEmpty()) {
                addTerminalEvent(
                    TerminalEventDto(
                        id = UUID.randomUUID().toString(),
                        timestamp = Instant.now(),
                        type = TerminalEventDto.EventType.SYSTEM,
                        message = "🔧 Using MCP tools: ${tools.joinToString(", ")}",
                        outputSettingKey = TerminalOutputMapping.KEY_TOOL_CALL
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

    /** Load settings for the filter dialog (grouped by category). No-op if no repository. */
    fun loadFilterSettings() {
        terminalSettingsRepository ?: return
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                val all = terminalSettingsRepository.getAllSettingsWithState()
                _filterSettings.value = all.groupBy { it.definition.category }
            }
        }
    }

    /** Toggle a filter setting by key. No-op if no repository. */
    fun toggleFilterSetting(key: String) {
        val repo = terminalSettingsRepository ?: return
        coroutineScope.launch(Dispatchers.IO) {
            val current = repo.isEnabled(key)
            repo.updateSetting(key, !current)
            loadFilterSettings()
        }
    }

    /** Reset all terminal output settings to defaults. No-op if no repository. */
    fun resetFilterToDefaults() {
        terminalSettingsRepository ?: return
        coroutineScope.launch(Dispatchers.IO) {
            terminalSettingsRepository.resetToDefaults()
            loadFilterSettings()
        }
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
                        message = "Project loaded: $projectPath",
                        outputSettingKey = TerminalOutputMapping.KEY_SYSTEM
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
                        message = "Failed to load project: $error",
                        outputSettingKey = TerminalOutputMapping.KEY_SYSTEM
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
                        message = "Project unloaded",
                        outputSettingKey = TerminalOutputMapping.KEY_SYSTEM
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
                        message = "Failed to unload project: $error",
                        outputSettingKey = TerminalOutputMapping.KEY_SYSTEM
                    )
                )
                onResult(false, error)
            }
        }
    }

    private fun addTerminalEvent(event: TerminalEventDto) {
        val (shouldShow, formattedMessage) = TerminalOutputFilter.shouldShowAndFormat(
            outputSettingKey = event.outputSettingKey,
            settings = _terminalDisplaySettings.value,
            message = event.message,
            timestamp = event.timestamp
        )

        if (!shouldShow) return

        _terminalState.value = _terminalState.value.copy(
            events = _terminalState.value.events + event.copy(message = formattedMessage)
        )
    }

    private fun mapOutputEventToDto(event: OutputEvent): TerminalEventDto {
        val key = TerminalOutputMapping.withSettingKey(event)
        return when (event) {
            is OutputEvent.Standard -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.STANDARD,
                message = event.text,
                outputSettingKey = key
            )
            is OutputEvent.Success -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.SUCCESS,
                message = event.text,
                outputSettingKey = key
            )
            is OutputEvent.Warning -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.WARNING,
                message = event.text,
                outputSettingKey = key
            )
            is OutputEvent.Error -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.ERROR,
                message = event.text,
                outputSettingKey = key
            )
            is OutputEvent.System -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.SYSTEM,
                message = event.text,
                outputSettingKey = key
            )
            is OutputEvent.Debug -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.DEBUG,
                message = event.text,
                outputSettingKey = key
            )
            is OutputEvent.Progress -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.PROGRESS,
                message = event.message,
                progressPercent = event.percent,
                outputSettingKey = key
            )
            OutputEvent.Complete -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.COMPLETE,
                message = "Task Complete",
                outputSettingKey = key
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
