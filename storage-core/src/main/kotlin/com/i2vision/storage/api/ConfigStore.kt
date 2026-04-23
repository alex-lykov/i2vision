package com.i2vision.storage.api

/**
 * Public API for configuration storage.
 * Stores agent configurations and presets.
 */
interface ConfigStore {

    /**
     * Store agent configuration.
     */
    suspend fun putConfig(key: String, config: String): Boolean

    /**
     * Get agent configuration.
     */
    suspend fun getConfig(key: String): String?

    /**
     * List all configuration keys.
     */
    suspend fun listConfigs(): List<String>

    /**
     * Delete configuration.
     */
    suspend fun deleteConfig(key: String): Boolean
}
