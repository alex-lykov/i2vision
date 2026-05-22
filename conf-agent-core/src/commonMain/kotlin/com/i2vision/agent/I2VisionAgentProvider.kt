/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent

/**
 * Factory interface for creating and managing i2vision agents.
 * 
 * The provider pattern enables:
 * - Switching between local TypeScript agents and remote Kotlin agents
 * - Configuration-based agent selection
 * - Health checking before agent creation
 * - Resource lifecycle management
 * 
 * ## Implementation Types
 * 
 * 1. **LocalAgentProvider** - Creates LocalTypeScriptAgent instances for development
 * 2. **KotlinAgentProvider** - Creates KoogI2VisionAgent instances for production
 * 3. **BridgedAgentProvider** - Creates BridgedAgent instances communicating via JSON-RPC
 * 
 * ## Usage Example
 * 
 * ```kotlin
 * // Get a provider based on configuration
 * val provider = AgentProviderFactory.create(config)
 * 
 * // Check provider health
 * val isHealthy = provider.ping()
 * if (!isHealthy) {
 *     throw AgentProviderException("Provider is not healthy")
 * }
 * 
 * // List available configurations
 * val configs = provider.listConfigurations()
 * println("Available configs: ${configs.size}")
 * 
 * // Create an agent for a specific layer
 * val agent = provider.createAgent(VslfcLayer.CODE, config)
 * println("Created agent: ${agent.displayName}")
 * 
 * // Use the agent
 * val response = agent.process(request)
 * 
 * // Clean up
 * provider.dispose()
 * ```
 * 
 * ## Lifecycle
 * 
 * 1. **Initialize** - Provider is created and initialized
 * 2. **Health Check** - Verify provider is operational via ping()
 * 3. **List Configs** - Discover available agent configurations
 * 4. **Create Agents** - Create agent instances as needed
 * 5. **Dispose** - Clean up all resources when done
 */
interface I2VisionAgentProvider {
    
    /**
     * Unique provider identifier.
     * 
     * Format: "provider-{type}-{timestamp}"
     * Example: "provider-kotlin-1234567890"
     */
    val id: String
    
    /**
     * Provider type name.
     * 
     * Examples: "local", "kotlin", "bridged", "remote"
     */
    val type: String
    
    /**
     * Human-readable display name.
     * 
     * Example: "Kotlin Agent Provider (Koog)"
     */
    val displayName: String
    
    /**
     * Check if the provider is healthy and operational.
     * 
     * Should be called before creating agents to ensure
     * the provider can fulfill requests.
     * 
     * @return true if healthy, false otherwise
     */
    suspend fun ping(): Boolean
    
    /**
     * List all available agent configurations.
     * 
     * Configurations define:
     * - Model settings (provider, ID, context length)
     * - Iteration limits and timeouts
     * - Tool availability
     * - Layer support
     * 
     * @return List of available configurations
     */
    suspend fun listConfigurations(): List<AgentConfigSummary>
    
    /**
     * Create a new agent instance for the specified layer.
     * 
     * The agent is created with the specified configuration.
     * If no configuration is provided, the default configuration is used.
     * 
     * @param layer The VSLFC layer the agent should specialize in
     * @param config Optional configuration (uses default if not provided)
     * @return A new agent instance
     * @throws AgentProviderException if the agent cannot be created
     * @throws IllegalArgumentException if the layer is not supported
     */
    suspend fun createAgent(
        layer: VslfcLayer,
        config: AgentConfig? = null
    ): I2VisionAgent
    
    /**
     * Create a new agent instance with a specific configuration ID.
     * 
     * Configuration IDs are returned from listConfigurations().
     * This is a convenience method for selecting pre-defined configurations.
     * 
     * @param layer The VSLFC layer the agent should specialize in
     * @param configurationId The configuration ID to use
     * @return A new agent instance
     * @throws AgentProviderException if the configuration ID is not found
     * @throws IllegalArgumentException if the layer is not supported
     */
    suspend fun createAgent(
        layer: VslfcLayer,
        configurationId: String
    ): I2VisionAgent
    
    /**
     * Dispose of all resources managed by this provider.
     * 
     * Should be called when the provider is no longer needed.
     * After disposal:
     * - All agents created by this provider should be disposed
     * - Network connections should be closed
     * - File handles should be released
     * - The provider should not be used for new agent creation
     */
    suspend fun dispose()
}

/**
 * Lightweight metadata about an available agent configuration.
 * 
 * Used for UI display and configuration selection.
 * 
 * @property id Unique configuration identifier
 * @property name Human-readable configuration name
 * @property description Configuration description
 * @property modelProvider Model provider (e.g., "Ollama", "OpenAI")
 * @property modelId Model identifier (e.g., "llama3.2:3b", "gpt-4")
 * @property supportedLayers VSLFC layers supported by this configuration
 * @property maxContextTokens Maximum context window in tokens
 * @property isDefault Whether this is the default configuration
 */
data class AgentConfigSummary(
    val id: String,
    val name: String,
    val description: String,
    val modelProvider: String,
    val modelId: String,
    val supportedLayers: List<VslfcLayer>,
    val maxContextTokens: Long,
    val isDefault: Boolean = false
) {
    /**
     * Check if this configuration supports a specific layer.
     */
    fun supportsLayer(layer: VslfcLayer): Boolean = layer in supportedLayers
    
    /**
     * Create a display string for UI.
     */
    fun toDisplayString(): String =
        "$name ($modelProvider $modelId) - ${maxContextTokens} tokens"
}

/**
 * Exception thrown when agent provider operations fail.
 * 
 * @param message Error message
 * @param cause Underlying cause (if any)
 * @param errorCode Error code for programmatic handling
 */
class AgentProviderException(
    message: String,
    cause: Throwable? = null,
    val errorCode: String = "PROVIDER_ERROR"
) : Exception(message, cause)

/**
 * Create a display name for a provider based on type.
 */
fun createProviderDisplayName(type: String, details: String = ""): String =
    buildString {
        append(type.replaceFirstChar { it.uppercase() })
        append(" Agent Provider")
        if (details.isNotEmpty()) {
            append(" ($details)")
        }
    }

/**
 * Generate a unique provider ID.
 * 
 * Format: "provider-{type}-{timestamp}"
 */
fun generateProviderId(type: String): String =
    "provider-${type}-${System.currentTimeMillis()}"
