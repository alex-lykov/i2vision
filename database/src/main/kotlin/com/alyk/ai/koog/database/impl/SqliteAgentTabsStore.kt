package com.alyk.ai.koog.database.impl

import com.alyk.ai.koog.database.schema.AgentTabsTable
import com.alyk.ai.koog.database.store.AgentTab
import com.alyk.ai.koog.database.store.AgentTabsStore
import com.alyk.ai.koog.database.store.AgentTypeDto
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

/**
 * SQLite implementation of AgentTabsStore using Exposed ORM
 */
class SqliteAgentTabsStore(private val database: Database) : AgentTabsStore {
    
    override suspend fun saveTabs(tabs: List<AgentTab>) {
        transaction(database) {
            // Clear existing tabs
            AgentTabsTable.deleteAll()
            
            // Insert new tabs
            AgentTabsTable.batchInsert(tabs) { tab ->
                this[AgentTabsTable.id] = tab.id
                this[AgentTabsTable.name] = tab.name
                this[AgentTabsTable.agentType] = tab.agentType.name
                this[AgentTabsTable.sessionId] = tab.sessionId?.toString()
                this[AgentTabsTable.isActive] = tab.isActive
                this[AgentTabsTable.createdAt] = tab.createdAt
                this[AgentTabsTable.lastActiveAt] = tab.lastActiveAt
                this[AgentTabsTable.projectPath] = tab.projectPath
                this[AgentTabsTable.unreadCount] = tab.unreadCount
                this[AgentTabsTable.hasReceivedFirstPrompt] = tab.hasReceivedFirstPrompt
            }
        }
    }
    
    override suspend fun loadTabs(): List<AgentTab> {
        return transaction(database) {
            AgentTabsTable.selectAll()
                .orderBy(AgentTabsTable.lastActiveAt, SortOrder.DESC)
                .map { row ->
                    AgentTab(
                        id = row[AgentTabsTable.id],
                        name = row[AgentTabsTable.name],
                        agentType = AgentTypeDto.valueOf(row[AgentTabsTable.agentType]),
                        sessionId = row[AgentTabsTable.sessionId]?.let { UUID.fromString(it) },
                        isActive = row[AgentTabsTable.isActive],
                        createdAt = row[AgentTabsTable.createdAt],
                        lastActiveAt = row[AgentTabsTable.lastActiveAt],
                        projectPath = row[AgentTabsTable.projectPath],
                        unreadCount = row[AgentTabsTable.unreadCount],
                        hasReceivedFirstPrompt = row[AgentTabsTable.hasReceivedFirstPrompt]
                    )
                }
        }
    }
    
    override suspend fun saveActiveTabId(tabId: String?) {
        transaction(database) {
            // First, set all tabs to inactive
            AgentTabsTable.update({ AgentTabsTable.isActive eq true }) {
                it[isActive] = false
            }
            
            // Then set the active tab
            tabId?.let { id ->
                AgentTabsTable.update({ AgentTabsTable.id eq id }) {
                    it[isActive] = true
                }
            }
        }
    }
    
    override suspend fun loadActiveTabId(): String? {
        return transaction(database) {
            AgentTabsTable.select(AgentTabsTable.id)
                .where { AgentTabsTable.isActive eq true }
                .limit(1)
                .map { it[AgentTabsTable.id] }
                .singleOrNull()
        }
    }
    
    override suspend fun clearAllTabs() {
        transaction(database) {
            AgentTabsTable.deleteAll()
        }
    }
}
