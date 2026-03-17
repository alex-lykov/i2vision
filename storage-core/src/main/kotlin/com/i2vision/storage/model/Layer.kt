package com.i2vision.storage.model

import kotlinx.serialization.Serializable

/**
 * VSLFC layer enumeration.
 * Represents the five layers of the VSLFC architecture.
 */
@Serializable
enum class Layer {
    VISION,
    STRUCTURE,
    LOGIC,
    FLOW,
    CODE
}
