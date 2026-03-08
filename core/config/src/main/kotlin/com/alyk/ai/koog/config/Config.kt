package com.alyk.ai.koog.config

/**
 * Configuration for the Koog AI system
 */
data class Config(
    val ollamaApiUrl: String = "http://localhost:11434",
    val maxContextLength: Int = 4096,
    val sessionTimeoutMinutes: Int = 30,
    val projectPath: String = ".",
    // Cloud configuration options
    val cloudConfigs: List<CloudConfig> = emptyList(),
    val enableCloudFallback: Boolean = false,
    val cloudFallbackThreshold: Int = 5000, // Response time in ms to trigger cloud fallback
    val preferredCloudProvider: String = "generic"
)

/**
 * Cloud provider configuration
 */
data class CloudConfig(
    val provider: String, // "generic", "ollama_cloud", "hugging_face", "replicate", "anyscale"
    val apiUrl: String,
    val apiKey: String? = null,
    val region: String? = null,
    val maxOutputTokens: Long = 2048L,
    val timeoutMs: Long = 30000L,
    val retryAttempts: Int = 3,
    val enabled: Boolean = true
)

/**
 * Load configuration from environment variables or defaults
 */
object ConfigLoader {
    fun load(): Config {
        val ollamaApiUrl = System.getenv("OLLAMA_API_URL")
            ?: System.getProperty("ollama.api.url")
            ?: "http://localhost:11434"
            
        val maxContextLength = System.getenv("KOOG_MAX_CONTEXT_LENGTH")
            ?.toIntOrNull()
            ?: System.getProperty("koog.max.context.length")
            ?.toIntOrNull()
            ?: 4096
            
        val sessionTimeoutMinutes = System.getenv("KOOG_SESSION_TIMEOUT_MINUTES")
            ?.toIntOrNull()
            ?: System.getProperty("koog.session.timeout.minutes")
            ?.toIntOrNull()
            ?: 30
            
        val projectPath = System.getenv("KOOG_PROJECT_PATH")
            ?: System.getProperty("koog.project.path")
            ?: "."

        // Cloud configuration
        val enableCloudFallback = System.getenv("KOOG_ENABLE_CLOUD_FALLBACK")
            ?.toBooleanStrictOrNull()
            ?: System.getProperty("koog.enable.cloud.fallback")
            ?.toBooleanStrictOrNull()
            ?: false

        val cloudFallbackThreshold = System.getenv("KOOG_CLOUD_FALLBACK_THRESHOLD")
            ?.toIntOrNull()
            ?: System.getProperty("koog.cloud.fallback.threshold")
            ?.toIntOrNull()
            ?: 5000

        val preferredCloudProvider = System.getenv("KOOG_PREFERRED_CLOUD_PROVIDER")
            ?: System.getProperty("koog.preferred.cloud.provider")
            ?: "generic"

        // Load cloud configurations from environment
        val cloudConfigs = loadCloudConfigs()

        return Config(
            ollamaApiUrl = ollamaApiUrl,
            maxContextLength = maxContextLength,
            sessionTimeoutMinutes = sessionTimeoutMinutes,
            projectPath = projectPath,
            cloudConfigs = cloudConfigs,
            enableCloudFallback = enableCloudFallback,
            cloudFallbackThreshold = cloudFallbackThreshold,
            preferredCloudProvider = preferredCloudProvider
        )
    }

    private fun loadCloudConfigs(): List<CloudConfig> {
        val cloudConfigs = mutableListOf<CloudConfig>()
        
        // Load from environment variables in format: KOOG_CLOUD_<PROVIDER>_URL
        val providers = listOf("generic", "ollama_cloud", "hugging_face", "replicate", "anyscale")
        
        providers.forEach { provider ->
            val url = System.getenv("KOOG_CLOUD_${provider.uppercase()}_URL")
                ?: System.getProperty("koog.cloud.${provider.lowercase()}.url")
            
            if (url != null) {
                val apiKey = System.getenv("KOOG_CLOUD_${provider.uppercase()}_API_KEY")
                    ?: System.getProperty("koog.cloud.${provider.lowercase()}.api.key")
                
                val region = System.getenv("KOOG_CLOUD_${provider.uppercase()}_REGION")
                    ?: System.getProperty("koog.cloud.${provider.lowercase()}.region")
                
                cloudConfigs.add(CloudConfig(
                    provider = provider,
                    apiUrl = url,
                    apiKey = apiKey,
                    region = region,
                    enabled = true
                ))
            }
        }
        
        return cloudConfigs
    }
}
