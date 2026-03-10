package gui.viewmodel

import core.TaskMode
import gui.data.AgentTypeDto
import gui.data.TerminalStateDto
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for Terminal component
 * Extracted from MainViewModel for better separation of concerns
 */
class TerminalViewModel(
    private val mainViewModel: MainViewModel
) {
    val state: StateFlow<TerminalStateDto> = mainViewModel.terminalState

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
}
