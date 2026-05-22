/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.yaml.Yaml
import java.io.File

/**
 * Loads agent configurations from YAML files.
 * 
 * Supports loading from:
 * - File system paths
 * - Classpath resources
 * - Directories (batch loading)
 * - Default configurations per VSLFC layer
 * 
 * Example usage:
 * ```kotlin
 * // Load from file
 * val config = YamlConfigLoader.load("/path/to/coding-agent.yaml")
 * 
 * // Load from resources
 * val config = YamlConfigLoader.loadFromResources("configs/code-agent.yaml")
 * 
 * // Load all from directory
 * val configs = YamlConfigLoader.loadAllFromDirectory("/path/to/configs")
 * 
 * // Get default for layer
 * val config = YamlConfigLoader.getDefaultForLayer(VslfcLayer.CODE)
 * ```
 */
object YamlConfigLoader {
    
    private val yamlFormat = Yaml {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
    
    /**
     * Load configuration from a YAML file.
     * 
     * @param path Absolute or relative path to the YAML file
     * @return Loaded configuration
     * @throws ConfigLoadException if the file cannot be read or parsed
     */
    suspend fun load(path: String): AgentPromptConfiguration {
        try {
            val file = File(path)
            if (!file.exists()) {
                throw ConfigLoadException("Configuration file not found: $path")
            }
            
            val content = file.readText()
            return parseYaml(content, path)
        } catch (e: ConfigLoadException) {
            throw e
        } catch (e: Exception) {
            throw ConfigLoadException("Failed to load configuration from $path: ${e.message}", e)
        }
    }
    
    /**
     * Load configuration from classpath resources.
     * 
     * @param resourceName Resource path (e.g., "configs/code-agent.yaml")
     * @return Loaded configuration
     * @throws ConfigLoadException if the resource cannot be found or parsed
     */
    suspend fun loadFromResources(resourceName: String): AgentPromptConfiguration {
        try {
            val inputStream = YamlConfigLoader::class.java.classLoader.getResourceAsStream(resourceName)
                ?: throw ConfigLoadException("Resource not found: $resourceName")
            
            val content = inputStream.bufferedReader().readText()
            return parseYaml(content, "resource:$resourceName")
        } catch (e: ConfigLoadException) {
            throw e
        } catch (e: Exception) {
            throw ConfigLoadException("Failed to load resource $resourceName: ${e.message}", e)
        }
    }
    
    /**
     * Load all configurations from a directory.
     * 
     * @param directory Path to the directory containing YAML files
     * @param recursive Whether to search subdirectories
     * @return List of loaded configurations
     * @throws ConfigLoadException if the directory cannot be read
     */
    suspend fun loadAllFromDirectory(
        directory: String,
        recursive: Boolean = false
    ): List<AgentPromptConfiguration> {
        try {
            val dir = File(directory)
            if (!dir.exists() || !dir.isDirectory) {
                throw ConfigLoadException("Directory not found: $directory")
            }
            
            val yamlFiles = if (recursive) {
                dir.walkTopDown().filter { it.isFile && it.extension == "yaml" }.toList()
            } else {
                dir.listFiles { f -> f.isFile && f.extension == "yaml" }?.toList() ?: emptyList()
            }
            
            return yamlFiles.mapNotNull { file ->
                try {
                    load(file.absolutePath)
                } catch (e: Exception) {
                    System.err.println("Failed to load ${file.absolutePath}: ${e.message}")
                    null
                }
            }
        } catch (e: ConfigLoadException) {
            throw e
        } catch (e: Exception) {
            throw ConfigLoadException("Failed to load configurations from $directory: ${e.message}", e)
        }
    }
    
    /**
     * Get the default configuration for a specific VSLFC layer.
     * 
     * @param layer The VSLFC layer
     * @return Default configuration for that layer
     */
    fun getDefaultForLayer(layer: com.i2vision.agent.VslfcLayer): AgentPromptConfiguration =
        DefaultConfigs.forLayer(layer)
    
    /**
     * Parse YAML content into a configuration object.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun parseYaml(content: String, source: String): AgentPromptConfiguration {
        try {
            val config = yamlFormat.decodeFromString<AgentPromptConfiguration>(content)
            
            // Validate required fields
            val errors = AgentPromptConfiguration.validate(config)
            if (errors.isNotEmpty()) {
                throw ConfigLoadException("Invalid configuration from $source: ${errors.joinToString(", ")}")
            }
            
            return config
        } catch (e: Exception) {
            throw ConfigLoadException("Failed to parse YAML from $source: ${e.message}", e)
        }
    }
    
    /**
     * Save a configuration to a YAML file.
     * 
     * @param config Configuration to save
     * @param path Path to the output file
     */
    suspend fun save(config: AgentPromptConfiguration, path: String) {
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            
            val content = yamlFormat.encodeToString(config)
            file.writeText(content)
        } catch (e: Exception) {
            throw ConfigLoadException("Failed to save configuration to $path: ${e.message}", e)
        }
    }
}

/**
 * Exception thrown when configuration loading fails.
 * 
 * @param message Error message
 * @param cause Underlying cause (if any)
 */
class ConfigLoadException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
