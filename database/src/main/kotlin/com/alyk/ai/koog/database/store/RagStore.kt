package com.alyk.ai.koog.database.store

/**
 * RAG (Retrieval Augmented Generation) store: document chunks and optional embeddings.
 * Supports metadata filtering and, when embeddings are present, similarity search.
 */
interface RagStore {
    /**
     * Insert a document chunk.
     * @param collection e.g. "project_docs", "codebase"
     * @param content chunk text
     * @param embedding optional embedding vector (e.g. FloatArray from embedding model); null for metadata-only search
     * @param metadata optional key-value metadata for filtering
     * @param sourceId optional source document id
     */
    suspend fun insertChunk(
        collection: String,
        content: String,
        embedding: FloatArray? = null,
        metadata: Map<String, String> = emptyMap(),
        sourceId: String? = null
    ): String

    /**
     * Search chunks by metadata (and optionally by vector similarity when embeddings are stored).
     * @param collection collection name
     * @param metadataFilter optional metadata key-value filter (all must match)
     * @param embeddingQuery optional query embedding for similarity search; ignored if null
     * @param limit max results
     * @return chunks ordered by relevance (when embeddingQuery provided) or by insert order
     */
    suspend fun search(
        collection: String,
        metadataFilter: Map<String, String> = emptyMap(),
        embeddingQuery: FloatArray? = null,
        limit: Int = 10
    ): List<RagChunk>

    /**
     * Delete chunks by collection and optional sourceId.
     */
    suspend fun deleteChunks(collection: String, sourceId: String? = null)

    /**
     * List collection names that have at least one chunk.
     */
    suspend fun listCollections(): List<String>
}

data class RagChunk(
    val id: String,
    val collection: String,
    val content: String,
    val metadata: Map<String, String>,
    val sourceId: String?,
    val score: Float? = null
)
