package com.alyk.ai.koog.database.store

import java.time.Instant
import java.util.*

/**
 * Agent type enumeration for database storage
 */
enum class AgentTypeDto {
    IDEA,
    ARCHITECTURE,
    MODULE,
    TEST,
    IMPLEMENTATION
}

/**
 * Represents a single agent tab with its state
 */
data class AgentTab(
    val id: String,
    val name: String,
    val agentType: AgentTypeDto,
    val sessionId: UUID? = null,
    val isActive: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val lastActiveAt: Instant = Instant.now(),
    val projectPath: String? = null,
    val unreadCount: Int = 0,
    val hasReceivedFirstPrompt: Boolean = false
)

/**
 * State for managing multiple agent tabs
 */
data class AgentTabsState(
    val tabs: List<AgentTab> = emptyList(),
    val activeTabId: String? = null
) {
    val activeTab: AgentTab?
        get() = tabs.find { it.id == activeTabId }
        
    val hasActiveTab: Boolean
        get() = activeTabId != null && tabs.any { it.id == activeTabId }
}

/**
 * Persistence for agent tabs
 * Used to save and restore agent tabs across app restarts
 */
interface AgentTabsStore {
    /**
     * Save all tabs
     */
    suspend fun saveTabs(tabs: List<AgentTab>)
    
    /**
     * Load all saved tabs
     */
    suspend fun loadTabs(): List<AgentTab>
    
    /**
     * Save the active tab ID
     */
    suspend fun saveActiveTabId(tabId: String?)
    
    /**
     * Load the active tab ID
     */
    suspend fun loadActiveTabId(): String?
    
    /**
     * Clear all tabs
     */
    suspend fun clearAllTabs()
}

/**
 * Simple in-memory implementation for testing
 * In production, this should be replaced with database implementation
 */
class InMemoryAgentTabsStore : AgentTabsStore {
    private var tabs: List<AgentTab> = emptyList()
    private var activeTabId: String? = null
    
    override suspend fun saveTabs(tabs: List<AgentTab>) {
        this.tabs = tabs
    }
    
    override suspend fun loadTabs(): List<AgentTab> {
        return tabs
    }
    
    override suspend fun saveActiveTabId(tabId: String?) {
        this.activeTabId = tabId
    }
    
    override suspend fun loadActiveTabId(): String? {
        return activeTabId
    }
    
    override suspend fun clearAllTabs() {
        tabs = emptyList()
        activeTabId = null
    }
}

/**
 * Database entity for agent tabs
 */
data class AgentTabEntity(
    val id: String,
    val name: String,
    val agentType: String,
    val sessionId: UUID?,
    val isActive: Boolean,
    val createdAt: Long,
    val lastActiveAt: Long,
    val projectPath: String?,
    val unreadCount: Int,
    val hasReceivedFirstPrompt: Boolean
)

/**
 * Extension functions for converting between AgentTab and AgentTabEntity
 */
fun AgentTab.toEntity(): AgentTabEntity {
    return AgentTabEntity(
        id = id,
        name = name,
        agentType = agentType.name,
        sessionId = sessionId,
        isActive = isActive,
        createdAt = createdAt.toEpochMilli(),
        lastActiveAt = lastActiveAt.toEpochMilli(),
        projectPath = projectPath,
        unreadCount = unreadCount,
        hasReceivedFirstPrompt = hasReceivedFirstPrompt
    )
}

fun AgentTabEntity.toDto(): AgentTab {
    return AgentTab(
        id = id,
        name = name,
        agentType = AgentTypeDto.valueOf(agentType),
        sessionId = sessionId,
        isActive = isActive,
        createdAt = Instant.ofEpochMilli(createdAt),
        lastActiveAt = Instant.ofEpochMilli(lastActiveAt),
        projectPath = projectPath,
        unreadCount = unreadCount,
        hasReceivedFirstPrompt = hasReceivedFirstPrompt
    )
}
