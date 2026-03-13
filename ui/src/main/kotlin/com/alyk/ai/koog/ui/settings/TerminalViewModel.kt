package com.alyk.ai.koog.ui.settings

import com.alyk.ai.koog.database.settings.TerminalSettingKey
import com.alyk.ai.koog.database.settings.TerminalSettingState
import com.alyk.ai.koog.database.settings.TerminalSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalViewModel(
    private val settingsRepo: TerminalSettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    
    private val _displayState = MutableStateFlow(TerminalDisplayState())
    val displayState: StateFlow<TerminalDisplayState> = _displayState.asStateFlow()
    
    private val _settingsCategories = MutableStateFlow<Map<TerminalSettingKey.Category, List<TerminalSettingState>>>(emptyMap())
    val settingsCategories: StateFlow<Map<TerminalSettingKey.Category, List<TerminalSettingState>>> = _settingsCategories.asStateFlow()
    
    init {
        scope.launch {
            loadSettings()
            updateDisplayState()
        }
    }
    
    private suspend fun loadSettings() = withContext(Dispatchers.IO) {
        val allSettings = settingsRepo.getAllSettingsWithState()
        _settingsCategories.value = allSettings.groupBy { it.definition.category }
    }
    
    private suspend fun updateDisplayState() = withContext(Dispatchers.IO) {
        val settings = settingsRepo.areEnabled(listOf(
            "show_welcome_banner",
            "show_status_bar",
            "show_timestamps",
            "compact_mode",
            "syntax_highlighting"
        ))
        
        _displayState.value = TerminalDisplayState(
            showWelcomeBanner = settings["show_welcome_banner"] ?: true,
            showStatusBar = settings["show_status_bar"] ?: true,
            showTimestamps = settings["show_timestamps"] ?: false,
            compactMode = settings["compact_mode"] ?: false,
            syntaxHighlighting = settings["syntax_highlighting"] ?: true
        )
    }
    
    fun toggleSetting(settingKey: String) {
        scope.launch(Dispatchers.IO) {
            val currentValue = settingsRepo.isEnabled(settingKey)
            settingsRepo.updateSetting(settingKey, !currentValue)
            
            // Reload all settings to reflect the change
            loadSettings()

            // Update the specific display state if the toggled key is relevant
            if (settingKey in listOf("show_welcome_banner", "show_status_bar", "show_timestamps", 
                                     "compact_mode", "syntax_highlighting")) {
                updateDisplayState()
            }
        }
    }

    fun resetToDefaults() {
        scope.launch(Dispatchers.IO) {
            settingsRepo.resetToDefaults()
            loadSettings()
            updateDisplayState()
        }
    }
}

data class TerminalDisplayState(
    val showWelcomeBanner: Boolean = true,
    val showStatusBar: Boolean = true,
    val showTimestamps: Boolean = false,
    val compactMode: Boolean = false,
    val syntaxHighlighting: Boolean = true
)
