package com.alyk.ai.koog.database.impl

import com.alyk.ai.koog.database.schema.LoadedModelsTable
import com.alyk.ai.koog.database.store.LoadedModelsStore
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

class SqliteLoadedModelsStore(private val db: org.jetbrains.exposed.sql.Database) : LoadedModelsStore {

    private val log = LoggerFactory.getLogger(SqliteLoadedModelsStore::class.java)

    override fun getRunning(): List<Pair<String, String>> = transaction(db) {
        LoadedModelsTable.select { LoadedModelsTable.isRunning eq true }
            .map { it[LoadedModelsTable.modelId] to it[LoadedModelsTable.provider] }
    }

    override fun setRunning(modelId: String, provider: String) = transaction(db) {
        val now = System.currentTimeMillis()
        val existing = LoadedModelsTable.select { LoadedModelsTable.modelId eq modelId }.singleOrNull()
        if (existing != null) {
            LoadedModelsTable.update({ LoadedModelsTable.modelId eq modelId }) {
                it[LoadedModelsTable.provider] = provider
                it[LoadedModelsTable.isRunning] = true
                it[LoadedModelsTable.updatedAt] = now
            }
        } else {
            LoadedModelsTable.insert {
                it[LoadedModelsTable.modelId] = modelId
                it[LoadedModelsTable.provider] = provider
                it[LoadedModelsTable.isRunning] = true
                it[LoadedModelsTable.updatedAt] = now
            }
        }
        log.debug("Set model running: modelId={} provider={}", modelId, provider)
    }

    override fun setStopped(modelId: String) = transaction(db) {
        val now = System.currentTimeMillis()
        LoadedModelsTable.update({ LoadedModelsTable.modelId eq modelId }) {
            it[LoadedModelsTable.isRunning] = false
            it[LoadedModelsTable.updatedAt] = now
        }
        if (LoadedModelsTable.select { LoadedModelsTable.modelId eq modelId }.count() > 0L) {
            log.debug("Set model stopped: modelId={}", modelId)
        }
    }
}
