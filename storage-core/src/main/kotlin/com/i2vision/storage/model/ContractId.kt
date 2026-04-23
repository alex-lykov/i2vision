/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.model

/**
 * Identifier for a contract between VSLFC layers.
 */
data class ContractId(
    val sourceLayer: Layer,
    val targetLayer: Layer,
    val name: String
)
