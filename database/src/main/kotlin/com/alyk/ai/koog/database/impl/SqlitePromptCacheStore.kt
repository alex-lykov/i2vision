package com.alyk.ai.koog.database.impl

import com.alyk.ai.koog.database.schema.PromptCacheTable
import com.alyk.ai.koog.database.store.CachedPrompt
import com.alyk.ai.koog.database.store.PromptCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.transactions.transaction

class SqlitePromptCacheStore(private val db: Database) : PromptCacheStore {

    override suspend fun get(key: String): CachedPrompt? = withContext(Dispatchers.IO) {
        transaction(db) {
            PromptCacheTable.select { PromptCacheTable.key eq key }.singleOrNull()?.let { row ->
                val value = row[PromptCacheTable.value]
                val expiresAt = row[PromptCacheTable.expiresAt]
                val cached = CachedPrompt(key = key, value = value, expiresAtMillis = expiresAt)
                if (cached.isExpired) {
                    PromptCacheTable.deleteWhere { PromptCacheTable.key eq key }
                    null
                } else {
                    cached
                }
            }
        }
    }

    override suspend fun put(key: String, value: String, ttlSeconds: Long?) = withContext(Dispatchers.IO) {
        transaction(db) {
            val now = System.currentTimeMillis()
            val expiresAt = ttlSeconds?.let { now + it * 1000 }
            val existing = PromptCacheTable.select { PromptCacheTable.key eq key }.singleOrNull()
            if (existing != null) {
                PromptCacheTable.update({ PromptCacheTable.key eq key }) {
                    it[PromptCacheTable.value] = value
                    it[PromptCacheTable.expiresAt] = expiresAt
                    it[PromptCacheTable.createdAt] = now
                }
            } else {
                PromptCacheTable.insert {
                    it[PromptCacheTable.key] = key
                    it[PromptCacheTable.value] = value
                    it[PromptCacheTable.expiresAt] = expiresAt
                    it[PromptCacheTable.createdAt] = now
                }
            }
            Unit
        }
    }

    override suspend fun invalidate(key: String) = withContext(Dispatchers.IO) {
        transaction(db) {
            PromptCacheTable.deleteWhere { PromptCacheTable.key eq key }
            Unit
        }
    }

    override suspend fun invalidateAll() = withContext(Dispatchers.IO) {
        transaction(db) {
            PromptCacheTable.deleteWhere { PromptCacheTable.key like "%" }
            Unit
        }
    }

    override suspend fun cleanupExpired() = withContext(Dispatchers.IO) {
        transaction(db) {
            val now = System.currentTimeMillis()
            PromptCacheTable.deleteWhere { PromptCacheTable.expiresAt.isNotNull() and (PromptCacheTable.expiresAt less now) }
            Unit
        }
    }
}
