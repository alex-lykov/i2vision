package gui.viewmodel

import com.alyk.ai.koog.database.settings.TerminalOutputFilter
import com.alyk.ai.koog.database.settings.TerminalSettingKey
import com.alyk.ai.koog.database.settings.TerminalSettingState
import com.alyk.ai.koog.database.settings.TerminalSettingsRepository
import com.alyk.ai.koog.database.store.AgentStateStore
import com.alyk.ai.koog.database.store.AgentTabsStore
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
import com.alyk.ai.koog.database.store.AgentTab as DatabaseAgentTab
import com.alyk.ai.koog.database.store.AgentTabsState as DatabaseAgentTabsState
import com.alyk.ai.koog.database.store.AgentTypeDto as DatabaseAgentTypeDto

/**
 * ViewModel for MainWindow
 * Manages all UI state and coordinates between components.
 * When [terminalSettingsRepository] is provided, terminal output is filtered by TerminalSettingsState
 * via [TerminalOutputFilter] and [TerminalOutputMapping].
 */
class MainViewModel(
    private val agentLauncher: AgentLauncher,
    private val coroutineScope: CoroutineScope,
    private val terminalSettingsRepository: TerminalSettingsRepository? = null,
    private val agentStateStore: AgentStateStore? = null,
    private val agentTabsStore: AgentTabsStore? = null
) {
    // Expose coroutine scope for child viewmodels
    val viewModelScope: CoroutineScope = coroutineScope
    /** Current terminal display settings (key -> enabled). Empty = show all. */
    private val _terminalDisplaySettings = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Display options for terminal (timestamps, compact) from settings. For use by Terminal.kt. */
    private val _terminalDisplayOptions = MutableStateFlow(TerminalDisplayOptionsDto())
    val terminalDisplayOptions: StateFlow<TerminalDisplayOptionsDto> = _terminalDisplayOptions.asStateFlow()

    /** Settings for the filter popup (category -> list). Loaded when dialog opens. */
    private val _filterSettings = MutableStateFlow<Map<TerminalSettingKey.Category, List<TerminalSettingState>>>(emptyMap())
    val filterSettings: StateFlow<Map<TerminalSettingKey.Category, List<TerminalSettingState>>> = _filterSettings.asStateFlow()

    // Agent tabs management
    private val _agentTabsState = MutableStateFlow(DatabaseAgentTabsState())
    private val _guiAgentTabsState = MutableStateFlow(AgentTabsState())
    val agentTabsState: StateFlow<AgentTabsState> = _guiAgentTabsState.asStateFlow()
    
    // Tab-specific terminal states (tabId -> terminal state)
    private val _tabTerminalStates = MutableStateFlow<Map<String, TerminalStateDto>>(emptyMap())
    
    init {
        // Update GUI state whenever database state changes
        coroutineScope.launch {
            _agentTabsState.collect { dbState ->
                val guiState = AgentTabsState(
                    tabs = dbState.tabs.map { it.toGuiTab() },
                    activeTabId = dbState.activeTabId
                )
                println("[VIEWMODEL] Converting DB state: ${dbState.tabs.size} tabs -> GUI state: ${guiState.tabs.size} tabs")
                guiState.tabs.forEach { tab ->
                    println("[VIEWMODEL] GUI Tab: ${tab.id} - ${tab.name} (${tab.agentType}) active=${tab.id == guiState.activeTabId}")
                }
                _guiAgentTabsState.value = guiState
                
                // Load terminal state for active tab
                dbState.activeTabId?.let { activeTabId ->
                    loadTabTerminalState(activeTabId)
                }
            }
        }
    }
    
    // Conversion functions between database and GUI types
    private fun DatabaseAgentTab.toGuiTab(): gui.data.AgentTab {
        return gui.data.AgentTab(
            id = id,
            name = name,
            agentType = when (agentType) {
                DatabaseAgentTypeDto.IDEA -> gui.data.AgentTypeDto.IDEA
                DatabaseAgentTypeDto.ARCHITECTURE -> gui.data.AgentTypeDto.ARCHITECTURE
                DatabaseAgentTypeDto.MODULE -> gui.data.AgentTypeDto.MODULE
                DatabaseAgentTypeDto.TEST -> gui.data.AgentTypeDto.TEST
                DatabaseAgentTypeDto.IMPLEMENTATION -> gui.data.AgentTypeDto.IMPLEMENTATION
            },
            sessionId = sessionId,
            isActive = isActive,
            createdAt = createdAt,
            lastActiveAt = lastActiveAt,
            projectPath = projectPath,
            unreadCount = unreadCount,
            hasReceivedFirstPrompt = hasReceivedFirstPrompt
        )
    }
    
    private fun gui.data.AgentTypeDto.toDatabaseType(): DatabaseAgentTypeDto {
        return when (this) {
            gui.data.AgentTypeDto.IDEA -> DatabaseAgentTypeDto.IDEA
            gui.data.AgentTypeDto.ARCHITECTURE -> DatabaseAgentTypeDto.ARCHITECTURE
            gui.data.AgentTypeDto.MODULE -> DatabaseAgentTypeDto.MODULE
            gui.data.AgentTypeDto.TEST -> DatabaseAgentTypeDto.TEST
            gui.data.AgentTypeDto.IMPLEMENTATION -> DatabaseAgentTypeDto.IMPLEMENTATION
        }
    }

    // Terminal state - now tab-specific
    private val _terminalState = MutableStateFlow(
        TerminalStateDto(
            events = emptyList(),
            inputText = "",
            isProcessing = false,
            selectedAgentType = AgentTypeDto.IMPLEMENTATION
        )
    )
    val terminalState: StateFlow<TerminalStateDto> = _terminalState.asStateFlow()
    
    // Get terminal state for specific tab
    private fun getTerminalStateForTab(tabId: String): TerminalStateDto {
        return _tabTerminalStates.value[tabId] ?: TerminalStateDto(
            events = emptyList(),
            inputText = "",
            isProcessing = false,
            selectedAgentType = AgentTypeDto.IMPLEMENTATION
        )
    }
    
    // Load terminal state for a tab from session storage
    private suspend fun loadTabTerminalState(tabId: String) {
        val dbTab = _agentTabsState.value.tabs.find { it.id == tabId }
        dbTab?.sessionId?.let { sessionId ->
            try {
                val session = agentStateStore?.getSession(sessionId)
                session?.let { 
                    val terminalEvents = it.conversationHistory.mapIndexed { index, message ->
                        TerminalEventDto(
                            id = "session-$index",
                            timestamp = Instant.ofEpochMilli(it.updatedAtMillis), // Use session updated time as approximation
                            type = if (message.startsWith(">>>")) TerminalEventDto.EventType.SYSTEM 
                                   else if (message.contains("ERROR")) TerminalEventDto.EventType.ERROR
                                   else if (message.contains("✅")) TerminalEventDto.EventType.SUCCESS
                                   else TerminalEventDto.EventType.STANDARD,
                            message = message,
                            outputSettingKey = TerminalOutputMapping.KEY_SYSTEM
                        )
                    }
                    
                    val tabTerminalState = TerminalStateDto(
                        events = terminalEvents,
                        inputText = "",
                        isProcessing = false,
                        selectedAgentType = convertDatabaseAgentTypeToGui(dbTab.agentType)
                    )
                    
                    val updatedStates = _tabTerminalStates.value.toMutableMap()
                    updatedStates[tabId] = tabTerminalState
                    _tabTerminalStates.value = updatedStates
                    
                    // If this is the active tab, update the main terminal state
                    if (_guiAgentTabsState.value.activeTabId == tabId) {
                        _terminalState.value = tabTerminalState
                    }
                    
                    println("[VIEWMODEL] Loaded terminal state for tab $tabId: ${terminalEvents.size} events")
                }
            } catch (e: Exception) {
                println("[VIEWMODEL] Error loading terminal state for tab $tabId: ${e.message}")
            }
        }
    }
    
    // Save terminal state for a tab to session storage
    private suspend fun saveTabTerminalState(tabId: String) {
        val dbTab = _agentTabsState.value.tabs.find { it.id == tabId }
        val terminalState = _tabTerminalStates.value[tabId]
        
        if (dbTab != null && dbTab.sessionId != null && terminalState != null) {
            try {
                val sessionId = dbTab.sessionId
                val conversationHistory = terminalState.events.map { event ->
                    event.message
                }
                
                agentStateStore?.updateSession(sessionId!!) { session ->
                    session.copy(conversationHistory = conversationHistory)
                }
                
                println("[VIEWMODEL] Saved terminal state for tab $tabId: ${conversationHistory.size} messages")
            } catch (e: Exception) {
                println("[VIEWMODEL] Error saving terminal state for tab $tabId: ${e.message}")
            }
        }
    }

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
        
        // Load tabs from storage and create default if needed
        loadTabsAndCreateDefault()
        
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
                    showStatusBar = settings["show_status_bar"] ?: true,
                    renderRichToolCards = settings["render_rich_tool_cards"] ?: true,
                    highlightToolParams = settings["highlight_tool_params"] ?: true
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
        val verboseMode = _terminalDisplaySettings.value["verbose_mode"] == true
        val effectiveMode = if (verboseMode) TaskMode.DEBUG else mode
        
        println("[VIEWMODEL] submitTask called: task='$task', mode=$mode, verbose=$verboseMode -> effectiveMode=$effectiveMode")
        
        if (task.isBlank()) {
            println("[VIEWMODEL] Task is blank, returning")
            return
        }

        coroutineScope.launch {
            val selectedAgent = _terminalState.value.selectedAgentType
            println("[VIEWMODEL] Selected agent: $selectedAgent")
            
            // Update tab name from first prompt for the active tab
            val activeTab = _agentTabsState.value.activeTab
            if (activeTab != null) {
                updateTabNameFromPrompt(activeTab.id, task)
            }
            
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
            agentLauncher.processTask(task, effectiveMode, selectedAgent).collect { event ->
                addTerminalEvent(mapOutputEventToDto(event))
            }

            _terminalState.value = _terminalState.value.copy(isProcessing = false)
            
            // Save terminal state for current tab after task completion
            _guiAgentTabsState.value.activeTabId?.let { activeTabId ->
                val currentTerminalState = _terminalState.value
                val updatedStates = _tabTerminalStates.value.toMutableMap()
                updatedStates[activeTabId] = currentTerminalState
                _tabTerminalStates.value = updatedStates
                saveTabTerminalState(activeTabId)
            }
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

    // Input history management
    fun addToInputHistory(input: String) {
        val currentState = _terminalState.value
        val history = currentState.inputHistory.toMutableList()
        
        // Remove if already exists (to avoid duplicates)
        history.remove(input)
        // Add to beginning
        history.add(0, input)
        // Keep only last 100 entries
        if (history.size > 100) {
            history.removeAt(history.size - 1)
        }
        
        _terminalState.value = currentState.copy(
            inputHistory = history,
            historyIndex = -1 // Reset index when adding new input
        )
    }
    
    fun navigateInputHistory(direction: TerminalViewModel.HistoryDirection) {
        val currentState = _terminalState.value
        val history = currentState.inputHistory
        
        if (history.isEmpty()) return
        
        val newIndex = when (direction) {
            TerminalViewModel.HistoryDirection.UP -> {
                if (currentState.historyIndex < history.size - 1) {
                    currentState.historyIndex + 1
                } else {
                    currentState.historyIndex
                }
            }
            TerminalViewModel.HistoryDirection.DOWN -> {
                if (currentState.historyIndex > -1) {
                    currentState.historyIndex - 1
                } else {
                    -1
                }
            }
        }
        
        val newText = if (newIndex == -1) {
            "" // Clear input when going back past first item
        } else {
            history[newIndex]
        }
        
        _terminalState.value = currentState.copy(
            inputText = newText,
            historyIndex = newIndex
        )
    }
    
    fun resetInputHistoryIndex() {
        val currentState = _terminalState.value
        if (currentState.historyIndex != -1) {
            _terminalState.value = currentState.copy(historyIndex = -1)
        }
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

        if (!shouldShow) {
            // Log when an event is filtered out
            println("[VIEWMODEL] 🚫 Event filtered out: type=${event.type}, key=${event.outputSettingKey}, message=${event.message.take(50)}...")
            return
        }

        val updatedEvent = event.copy(message = formattedMessage)
        _terminalState.value = _terminalState.value.copy(
            events = _terminalState.value.events + updatedEvent
        )
        
        // Also update the current tab's terminal state
        _guiAgentTabsState.value.activeTabId?.let { activeTabId ->
            val updatedStates = _tabTerminalStates.value.toMutableMap()
            val currentTabState = updatedStates[activeTabId]?.copy(
                events = (updatedStates[activeTabId]?.events ?: emptyList()) + updatedEvent
            ) ?: TerminalStateDto(
                events = listOf(updatedEvent),
                inputText = "",
                isProcessing = false,
                selectedAgentType = _terminalState.value.selectedAgentType
            )
            updatedStates[activeTabId] = currentTabState
            _tabTerminalStates.value = updatedStates
        }
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
            is OutputEvent.ToolCallDetail -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.TOOL_CALL,
                message = "Tool: ${event.toolName}",
                outputSettingKey = key,
                payload = TerminalEventPayload.ToolCall(
                    toolName = event.toolName,
                    params = event.params,
                    result = event.result,
                    durationMs = event.durationMs,
                    success = event.success
                )
            )
            is OutputEvent.FileOp -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.FILE_OP,
                message = "${event.operation}: ${event.path}",
                outputSettingKey = key,
                payload = TerminalEventPayload.FileOp(
                    operation = event.operation,
                    path = event.path,
                    contentPreview = event.contentPreview,
                    bytesWritten = event.bytesWritten,
                    success = event.success
                )
            )
            is OutputEvent.Decision -> TerminalEventDto(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                type = TerminalEventDto.EventType.DECISION,
                message = "${event.phase}: ${event.message}",
                outputSettingKey = key,
                payload = TerminalEventPayload.Decision(phase = event.phase, message = event.message, details = event.details)
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

    // Tab management methods
    private fun loadTabsAndCreateDefault() {
        coroutineScope.launch {
            try {
                println("[VIEWMODEL] Loading tabs from storage...")
                val savedTabs = agentTabsStore?.loadTabs() ?: emptyList()
                val savedActiveTabId = agentTabsStore?.loadActiveTabId()
                
                println("[VIEWMODEL] Loaded ${savedTabs.size} tabs, active tab: $savedActiveTabId")
                savedTabs.forEach { tab ->
                    println("[VIEWMODEL] Tab: ${tab.id} - ${tab.name} (${tab.agentType})")
                }
                
                if (savedTabs.isEmpty()) {
                    println("[VIEWMODEL] No saved tabs found, creating default tab")
                    createDefaultTab()
                } else {
                    println("[VIEWMODEL] Setting tabs state with ${savedTabs.size} tabs")
                    _agentTabsState.value = DatabaseAgentTabsState(
                        tabs = savedTabs,
                        activeTabId = savedActiveTabId
                    )
                    
                    // Switch to the active tab's agent type
                    savedActiveTabId?.let { activeId ->
                        val activeTab = savedTabs.find { it.id == activeId }
                        activeTab?.let { 
                            val guiAgentType = when (it.agentType) {
                                DatabaseAgentTypeDto.IDEA -> gui.data.AgentTypeDto.IDEA
                                DatabaseAgentTypeDto.ARCHITECTURE -> gui.data.AgentTypeDto.ARCHITECTURE
                                DatabaseAgentTypeDto.MODULE -> gui.data.AgentTypeDto.MODULE
                                DatabaseAgentTypeDto.TEST -> gui.data.AgentTypeDto.TEST
                                DatabaseAgentTypeDto.IMPLEMENTATION -> gui.data.AgentTypeDto.IMPLEMENTATION
                            }
                            println("[VIEWMODEL] Switching to agent type: $guiAgentType")
                            setSelectedAgentType(guiAgentType)
                        }
                    }
                }
            } catch (e: Exception) {
                println("[VIEWMODEL] Error loading tabs: ${e.message}")
                e.printStackTrace()
                createDefaultTab()
            }
        }
    }
    
    private suspend fun saveTabsState() {
        try {
            agentTabsStore?.saveTabs(_agentTabsState.value.tabs)
            agentTabsStore?.saveActiveTabId(_agentTabsState.value.activeTabId)
        } catch (e: Exception) {
            println("[VIEWMODEL] Error saving tabs: ${e.message}")
        }
    }
    
    private suspend fun createDefaultTab() {
        val defaultTabId = createNewAgentTabInternal(gui.data.AgentTypeDto.IMPLEMENTATION, "Implementation")
        println("[VIEWMODEL] Created default tab: $defaultTabId")
        saveTabsState()
    }
    
    suspend fun createNewAgentTab(agentType: gui.data.AgentTypeDto, name: String? = null): String {
        return createNewAgentTabInternal(agentType, name)
    }
    
    private suspend fun createNewAgentTabInternal(agentType: gui.data.AgentTypeDto, name: String? = null): String {
        val tabId = UUID.randomUUID().toString()
        val tabName = name ?: generateTabName(agentType)
        val dbAgentType = agentType.toDatabaseType()
        
        // Create session in database if store is available
        val sessionId = agentStateStore?.createSession()
        
        val newTab = DatabaseAgentTab(
            id = tabId,
            name = tabName,
            agentType = dbAgentType,
            sessionId = sessionId,
            isActive = true,
            lastActiveAt = Instant.now()
        )
        
        val currentState = _agentTabsState.value
        val updatedTabs = currentState.tabs.map { it.copy(isActive = false) } + newTab
        _agentTabsState.value = DatabaseAgentTabsState(
            tabs = updatedTabs,
            activeTabId = tabId
        )
        
        // Switch to the new tab's agent type
        setSelectedAgentType(agentType)
        
        // Save tabs state
        saveTabsState()
        
        return tabId
    }
    
    fun updateTabNameFromPrompt(tabId: String, firstPrompt: String) {
        val currentState = _agentTabsState.value
        val tab = currentState.tabs.find { it.id == tabId } ?: return
        
        // Only update if tab hasn't received first prompt yet and still has default name
        if (!tab.hasReceivedFirstPrompt && isDefaultTabName(tab.name, convertDatabaseAgentTypeToGui(tab.agentType))) {
            val truncatedName = truncatePromptText(firstPrompt)
            updateAgentTabNameAndMarkFirstPrompt(tabId, truncatedName)
        }
    }
    
    private fun convertDatabaseAgentTypeToGui(dbType: DatabaseAgentTypeDto): gui.data.AgentTypeDto {
        return when (dbType) {
            DatabaseAgentTypeDto.IDEA -> gui.data.AgentTypeDto.IDEA
            DatabaseAgentTypeDto.ARCHITECTURE -> gui.data.AgentTypeDto.ARCHITECTURE
            DatabaseAgentTypeDto.MODULE -> gui.data.AgentTypeDto.MODULE
            DatabaseAgentTypeDto.TEST -> gui.data.AgentTypeDto.TEST
            DatabaseAgentTypeDto.IMPLEMENTATION -> gui.data.AgentTypeDto.IMPLEMENTATION
        }
    }
    
    private fun updateAgentTabNameAndMarkFirstPrompt(tabId: String, name: String) {
        coroutineScope.launch {
            val currentState = _agentTabsState.value
            val updatedTabs = currentState.tabs.map { tab ->
                if (tab.id == tabId) {
                    tab.copy(
                        name = name,
                        hasReceivedFirstPrompt = true
                    )
                } else {
                    tab
                }
            }
            _agentTabsState.value = currentState.copy(tabs = updatedTabs)
            saveTabsState()
        }
    }
    
    fun switchToAgentTab(tabId: String) {
        coroutineScope.launch {
            val currentState = _agentTabsState.value
            val targetTab = currentState.tabs.find { it.id == tabId } ?: return@launch
            
            // Save current tab's terminal state before switching
            currentState.activeTabId?.let { currentTabId ->
                if (currentTabId != tabId) {
                    // Save current terminal state to the current tab
                    val currentTerminalState = _terminalState.value
                    val updatedStates = _tabTerminalStates.value.toMutableMap()
                    updatedStates[currentTabId] = currentTerminalState
                    _tabTerminalStates.value = updatedStates
                    
                    // Persist to session storage
                    saveTabTerminalState(currentTabId)
                }
            }
            
            val updatedTabs = currentState.tabs.map { tab ->
                tab.copy(isActive = tab.id == tabId, lastActiveAt = if (tab.id == tabId) Instant.now() else tab.lastActiveAt)
            }
            
            _agentTabsState.value = currentState.copy(
                tabs = updatedTabs,
                activeTabId = tabId
            )
            
            // Load target tab's terminal state
            val targetTerminalState = getTerminalStateForTab(tabId)
            _terminalState.value = targetTerminalState
            
            // If we haven't loaded this tab's session yet, load it now
            if (!_tabTerminalStates.value.containsKey(tabId)) {
                loadTabTerminalState(tabId)
            }
            
            // Switch to the tab's agent type
            setSelectedAgentType(convertDatabaseAgentTypeToGui(targetTab.agentType))
            
            // Save tabs state
            saveTabsState()
            
            println("[VIEWMODEL] Switched to tab $tabId (${targetTab.name}) with ${targetTerminalState.events.size} events")
        }
    }
    
    fun closeAgentTab(tabId: String) {
        coroutineScope.launch {
            val currentState = _agentTabsState.value
            val tabs = currentState.tabs
            val tabIndex = tabs.indexOfFirst { it.id == tabId }
            
            if (tabIndex == -1) return@launch
            
            val tabToClose = tabs[tabIndex]
            
            // Clean up session from database if store is available
            tabToClose.sessionId?.let { sessionId: UUID ->
                agentStateStore?.deleteSession(sessionId)
            }
            
            val remainingTabs = tabs.filter { it.id != tabId }
            
            // If we're closing the active tab, switch to another one
            val newActiveTabId = if (currentState.activeTabId == tabId) {
                if (remainingTabs.isNotEmpty()) {
                    // Try to select the tab to the right, otherwise the tab to the left
                    val nextTabIndex = if (tabIndex < remainingTabs.size) tabIndex else tabIndex - 1
                    val nextTab = remainingTabs[nextTabIndex.coerceIn(0, remainingTabs.size - 1)]
                    nextTab.id
                } else {
                    null
                }
            } else {
                currentState.activeTabId
            }
            
            _agentTabsState.value = DatabaseAgentTabsState(
                tabs = remainingTabs,
                activeTabId = newActiveTabId
            )
            
            // Switch agent type if we have a new active tab
            newActiveTabId?.let { id ->
                val newActiveTab = remainingTabs.find { it.id == id }
                newActiveTab?.let { setSelectedAgentType(convertDatabaseAgentTypeToGui(it.agentType)) }
            }
            
            // Save tabs state
            saveTabsState()
        }
    }
    
    fun updateAgentTabName(tabId: String, name: String) {
        coroutineScope.launch {
            val currentState = _agentTabsState.value
            val updatedTabs = currentState.tabs.map { tab ->
                if (tab.id == tabId) tab.copy(name = name) else tab
            }
            _agentTabsState.value = currentState.copy(tabs = updatedTabs)
            saveTabsState()
        }
    }
    
    private fun generateTabName(agentType: gui.data.AgentTypeDto): String {
        val currentState = _agentTabsState.value
        val existingNames = currentState.tabs.filter { it.agentType == agentType.toDatabaseType() }.map { it.name }
        
        val baseName = when (agentType) {
            gui.data.AgentTypeDto.IDEA -> "Idea"
            gui.data.AgentTypeDto.ARCHITECTURE -> "Architecture"
            gui.data.AgentTypeDto.MODULE -> "Module"
            gui.data.AgentTypeDto.TEST -> "Test"
            gui.data.AgentTypeDto.IMPLEMENTATION -> "Implementation"
        }
        
        var name = baseName
        var counter = 1
        while (existingNames.contains(name)) {
            name = "$baseName ($counter)"
            counter++
        }
        
        return name
    }
    
    private fun isDefaultTabName(name: String, agentType: gui.data.AgentTypeDto): Boolean {
        val baseName = when (agentType) {
            gui.data.AgentTypeDto.IDEA -> "Idea"
            gui.data.AgentTypeDto.ARCHITECTURE -> "Architecture"
            gui.data.AgentTypeDto.MODULE -> "Module"
            gui.data.AgentTypeDto.TEST -> "Test"
            gui.data.AgentTypeDto.IMPLEMENTATION -> "Implementation"
        }
        
        return name == baseName || name.startsWith("$baseName (")
    }
    
    private fun truncatePromptText(prompt: String): String {
        val maxLength = 25
        val cleanPrompt = prompt.trim()
        
        return if (cleanPrompt.length <= maxLength) {
            cleanPrompt
        } else {
            // Try to break at word boundaries
            val truncated = cleanPrompt.take(maxLength)
            val lastSpaceIndex = truncated.lastIndexOf(' ')
            
            if (lastSpaceIndex > maxLength / 2) {
                truncated.take(lastSpaceIndex) + "..."
            } else {
                truncated + "..."
            }
        }
    }
}
