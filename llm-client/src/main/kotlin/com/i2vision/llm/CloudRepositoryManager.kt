package com.i2vision.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Manages cloud model repository configurations
 * Stores repository URLs and allows fetching model lists from them
 */
class CloudRepositoryManager(
    private val configFile: File = File(System.getProperty("user.home"), ".koog-cloud-repos.json")
) {
    private val httpClient = HttpClient.newHttpClient()
    private var repositories: MutableList<CloudRepository> = mutableListOf()

    init {
        loadRepositories()
    }

    /**
     * Get all configured repositories
     */
    fun getRepositories(): List<CloudRepository> = repositories.toList()

    /**
     * Add a new repository
     */
    fun addRepository(repository: CloudRepository): Boolean {
        if (repositories.any { it.url == repository.url }) {
            return false // Already exists
        }
        repositories.add(repository)
        saveRepositories()
        return true
    }

    /**
     * Remove a repository by URL
     */
    fun removeRepository(url: String): Boolean {
        val removed = repositories.removeAll { it.url == url }
        if (removed) {
            saveRepositories()
        }
        return removed
    }

    /**
     * Update a repository
     */
    fun updateRepository(oldUrl: String, newRepository: CloudRepository): Boolean {
        val index = repositories.indexOfFirst { it.url == oldUrl }
        if (index == -1) return false

        repositories[index] = newRepository
        saveRepositories()
        return true
    }

    /**
     * Fetch models from a repository URL
     * Returns list of models available at that endpoint
     */
    suspend fun fetchModelsFromUrl(
        url: String,
        apiKey: String? = null,
        provider: CloudProvider = CloudProvider.GENERIC
    ): List<CloudModelInfo> = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("$url/api/tags"))
                .GET()

            apiKey?.let {
                requestBuilder.header("Authorization", "Bearer $it")
            }

            val request = requestBuilder.build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val config = OllamaCloudConfig(
                    apiUrl = url,
                    apiKey = apiKey,
                    provider = provider
                )
                parseCloudModelsResponse(response.body(), config)
            } else {
                throw Exception("HTTP ${response.statusCode()}: ${response.body()}")
            }
        } catch (e: Exception) {
            println("Failed to fetch models from $url: ${e.message}")
            emptyList()
        }
    }

    /**
     * Update models from all repositories
     */
    suspend fun updateAllRepositories(): Map<String, List<CloudModelInfo>> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, List<CloudModelInfo>>()

        repositories.forEach { repo ->
            try {
                val models = fetchModelsFromUrl(repo.url, repo.apiKey, repo.provider)
                results[repo.url] = models
            } catch (e: Exception) {
                println("Failed to update repository ${repo.url}: ${e.message}")
                results[repo.url] = emptyList()
            }
        }

        results
    }

    /**
     * Convert repositories to OllamaCloudConfig list for CloudModelRegistry
     */
    fun toCloudConfigs(): List<OllamaCloudConfig> {
        return repositories.map { repo ->
            OllamaCloudConfig(
                apiUrl = repo.url,
                apiKey = repo.apiKey,
                maxOutputTokens = repo.maxOutputTokens,
                timeoutMs = repo.timeoutMs,
                retryAttempts = repo.retryAttempts,
                region = repo.region,
                provider = repo.provider
            )
        }
    }

    private fun parseCloudModelsResponse(response: String, config: OllamaCloudConfig): List<CloudModelInfo> {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val jsonObject = json.decodeFromString<JsonObject>(response)
            val modelsArray = jsonObject["models"]?.jsonArray ?: return emptyList()

            modelsArray.mapNotNull { modelElement ->
                val modelObj = modelElement as? JsonObject ?: return@mapNotNull null
                val name = modelObj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null

                val size = modelObj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val digest = modelObj["digest"]?.jsonPrimitive?.content ?: "unknown"

                val parts = name.split(":")
                val modelName = parts[0]
                val tag = parts.getOrNull(1) ?: "latest"
                val modelId = "${config.provider.name.lowercase()}:$name"

                val contextLength = inferContextLength(name)

                CloudModelInfo(
                    id = modelId,
                    name = modelName,
                    tag = tag,
                    size = size,
                    contextLength = contextLength,
                    digest = digest,
                    layers = 0,
                    path = config.apiUrl,
                    cloudConfig = config,
                    provider = config.provider,
                    region = config.region,
                    isAvailable = true
                )
            }
        } catch (e: Exception) {
            println("Error parsing cloud models response: ${e.message}")
            emptyList()
        }
    }

    private fun inferContextLength(modelId: String): Int {
        val lower = modelId.lowercase()
        return when {
            lower.contains("70b") -> 8192
            lower.contains("32b") -> 8192
            lower.contains("14b") -> 4096
            lower.contains("8b") -> 4096
            lower.contains("7b") -> 4096
            lower.contains("4b") -> 4096
            lower.contains("3b") -> 2048
            lower.contains("gpt-oss") && lower.contains("20b") -> 16384
            lower.contains("mistral") && lower.contains("7b") -> 32768
            lower.contains("llama3") && lower.contains("70b") -> 8192
            lower.contains("llama3") && lower.contains("8b") -> 8192
            lower.contains("codellama") && lower.contains("70b") -> 16384
            lower.contains("codellama") && lower.contains("34b") -> 16384
            lower.contains("codellama") && lower.contains("13b") -> 16384
            lower.contains("codellama") && lower.contains("7b") -> 16384
            lower.contains("minimax") && lower.contains("m2") -> 196608  // MiniMax M2.1: 196K context
            else -> 4096
        }
    }

    private fun loadRepositories() {
        try {
            if (configFile.exists()) {
                val content = configFile.readText()
                val json = Json { ignoreUnknownKeys = true }
                val saved = json.decodeFromString<List<CloudRepository>>(content)
                repositories = saved.toMutableList()
            }
        } catch (e: Exception) {
            println("Failed to load repositories: ${e.message}")
            repositories = mutableListOf()
        }
    }

    private fun saveRepositories() {
        try {
            configFile.parentFile?.mkdirs()
            val json = Json { prettyPrint = true }
            val serializer = kotlinx.serialization.builtins.ListSerializer(CloudRepository.serializer())
            val content = json.encodeToString(serializer, repositories)
            configFile.writeText(content)
        } catch (e: Exception) {
            println("Failed to save repositories: ${e.message}")
        }
    }

    fun close() {
        // HttpClient doesn't need explicit closing in Java 11+
    }
}

/**
 * Cloud repository configuration
 */
@Serializable
data class CloudRepository(
    val name: String,
    val url: String,
    val apiKey: String? = null,
    @Serializable(with = CloudProviderSerializer::class)
    val provider: CloudProvider = CloudProvider.GENERIC,
    val region: String? = null,
    val maxOutputTokens: Long = 2048L,
    val timeoutMs: Long = 30000L,
    val retryAttempts: Int = 3,
    val enabled: Boolean = true
)

object CloudProviderSerializer : kotlinx.serialization.KSerializer<CloudProvider> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
            "CloudProvider",
            kotlinx.serialization.descriptors.PrimitiveKind.STRING
        )

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: CloudProvider) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): CloudProvider {
        val string = decoder.decodeString()
        return CloudProvider.fromString(string)
    }
}
