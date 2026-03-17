package com.i2vision.storage.model

/**
 * Identifier for a contract between VSLFC layers.
 */
data class ContractId(
    val sourceLayer: Layer,
    val targetLayer: Layer,
    val name: String
)
