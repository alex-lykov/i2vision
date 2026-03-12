package com.alyk.ai.koog.database

import com.alyk.ai.koog.database.impl.*
import com.alyk.ai.koog.database.schema.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File

/**
 * SQLite-backed implementation of [DatabaseProvider].
 * Uses a single SQLite file (default: `~/.koog/koog.db`).
 */
class SqliteDatabaseProvider(
    private val dbPath: File = File(System.getProperty("user.home"), ".koog/koog.db")
) : DatabaseProvider {

    private val log = LoggerFactory.getLogger(SqliteDatabaseProvider::class.java)

    private val db: Database by lazy {
        log.info("Initializing SQLite database at {}", dbPath.absolutePath)
        dbPath.parentFile?.mkdirs()
        Database.connect(
            "jdbc:sqlite:${dbPath.absolutePath}",
            driver = "org.sqlite.JDBC"
        ).also { database ->
            transaction(database) {
                SchemaUtils.create(
                    ProjectSettingsTable,
                    SessionsTable,
                    RagChunksTable,
                    AgentMemoryTable,
                    ProjectsTable,
                    PromptCacheTable,
                    LoadedModelsTable
                )
            }
            log.info("Database schema created (project_settings, sessions, rag_chunks, agent_memory, projects, prompt_cache, loaded_models)")
        }
    }

    override val projectSettings = SqliteProjectSettingsStore(db)
    override val agentState = SqliteAgentStateStore(db)
    override val rag = SqliteRagStore(db)
    override val memory = SqliteAgentMemoryStore(db)
    override val promptCache = SqlitePromptCacheStore(db)
    override val projects = SqliteProjectsStore(db)
    override val loadedModels = SqliteLoadedModelsStore(db)

    override fun close() {
        log.debug("Database provider close requested (SQLite in-process, no pool to close)")
    }
}
