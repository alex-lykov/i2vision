package gui.viewmodel

import com.alyk.ai.koog.database.settings.TerminalSettingKey
import com.alyk.ai.koog.database.settings.TerminalSettingState
import core.TaskMode
import gui.data.AgentStatusDto
import gui.data.AgentTypeDto
import gui.data.TerminalDisplayOptionsDto
import gui.data.TerminalStateDto
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for Terminal component
 * Extracted from MainViewModel for better separation of concerns.
 * State and display options align with db TerminalSettingsState via MainViewModel.
 */
class TerminalViewModel(
    private val mainViewModel: MainViewModel
) {
    val state: StateFlow<TerminalStateDto> = mainViewModel.terminalState
    val displayOptions: StateFlow<TerminalDisplayOptionsDto> = mainViewModel.terminalDisplayOptions
    val agentStatus: StateFlow<AgentStatusDto?> = mainViewModel.agentStatus
    val filterSettings: StateFlow<Map<TerminalSettingKey.Category, List<TerminalSettingState>>> = mainViewModel.filterSettings

    fun submitTask(task: String, mode: TaskMode) {
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
}
