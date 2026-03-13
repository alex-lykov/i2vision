package com.alyk.ai.koog.database

import com.alyk.ai.koog.database.impl.*
import com.alyk.ai.koog.database.schema.*
import com.alyk.ai.koog.database.settings.TerminalSettingKey
import com.alyk.ai.koog.database.settings.TerminalSettingsRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
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
                // Drop the legacy table if it exists to ensure a clean slate
                exec("DROP TABLE IF EXISTS terminal_settings;")
                log.info("Dropped legacy 'terminal_settings' table if it existed.")

                // Create all tables
                SchemaUtils.create(
                    ProjectSettingsTable,
                    SessionsTable,
                    RagChunksTable,
                    AgentMemoryTable,
                    ProjectsTable,
                    PromptCacheTable,
                    LoadedModelsTable,
                    // New Terminal Settings Tables
                    TerminalSettingsDefinitions,
                    TerminalSettingsState,
                    TerminalSettingsProfiles,
                    TerminalSettingsProfileSettings,
                    TerminalSettingsAuditLog
                )

                // Populate the definitions table from the generated enum if it's empty
                if (TerminalSettingsDefinitions.selectAll().count() == 0L) {
                    val allSettings = TerminalSettingKey.allSettings
                    
                    TerminalSettingsDefinitions.batchInsert(allSettings) { setting ->
                        this[TerminalSettingsDefinitions.key] = setting.key
                        this[TerminalSettingsDefinitions.category] = setting.category.displayName
                        this[TerminalSettingsDefinitions.description] = setting.description
                        this[TerminalSettingsDefinitions.defaultValue] = setting.defaultValue
                    }
                    log.info("Populated terminal_settings_definitions with ${allSettings.size} entries.")
                }
            }
            log.info("Database schema created/verified")
        }
    }

    override val projectSettings = SqliteProjectSettingsStore(db)
    override val agentState = SqliteAgentStateStore(db)
    override val rag = SqliteRagStore(db)
    override val memory = SqliteAgentMemoryStore(db)
    override val promptCache = SqlitePromptCacheStore(db)
    override val projects = SqliteProjectsStore(db)
    override val loadedModels = SqliteLoadedModelsStore(db)

    override fun terminalSettingsRepository(sessionId: String, userId: String?): TerminalSettingsRepository =
        TerminalSettingsRepository(db, sessionId, userId)

    override fun close() {
        log.debug("Database provider close requested (SQLite in-process, no pool to close)")
    }
}
