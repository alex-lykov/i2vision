package com.alyk.ai.koog.database.schema

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object ProjectSettingsTable : Table("project_settings") {
    val projectId = varchar("project_id", 512)
    val settingsJson = text("settings_json")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(projectId)
}

object SessionsTable : Table("sessions") {
    val id = varchar("id", 36)
    val projectPath = varchar("project_path", 2048).nullable()
    val conversationHistory = text("conversation_history") // JSON array of strings
    val inputHistory = text("input_history").nullable() // JSON array of strings (terminal prompt history)
    val activeModel = varchar("active_model", 256).nullable()
    val updatedAt = long("updated_at")
    val currentPhase = varchar("current_phase", 256).nullable()
    val currentGoal = text("current_goal").nullable()
    val workflowPhase = varchar("workflow_phase", 256).nullable()
    val completedStepsJson = text("completed_steps").nullable() // JSON array of strings
    val decisionLogJson = text("decision_log").nullable() // JSON array of DecisionLogEntry
    override val primaryKey = PrimaryKey(id)
}

object RagChunksTable : Table("rag_chunks") {
    val id = varchar("id", 64)
    val collection = varchar("collection", 256)
    val content = text("content")
    val embeddingBlob = binary("embedding", 65536).nullable() // up to ~16k floats
    val metadataJson = text("metadata_json")
    val sourceId = varchar("source_id", 512).nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object AgentMemoryTable : Table("agent_memory") {
    val id = varchar("id", 64)
    val concept = varchar("concept", 256)
    val content = text("content")
    val sessionId = varchar("session_id", 36).nullable()
    val agentId = varchar("agent_id", 128).nullable()
    val projectId = varchar("project_id", 512).nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ProjectsTable : Table("projects") {
    val id = varchar("id", 512)
    val name = varchar("name", 512)
    val path = varchar("path", 2048)
    val addedAt = long("added_at")
    val isActive = bool("is_active")
    override val primaryKey = PrimaryKey(id)
}

object PromptCacheTable : Table("prompt_cache") {
    val key = varchar("key", 256)
    val value = text("value")
    val expiresAt = long("expires_at").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(key)
}

/** Loaded/running LLM models (local or cloud). Status persisted so models can be restarted on app boot. */
object LoadedModelsTable : Table("loaded_models") {
    val modelId = varchar("model_id", 256)
    val provider = varchar("provider", 32)  // "local" | "cloud"
    val isRunning = bool("is_running")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(modelId)
}

// --- Terminal Settings Schema ---

/** Stores the canonical definition of all possible settings, generated at build time. */
object TerminalSettingsDefinitions : Table("terminal_settings_definitions") {
    val key = varchar("key", 100)
    val category = varchar("category", 50)
    val description = text("description")
    val defaultValue = bool("default_value")
    val addedInVersion = varchar("added_in_version", 20).nullable()
    val deprecated = bool("deprecated").default(false)
    val deprecatedInVersion = varchar("deprecated_in_version", 20).nullable()
    override val primaryKey = PrimaryKey(key)
}

/** Tracks the current state of settings for each session. */
object TerminalSettingsState : Table("terminal_settings_state") {
    val settingKey = varchar("setting_key", 100) references TerminalSettingsDefinitions.key
    val sessionId = varchar("session_id", 50)
    val userId = varchar("user_id", 50).nullable()
    val enabled = bool("enabled")
    val updatedAt = timestamp("updated_at")
    val updatedBy = varchar("updated_by", 50).nullable()
    override val primaryKey = PrimaryKey(settingKey, sessionId)
}

/** Stores user-created setting profiles/presets. */
object TerminalSettingsProfiles : Table("terminal_settings_profiles") {
    val profileId = varchar("profile_id", 50)
    val profileName = varchar("profile_name", 100)
    val description = text("description").nullable()
    val isDefault = bool("is_default").default(false)
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 50).nullable()
    override val primaryKey = PrimaryKey(profileId)
}

/** A many-to-many link between profiles and the settings they contain. */
object TerminalSettingsProfileSettings : Table("terminal_settings_profile_settings") {
    val profileId = varchar("profile_id", 50) references TerminalSettingsProfiles.profileId
    val settingKey = varchar("setting_key", 100) references TerminalSettingsDefinitions.key
    val enabled = bool("enabled")
    override val primaryKey = PrimaryKey(profileId, settingKey)
}

/** A detailed audit log of all changes made to settings. */
object TerminalSettingsAuditLog : Table("terminal_settings_audit_log") {
    val id = long("id").autoIncrement()
    val settingKey = varchar("setting_key", 100)
    val sessionId = varchar("session_id", 50).nullable()
    val oldValue = bool("old_value").nullable()
    val newValue = bool("new_value")
    val changedAt = timestamp("changed_at")
    val changedBy = varchar("changed_by", 50).nullable()
    val changeReason = varchar("change_reason", 255).nullable()
    override val primaryKey = PrimaryKey(id)
}
