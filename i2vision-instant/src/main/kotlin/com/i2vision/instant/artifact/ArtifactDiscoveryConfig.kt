package com.i2vision.instant.artifact

import org.yaml.snakeyaml.Yaml

/**
 * Configuration for artifact discovery - completely decoupled from VSLFC
 * Can be loaded from YAML/JSON at runtime
 */
data class ArtifactDiscoveryConfig(
    val layers: Map<String, LayerConfig> = emptyMap(),
    val defaultPatterns: List<String> = listOf("*.yaml", "*.yml")
) {
    data class LayerConfig(
        val patterns: List<String> = emptyList(),      // File patterns to match
        val required: Boolean = false,                  // Is this layer required?
        val maxItems: Int = 100,                        // Max items to load
        val mergeStrategy: MergeStrategy = MergeStrategy.APPEND
    )

    enum class MergeStrategy {
        APPEND,     // Add all artifacts
        REPLACE,    // Replace with newest
        MERGE       // Smart merge by ID
    }

    companion object {
        /**
         * Default VSLFC configuration - can be overridden
         */
        fun defaultVslfc(): ArtifactDiscoveryConfig = ArtifactDiscoveryConfig(
            layers = mapOf(
                "vision" to LayerConfig(
                    patterns = listOf("**/*.yaml", "**/*.yml"),
                    required = false,
                    maxItems = 50
                ),
                "structure" to LayerConfig(
                    patterns = listOf("**/*.yaml", "**/*.yml"),
                    required = false,
                    maxItems = 100
                ),
                "logic" to LayerConfig(
                    patterns = listOf("**/*.yaml", "**/*.yml"),
                    required = false,
                    maxItems = 100
                ),
                "flow" to LayerConfig(
                    patterns = listOf("**/*.yaml", "**/*.yml"),
                    required = false,
                    maxItems = 100
                ),
                "code" to LayerConfig(
                    patterns = listOf("**/*.yaml", "**/*.yml"),
                    required = false,
                    maxItems = 10000
                )
            )
        )

        /**
         * Generic configuration - loads everything
         */
        fun generic(): ArtifactDiscoveryConfig = ArtifactDiscoveryConfig(
            layers = mapOf(
                "all" to LayerConfig(
                    patterns = listOf("**/*.yaml", "**/*.yml"),
                    required = false,
                    maxItems = 1000
                )
            )
        )

        /**
         * Load from YAML file
         */
        fun fromYaml(content: String): ArtifactDiscoveryConfig {
            val yaml = Yaml()

            @Suppress("UNCHECKED_CAST")
            val map = yaml.load(content) as? Map<String, Any> ?: return defaultVslfc()

            val layersMap = mutableMapOf<String, LayerConfig>()
            (map["layers"] as? Map<String, Any>)?.forEach { (layerName, layerConfig) ->
                @Suppress("UNCHECKED_CAST")
                val config = layerConfig as? Map<String, Any> ?: return@forEach

                layersMap[layerName] = LayerConfig(
                    patterns = (config["patterns"] as? List<String>) ?: emptyList(),
                    required = (config["required"] as? Boolean) ?: false,
                    maxItems = (config["maxItems"] as? Number)?.toInt() ?: 100,
                    mergeStrategy = when ((config["mergeStrategy"] as? String)?.uppercase()) {
                        "REPLACE" -> MergeStrategy.REPLACE
                        "MERGE" -> MergeStrategy.MERGE
                        else -> MergeStrategy.APPEND
                    }
                )
            }

            return ArtifactDiscoveryConfig(
                layers = layersMap,
                defaultPatterns = (map["defaultPatterns"] as? List<String>) ?: listOf("*.yaml", "*.yml")
            )
        }
    }
}
