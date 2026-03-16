package com.alyk.ai.koog.database.schema

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Table for storing agent tabs
 */
object AgentTabsTable : Table("agent_tabs") {
    val id = text("id").uniqueIndex()
    val name = text("name")
    val agentType = text("agent_type")
    val sessionId = text("session_id").nullable()
    val isActive = bool("is_active").default(false)
    val createdAt = timestamp("created_at")
    val lastActiveAt = timestamp("last_active_at")
    val projectPath = text("project_path").nullable()
    val unreadCount = integer("unread_count").default(0)
    val hasReceivedFirstPrompt = bool("has_received_first_prompt").default(false)
    
    override val primaryKey = PrimaryKey(id)
}
