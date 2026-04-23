/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.impl.internal

/**
 * INTERNAL ONLY. This file contains all path knowledge.
 * NO OTHER MODULE SHOULD IMPORT THIS.
 * 
 * This is the single source of truth for all physical storage paths.
 * Changes to storage layout should only happen here.
 */
internal object StorageLayout {

    // Semantic cache paths - now in user home directory
    // Note: SEMANTIC_CACHE is deprecated - use I2VisionPaths.getProjectCacheDir(projectPath) instead
    @Deprecated("Use I2VisionPaths.getProjectCacheDir(projectPath) instead")
    val SEMANTIC_CACHE: String = ".semantic-cache"
    private val TOOLS_DIR: String get() = "$SEMANTIC_CACHE/.tools"

    // Vision AI paths (contract definitions)
    const val VISION_AI = ".vision-ai"

    // Contract paths
    private val CONTRACT_REGISTRY: String get() = "$SEMANTIC_CACHE/contracts/registry.yaml"
    private val CONTRACT_ARTIFACTS: String get() = "$SEMANTIC_CACHE/contracts"

    /**
     * Get the cache directory for a module.
     */
    fun moduleCacheDir(module: String): String = "$SEMANTIC_CACHE/$module"

    /**
     * Get the layer directory for a module.
     */
    fun layerDir(module: String, layer: String): String =
        "$SEMANTIC_CACHE/$module/${layer.lowercase()}"

    /**
     * Get the artifact path for a module/layer/name.
     */
    fun artifactPath(module: String, layer: String, name: String): String =
        "${layerDir(module, layer)}/$name"

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
     */
    fun contractArtifactsDir(layer: String): String =
        "$CONTRACT_ARTIFACTS/${layer.lowercase()}"

    /**
     * Get cross-module flows path.
     */
    fun crossModuleFlowsPath(): String = "$TOOLS_DIR/cross-module-flows.yaml"

    /**
     * Get cross-module imports path.
     */
    fun crossModuleImportsPath(): String = "$TOOLS_DIR/cross-module-imports.yaml"

    /**
     * Get complexity artifact path for a module.
     */
    fun complexityPath(module: String): String =
        "${layerDir(module, "code")}/complexity.yaml"

    /**
     * Get docs directory for a module.
     */
    fun docsDir(module: String): String = "$SEMANTIC_CACHE/$module/docs"

    /**
     * Get metadata file path for an artifact.
     */
    fun metadataPath(artifactPath: String): String = "$artifactPath.meta"
}
