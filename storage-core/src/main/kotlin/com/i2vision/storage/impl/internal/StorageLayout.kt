/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.impl.internal

import com.i2vision.storage.I2VisionPaths
import java.io.File

/**
 * INTERNAL ONLY. This file contains all path knowledge.
 * NO OTHER MODULE SHOULD IMPORT THIS.
 * 
 * This is the single source of truth for all physical storage paths.
 * Changes to storage layout should only happen here.
 */
internal object StorageLayout {

    // Vision AI paths (contract definitions)
    const val VISION_AI = ".vision-ai"

    /**
     * Get the contract definitions directory for a layer.
     */
    fun contractDefinitionsDir(layer: String): String =
        "$VISION_AI/.${layer.lowercase()}/contracts"

    /**
     * Get a specific contract definition path.
     */
    fun contractDefinitionPath(layer: String, filename: String): String =
        "${contractDefinitionsDir(layer)}/$filename"

    /**
     * Get the contract artifacts directory for a layer.
     * Uses I2VisionPaths for project-specific cache location.
     */
    fun contractArtifactsDir(projectRoot: File, layer: String): File {
        val cacheDir = I2VisionPaths.getProjectCacheDir(projectRoot.absolutePath)
        return File(cacheDir, "contracts/${layer.lowercase()}").apply { mkdirs() }
    }
}
