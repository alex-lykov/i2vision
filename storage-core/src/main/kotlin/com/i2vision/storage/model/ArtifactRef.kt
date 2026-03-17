package com.i2vision.storage.model

import kotlinx.serialization.Serializable

/**
 * Reference to a VSLFC artifact.
 * Used by storage clients to identify artifacts without knowing physical paths.
 */
@Serializable
data class ArtifactRef(
    val module: String,
    val layer: Layer,
    val name: String
)
