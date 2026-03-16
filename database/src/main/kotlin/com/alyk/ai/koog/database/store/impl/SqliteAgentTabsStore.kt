package com.alyk.ai.koog.database.store.impl

import com.alyk.ai.koog.database.store.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet

/**
 * SQLite implementation of AgentTabsStore
 * Provides persistent storage for agent tabs using SQLite database
 */
class SqliteAgentTabsStore(private val connection: Connection) : AgentTabsStore {
    
    companion object {
        private const val CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS agent_tabs (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                agent_type TEXT NOT NULL,
                session_id TEXT,
                is_active INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                last_active_at INTEGER NOT NULL,
                project_path TEXT,
                unread_count INTEGER NOT NULL DEFAULT 0,
                has_received_first_prompt INTEGER NOT NULL DEFAULT 0
            )
        """
        
        private const val INSERT_TAB_SQL = """
            INSERT OR REPLACE INTO agent_tabs 
            (id, name, agent_type, session_id, is_active, created_at, last_active_at, project_path, unread_count, has_received_first_prompt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        
        private const val SELECT_ALL_TABS_SQL = """
            SELECT id, name, agent_type, session_id, is_active, created_at, last_active_at, project_path, unread_count, has_received_first_prompt
            FROM agent_tabs
            ORDER BY last_active_at DESC
        """
        
        private const val DELETE_ALL_TABS_SQL = "DELETE FROM agent_tabs"
        
        private const val UPDATE_ACTIVE_TAB_SQL = """
            UPDATE agent_tabs
            SET is_active = CASE WHEN id = ? THEN 1 ELSE 0 END
        """
    }
    
    init {
        // Create table if it doesn't exist
        connection.createStatement().use { stmt ->
            stmt.execute(CREATE_TABLE_SQL)
        }
    }
    
    override suspend fun saveTabs(tabs: List<AgentTab>) {
        withContext(Dispatchers.IO) {
            connection.autoCommit = false
            try {
                // Clear existing tabs
                connection.createStatement().use { stmt ->
                    stmt.execute(DELETE_ALL_TABS_SQL)
                }
                
                // Insert new tabs
                connection.prepareStatement(INSERT_TAB_SQL).use { pstmt ->
                    tabs.forEach { tab ->
                        val entity = tab.toEntity()
                        pstmt.setString(1, entity.id)
                        pstmt.setString(2, entity.name)
                        pstmt.setString(3, entity.agentType)
                        pstmt.setString(4, entity.sessionId?.toString())
                        pstmt.setInt(5, if (entity.isActive) 1 else 0)
                        pstmt.setLong(6, entity.createdAt)
                        pstmt.setLong(7, entity.lastActiveAt)
                        pstmt.setString(8, entity.projectPath)
                        pstmt.setInt(9, entity.unreadCount)
                        pstmt.setInt(10, if (entity.hasReceivedFirstPrompt) 1 else 0)
                        pstmt.addBatch()
                    }
                    pstmt.executeBatch()
                }
                
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }
    
    override suspend fun loadTabs(): List<AgentTab> {
        return withContext(Dispatchers.IO) {
            connection.prepareStatement(SELECT_ALL_TABS_SQL).use { pstmt ->
                val resultSet = pstmt.executeQuery()
                val tabs = mutableListOf<AgentTab>()
                
                while (resultSet.next()) {
                    tabs.add(resultSet.toTabEntity().toDto())
                }
                
                tabs
            }
        }
    }
    
    override suspend fun saveActiveTabId(tabId: String?) {
        withContext(Dispatchers.IO) {
            connection.autoCommit = false
            try {
                // First, set all tabs to inactive
                connection.createStatement().use { stmt ->
                    stmt.execute("UPDATE agent_tabs SET is_active = 0")
                }
                
                // Then set the active tab
                tabId?.let { id ->
                    connection.prepareStatement(UPDATE_ACTIVE_TAB_SQL).use { pstmt ->
                        pstmt.setString(1, id)
                        pstmt.executeUpdate()
                    }
                }
                
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }
    
    override suspend fun loadActiveTabId(): String? {
        return withContext(Dispatchers.IO) {
            connection.prepareStatement("SELECT id FROM agent_tabs WHERE is_active = 1 LIMIT 1").use { pstmt ->
                val resultSet = pstmt.executeQuery()
                if (resultSet.next()) {
                    resultSet.getString("id")
                } else {
                    null
                }
            }
        }
    }
    
    override suspend fun clearAllTabs() {
        withContext(Dispatchers.IO) {
            connection.createStatement().use { stmt ->
                stmt.execute(DELETE_ALL_TABS_SQL)
            }
        }
    }
    
    private fun ResultSet.toTabEntity(): AgentTabEntity {
        return AgentTabEntity(
            id = getString("id"),
            name = getString("name"),
            agentType = getString("agent_type"),
            sessionId = getString("session_id")?.let { java.util.UUID.fromString(it) },
            isActive = getInt("is_active") == 1,
            createdAt = getLong("created_at"),
            lastActiveAt = getLong("last_active_at"),
            projectPath = getString("project_path"),
            unreadCount = getInt("unread_count"),
            hasReceivedFirstPrompt = getInt("has_received_first_prompt") == 1
        )
    }
}
