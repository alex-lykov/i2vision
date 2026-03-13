package com.alyk.ai.koog.database.settings

import com.alyk.ai.koog.database.schema.TerminalSettingsAuditLog
import com.alyk.ai.koog.database.schema.TerminalSettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// Runtime state model
data class TerminalSettingState(
    val definition: TerminalSettingKey,
    val enabled: Boolean,
    val lastUpdated: Instant? = null,
    val isOverridden: Boolean = false  // True if different from default
)

// Session-specific settings
data class TerminalSessionSettings(
    val sessionId: String,
    val settings: Map<String, Boolean>,  // key -> enabled
    val activeProfileId: String? = null,
    val lastSyncTime: Instant = Instant.now()
)

// Settings profile
data class SettingsProfile(
    val id: String,
    val name: String,
    val description: String?,
    val settings: Map<String, Boolean>,
    val isDefault: Boolean = false
)

class TerminalSettingsRepository(
    private val db: Database,
    private val sessionId: String,
    private val userId: String? = null
) {
    
    private val settingsCache = ConcurrentHashMap<String, Boolean>()
    private val _settingsFlow = MutableStateFlow<TerminalSessionSettings?>(null)
    val settingsFlow = _settingsFlow.asStateFlow()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            loadSettingsIntoCache()
        }
    }

    suspend fun getAllSettingsWithState(): List<TerminalSettingState> = newSuspendedTransaction(db = db) {
        val currentStates = getCurrentStates()
        
        TerminalSettingKey.allSettings.map { definition ->
            val enabled = currentStates[definition.key] ?: definition.defaultValue
            TerminalSettingState(
                definition = definition,
                enabled = enabled,
                isOverridden = enabled != definition.defaultValue
            )
        }
    }

    suspend fun getSettingsByCategory(category: TerminalSettingKey.Category): List<TerminalSettingState> {
        return getAllSettingsWithState().filter { it.definition.category == category }
    }

    suspend fun isEnabled(settingKey: String): Boolean {
        settingsCache[settingKey]?.let { return it }
        
        val state = getCurrentState(settingKey) ?: TerminalSettingKey.fromKey(settingKey)?.defaultValue ?: false
        settingsCache[settingKey] = state
        return state
    }

    suspend fun areEnabled(settingKeys: List<String>): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        val missingFromCache = settingKeys.filter { !settingsCache.containsKey(it) }

        if (missingFromCache.isNotEmpty()) {
            val dbStates = getCurrentStates(missingFromCache)
            settingsCache.putAll(dbStates)
        }
        
        return settingKeys.associateWith { key ->
            settingsCache[key] ?: TerminalSettingKey.fromKey(key)?.defaultValue ?: false
        }
    }

    suspend fun updateSetting(settingKey: String, enabled: Boolean, reason: String = "user_action") = newSuspendedTransaction(db = db) {
        val definition = TerminalSettingKey.fromKey(settingKey)
            ?: throw IllegalArgumentException("Unknown setting key: $settingKey")
        
        val oldValue = getCurrentState(settingKey)

        // Upsert the setting state
        TerminalSettingsState.upsert {
            it[TerminalSettingsState.settingKey] = settingKey
            it[TerminalSettingsState.sessionId] = this@TerminalSettingsRepository.sessionId
            it[TerminalSettingsState.userId] = this@TerminalSettingsRepository.userId
            it[TerminalSettingsState.enabled] = enabled
            it[TerminalSettingsState.updatedAt] = Instant.now()
            it[TerminalSettingsState.updatedBy] = this@TerminalSettingsRepository.userId
        }

        // Add to audit log
        TerminalSettingsAuditLog.insert {
            it[TerminalSettingsAuditLog.settingKey] = settingKey
            it[TerminalSettingsAuditLog.sessionId] = this@TerminalSettingsRepository.sessionId
            it[TerminalSettingsAuditLog.oldValue] = oldValue
            it[TerminalSettingsAuditLog.newValue] = enabled
            it[changedAt] = CurrentTimestamp()
            it[changedBy] = this@TerminalSettingsRepository.userId
            it[changeReason] = reason
        }

        // Update cache and flow
        settingsCache[settingKey] = enabled
        _settingsFlow.update { current ->
            current?.copy(
                settings = current.settings.toMutableMap().apply { put(settingKey, enabled) }
            )
        }
    }

    suspend fun batchUpdateSettings(updates: Map<String, Boolean>, reason: String = "batch_update") {
        updates.forEach { (key, value) ->
            updateSetting(key, value, reason)
        }
    }

    suspend fun resetToDefaults(reason: String = "reset_defaults") {
        batchUpdateSettings(TerminalSettingKey.defaultStates, reason)
    }

    private suspend fun loadSettingsIntoCache() {
        val states = getCurrentStates()
        settingsCache.clear()
        settingsCache.putAll(states)
        
        _settingsFlow.value = TerminalSessionSettings(
            sessionId = sessionId,
            settings = states
        )
    }

    private suspend fun getCurrentStates(keys: List<String>? = null): Map<String, Boolean> = newSuspendedTransaction(db = db) {
        val query = TerminalSettingsState.selectAll()
            .where { TerminalSettingsState.sessionId eq this@TerminalSettingsRepository.sessionId }
        
        keys?.let {
            query.andWhere { TerminalSettingsState.settingKey inList it }
        }
        
        query.associate { it[TerminalSettingsState.settingKey] to it[TerminalSettingsState.enabled] }
    }
    
    private suspend fun getCurrentState(key: String): Boolean? = newSuspendedTransaction(db = db) {
        TerminalSettingsState
            .select { (TerminalSettingsState.settingKey eq key) and (TerminalSettingsState.sessionId eq this@TerminalSettingsRepository.sessionId) }
            .singleOrNull()
            ?.get(TerminalSettingsState.enabled)
    }
}
