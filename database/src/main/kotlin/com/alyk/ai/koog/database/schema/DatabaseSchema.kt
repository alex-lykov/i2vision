package com.alyk.ai.koog.database.schema

import org.jetbrains.exposed.sql.Table

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
    val activeModel = varchar("active_model", 256).nullable()
    val updatedAt = long("updated_at")
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
