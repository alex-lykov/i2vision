package gui.viewmodel

import com.alyk.ai.koog.database.settings.TerminalSettingKey
import com.alyk.ai.koog.database.settings.TerminalSettingState
import com.alyk.ai.koog.database.store.AgentStateStore
import core.TaskMode
import gui.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for Terminal component
 * Extracted from MainViewModel for better separation of concerns.
 * State and display options align with db TerminalSettingsState via MainViewModel.
 */
class TerminalViewModel(
    private val mainViewModel: MainViewModel,
    private val agentStateStore: AgentStateStore? = null
) {
    private val coroutineScope: CoroutineScope = mainViewModel.viewModelScope
    val state: StateFlow<TerminalStateDto> = mainViewModel.terminalState
    val displayOptions: StateFlow<TerminalDisplayOptionsDto> = mainViewModel.terminalDisplayOptions
    val agentStatus: StateFlow<AgentStatusDto?> = mainViewModel.agentStatus
    val filterSettings: StateFlow<Map<TerminalSettingKey.Category, List<TerminalSettingState>>> = mainViewModel.filterSettings
    
    // Tab management
    val tabsState: StateFlow<AgentTabsState> = mainViewModel.agentTabsState

    fun submitTask(task: String, mode: TaskMode) {
        // Add to history before submitting
        addToHistory(task)
        mainViewModel.submitTask(task, mode)
    }

    fun clear() {
        mainViewModel.clearTerminal()
    }

    fun updateInputText(text: String) {
        mainViewModel.updateInputText(text)
    }
    
    fun setAgentType(agentType: AgentTypeDto) {
        mainViewModel.setSelectedAgentType(agentType)
    }

    fun loadFilterSettings() = mainViewModel.loadFilterSettings()
    fun toggleFilterSetting(key: String) = mainViewModel.toggleFilterSetting(key)
    fun resetFilterToDefaults() = mainViewModel.resetFilterToDefaults()
    
    // History management
    fun addToHistory(input: String) {
        if (input.isNotBlank()) {
            mainViewModel.addToInputHistory(input)
        }
    }
    
    fun navigateHistory(direction: HistoryDirection) {
        mainViewModel.navigateInputHistory(direction)
    }
    
    fun resetHistoryIndex() {
        mainViewModel.resetInputHistoryIndex()
    }
    
    // Tab management methods
    fun createNewTab(agentType: AgentTypeDto, name: String? = null) {
        coroutineScope.launch {
            mainViewModel.createNewAgentTab(agentType, name)
        }
    }
    
    fun switchToTab(tabId: String) {
        mainViewModel.switchToAgentTab(tabId)
    }
    
    fun closeTab(tabId: String) {
        mainViewModel.closeAgentTab(tabId)
    }
    
    fun updateTabName(tabId: String, name: String) {
        mainViewModel.updateAgentTabName(tabId, name)
    }
    
    fun getActiveTab(): AgentTab? {
        return mainViewModel.agentTabsState.value.activeTab
    }
    
    enum class HistoryDirection {
        UP, DOWN
    }
}
