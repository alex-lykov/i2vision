package com.alyk.ai.koog.database.impl

import com.alyk.ai.koog.database.schema.RagChunksTable
import com.alyk.ai.koog.database.store.RagChunk
import com.alyk.ai.koog.database.store.RagStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.ByteBuffer
import java.util.*

class SqliteRagStore(private val db: Database) : RagStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun insertChunk(
        collection: String,
        content: String,
        embedding: FloatArray?,
        metadata: Map<String, String>,
        sourceId: String?
    ): String = withContext(Dispatchers.IO) {
        transaction(db) {
            val id = "chunk_${UUID.randomUUID().toString().replace("-", "").take(24)}"
            RagChunksTable.insert {
                it[RagChunksTable.id] = id
                it[RagChunksTable.collection] = collection
                it[RagChunksTable.content] = content
                it[RagChunksTable.embeddingBlob] = embedding?.toByteArray()
                it[RagChunksTable.metadataJson] = metadataToJson(metadata)
                it[RagChunksTable.sourceId] = sourceId
                it[RagChunksTable.createdAt] = System.currentTimeMillis()
            }
            id
        }
    }

    override suspend fun search(
        collection: String,
        metadataFilter: Map<String, String>,
        embeddingQuery: FloatArray?,
        limit: Int
    ): List<RagChunk> = withContext(Dispatchers.IO) {
        transaction(db) {
            if (metadataFilter.isNotEmpty()) {
                val rows = RagChunksTable.select { RagChunksTable.collection eq collection }
                    .limit(limit * 4)
                    .toList()
                val filtered = rows.filter { row ->
                    val meta = jsonToMetadata(row[RagChunksTable.metadataJson])
                    metadataFilter.all { (k, v) -> meta[k] == v }
                }.take(limit)
                if (embeddingQuery == null) return@transaction filtered.map { it.toChunk() }
                // With embedding: load chunks that have embedding, compute cosine similarity, sort
                val withScores = filtered.mapNotNull { row ->
                    val blob = row[RagChunksTable.embeddingBlob] ?: return@mapNotNull null
                    val stored = blob.toFloatArray()
                    val score = cosineSimilarity(embeddingQuery, stored)
                    row.toChunk(score)
                }
                withScores.sortedByDescending { it.score!! }.take(limit)
            } else {
                val rows = RagChunksTable.select { RagChunksTable.collection eq collection }
                    .orderBy(RagChunksTable.createdAt, SortOrder.DESC)
                    .limit(limit)
                    .toList()
                if (embeddingQuery == null) return@transaction rows.map { it.toChunk() }
                val withScores = rows.mapNotNull { row ->
                    val blob = row[RagChunksTable.embeddingBlob] ?: return@mapNotNull row.toChunk()
                    val stored = blob.toFloatArray()
                    val score = cosineSimilarity(embeddingQuery, stored)
                    row.toChunk(score)
                }
                withScores.sortedByDescending { it.score!! }.take(limit)
            }
        }
    }

    override suspend fun deleteChunks(collection: String, sourceId: String?) = withContext(Dispatchers.IO) {
        transaction(db) {
            if (sourceId != null) {
                RagChunksTable.deleteWhere {
                    RagChunksTable.collection eq collection and (RagChunksTable.sourceId eq sourceId)
                }
            } else {
                RagChunksTable.deleteWhere { RagChunksTable.collection eq collection }
            }
            Unit
        }
    }

    override suspend fun listCollections(): List<String> = withContext(Dispatchers.IO) {
        transaction(db) {
            RagChunksTable.select(RagChunksTable.collection).map { it[RagChunksTable.collection] }.distinct()
        }
    }

    private fun metadataToJson(metadata: Map<String, String>): String =
        buildJsonObject { metadata.forEach { put(it.key, it.value) } }.toString()

    private fun jsonToMetadata(str: String): Map<String, String> =
        json.parseToJsonElement(str).jsonObject.entries.associate { it.key to it.value.jsonPrimitive.content }

    private fun org.jetbrains.exposed.sql.ResultRow.toChunk(score: Float? = null): RagChunk {
        val meta = jsonToMetadata(this[RagChunksTable.metadataJson])
        return RagChunk(
            id = this[RagChunksTable.id],
            collection = this[RagChunksTable.collection],
            content = this[RagChunksTable.content],
            metadata = meta,
            sourceId = this[RagChunksTable.sourceId],
            score = score
        )
    }

    private fun FloatArray.toByteArray(): ByteArray {
        val buf = ByteBuffer.allocate(size * 4)
        forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun ByteArray.toFloatArray(): FloatArray {
        val buf = ByteBuffer.wrap(this)
        return FloatArray(size / 4) { buf.getFloat() }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom <= 0f) 0f else dot / denom
    }
}
