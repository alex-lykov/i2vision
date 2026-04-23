/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.instant.artifact

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.PathMatcher

/**
 * Generic artifact loader - knows NOTHING about VSLFC
 * Just loads files based on configuration
 */
class GenericArtifactLoader(
    private val rootPath: String,
    private val config: ArtifactDiscoveryConfig = ArtifactDiscoveryConfig.defaultVslfc()
) {

    private val log = LoggerFactory.getLogger(GenericArtifactLoader::class.java)

    data class LoadedArtifacts(
        val layers: Map<String, List<Map<String, Any>>> = emptyMap(),
        val metadata: LoadMetadata = LoadMetadata()
    ) {
        fun getLayer(layerName: String): List<Map<String, Any>> = layers[layerName] ?: emptyList()
        fun hasLayer(layerName: String): Boolean = layers.containsKey(layerName)
        fun layerNames(): List<String> = layers.keys.toList()
        fun isEmpty(): Boolean = layers.isEmpty()
    }

    data class LoadMetadata(
        val startTime: Long = System.currentTimeMillis(),
        val filesLoaded: Int = 0,
        val errors: List<String> = emptyList(),
        val cacheDir: String = ""
    ) {
        val durationMs: Long get() = System.currentTimeMillis() - startTime
    }

    /**
     * Load artifacts from a directory
     */
    fun loadFromDirectory(dirPath: String, recursive: Boolean = true): LoadedArtifacts {
        val absolutePath = if (File(dirPath).isAbsolute) dirPath
        else File(rootPath, dirPath).absolutePath
        val targetDir = File(absolutePath)

        if (!targetDir.exists() || !targetDir.isDirectory) {
            log.warn("[ARTIFACT_LOADER] Directory not found: $dirPath")
            return LoadedArtifacts(metadata = LoadMetadata(errors = listOf("Directory not found: $dirPath")))
        }

        log.debug("[ARTIFACT_LOADER] Loading artifacts from: $dirPath")

        val artifacts = mutableMapOf<String, MutableList<Map<String, Any>>>()
        val errors = mutableListOf<String>()
        var filesLoaded = 0

        config.layers.forEach { (layerName, layerConfig) ->
            val layerArtifacts = mutableListOf<Map<String, Any>>()

            layerConfig.patterns.forEach { pattern ->
                val files = findFiles(targetDir, pattern, recursive)

                files.forEach { file ->
                    try {
                        when (val content = loadYamlFile(file)) {
                            is List<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                val items = content.filterIsInstance<Map<String, Any>>()
                                // Add _source field to track filename
                                items.take(layerConfig.maxItems - layerArtifacts.size).forEach { item ->
                                    val mutableItem = item.toMutableMap()
                                    mutableItem["_source"] = file.name
                                    layerArtifacts.add(mutableItem)
                                }
                                filesLoaded++
                            }

                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                val item = content as Map<String, Any>
                                val mutableItem = item.toMutableMap()
                                mutableItem["_source"] = file.name
                                layerArtifacts.add(mutableItem)
                                filesLoaded++
                            }

                            else -> {
                                if (content != null) {
                                    val item = mapOf("_raw" to content.toString(), "_source" to file.name)
                                    layerArtifacts.add(item)
                                    filesLoaded++
                                }
                            }
                        }
                    } catch (e: Exception) {
                        log.error("[ARTIFACT_LOADER] Failed to load ${file.absolutePath}: ${e.message}")
                        errors.add("Failed to load ${file.absolutePath}: ${e.message}")
                    }
                }
            }

            if (layerArtifacts.isNotEmpty()) {
                artifacts[layerName] = layerArtifacts
                log.debug("[ARTIFACT_LOADER] Loaded ${layerArtifacts.size} artifacts for layer: $layerName")
            } else if (layerConfig.required) {
                errors.add("Required layer '$layerName' has no artifacts")
            }
        }

        // Also load default patterns for unmatched files
        if (config.defaultPatterns.isNotEmpty()) {
            val unmatchedArtifacts = mutableListOf<Map<String, Any>>()
            config.defaultPatterns.forEach { pattern ->
                val files = findFiles(targetDir, pattern, recursive)
                files.forEach { file ->
                    // Skip if already loaded by a specific layer
                    val alreadyLoaded = artifacts.values.any { layer ->
                        layer.any { it["_source"] == file.absolutePath }
                    }
                    if (!alreadyLoaded) {
                        try {
                            when (val content = loadYamlFile(file)) {
                                is List<*> -> {
                                    @Suppress("UNCHECKED_CAST")
                                    unmatchedArtifacts.addAll(content.filterIsInstance<Map<String, Any>>())
                                }

                                is Map<*, *> -> {
                                    @Suppress("UNCHECKED_CAST")
                                    unmatchedArtifacts.add(content as Map<String, Any>)
                                }
                            }
                            filesLoaded++
                        } catch (e: Exception) {
                            log.error("[ARTIFACT_LOADER] Failed to load ${file.absolutePath}: ${e.message}")
                            errors.add("Failed to load ${file.absolutePath}: ${e.message}")
                        }
                    }
                }
            }
            if (unmatchedArtifacts.isNotEmpty()) {
                artifacts["_unmatched"] = unmatchedArtifacts
            }
        }

        log.info("[ARTIFACT_LOADER] Loaded $filesLoaded files from $dirPath in ${LoadMetadata(startTime = System.currentTimeMillis()).durationMs}ms")

        return LoadedArtifacts(
            layers = artifacts,
            metadata = LoadMetadata(
                filesLoaded = filesLoaded,
                errors = errors,
                cacheDir = targetDir.absolutePath
            )
        )
    }

    /**
     * Find files matching a pattern
     */
    private fun findFiles(directory: File, pattern: String, recursive: Boolean): List<File> {
        // Simplified: if pattern is **/*.yaml or *.yaml, just match by extension
        val matchExtension = pattern.contains("*.yaml") || pattern.contains("*.yml")

        return directory.walk()
            .maxDepth(if (recursive) Int.MAX_VALUE else 1)
            .filter { it.isFile }
            .filter { file ->
                if (matchExtension) {
                    file.extension in listOf("yaml", "yml")
                } else {
                    file.name == pattern
                }
            }
            .toList()
    }

    /**
     * Create a glob pattern matcher
     */
    private fun createGlobMatcher(baseDir: File, pattern: String): PathMatcher {
        val basePath = baseDir.toPath()
        // For recursive patterns like **/*.yaml, use glob syntax
        val fullPattern = if (pattern.contains("**")) {
            "$basePath/$pattern".replace('\\', '/')
        } else {
            "$basePath/$pattern".replace('\\', '/')
        }
        return FileSystems.getDefault().getPathMatcher("glob:$fullPattern")
    }

    /**
     * Load and parse YAML file
     */
    private fun loadYamlFile(file: File): Any? {
        return try {
            val yaml = Yaml()
            val content: Any? = yaml.load(file.readText())

            // Add source metadata to maps
            when (content) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    (content as MutableMap<String, Any>).putIfAbsent("_source", file.absolutePath)
                }

                is List<*> -> {
                    content.filterIsInstance<MutableMap<String, Any>>().forEach { item ->
                        item.putIfAbsent("_source", file.absolutePath)
                    }
                }
            }
            content
        } catch (e: Exception) {
            throw RuntimeException("Failed to parse YAML: ${file.absolutePath}", e)
        }
    }

    /**
     * Get all available layers in a directory (without loading content)
     */
    fun discoverLayers(dirPath: String): List<String> {
        val absolutePath = if (File(dirPath).isAbsolute) dirPath
        else File(rootPath, dirPath).absolutePath
        val targetDir = File(absolutePath)

        if (!targetDir.exists() || !targetDir.isDirectory) return emptyList()

        val layers = mutableSetOf<String>()

        config.layers.keys.forEach { layerName ->
            val layerDir = File(targetDir, layerName)
            if (layerDir.exists() && layerDir.isDirectory && layerDir.listFiles()?.isNotEmpty() == true) {
                layers.add(layerName)
            }
        }

        // Also check for directories that might be layers not in config
        targetDir.listFiles()
            ?.filter { it.isDirectory && !config.layers.containsKey(it.name) }
            ?.forEach { layers.add(it.name) }

        return layers.toList()
    }
}
