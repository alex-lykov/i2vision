package com.i2vision.storage.model

import kotlinx.serialization.Serializable

/**
 * A VSLFC artifact with content and metadata.
 */
data class Artifact(
    val ref: ArtifactRef,
    val content: ByteArray,
    val metadata: ArtifactMetadata
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Artifact

        if (ref != other.ref) return false
        if (!content.contentEquals(other.content)) return false
        if (metadata != other.metadata) return false

        return true
    }

    override fun hashCode(): Int {
        var result = ref.hashCode()
        result = 31 * result + content.contentHashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}

/**
 * Metadata about an artifact.
 */
@Serializable
data class ArtifactMetadata(
    val createdAt: Long,  // Epoch milliseconds
    val sourceFiles: List<String>,
    val hash: String,
    val confidence: Double? = null
)
