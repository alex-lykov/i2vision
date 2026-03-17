package com.i2vision.llm

import com.i2vision.llm.ModelParsingUtils
import com.i2vision.llm.ModelWrapper
import com.i2vision.llm.PerformanceMonitor
import com.i2vision.llm.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.measureTimeMillis

@Serializable
private data class OllamaCloudGenerateRequest(val model: String, val prompt: String, val stream: Boolean = true)

/**
 * OllamaCloudModelWrapper: HTTP client for remote Ollama instances
 * Supports cloud-hosted Ollama APIs with authentication and configuration
 */
class OllamaCloudModelWrapper(
    override val modelName: String,
    override val maxContextLength: Int,
    private val cloudConfig: OllamaCloudConfig,
    private val performanceMonitor: PerformanceMonitor? = null
) : ModelWrapper {
    
    override suspend fun generate(prompt: String): String {
        return withContext(Dispatchers.IO) {
            val client = HttpClient.newHttpClient()
            val body = Json.encodeToString(OllamaCloudGenerateRequest(model = modelName, prompt = prompt, stream = false))
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("${cloudConfig.apiUrl.trimEnd('/')}/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(java.time.Duration.ofMillis(cloudConfig.timeoutMs))
            cloudConfig.apiKey?.let { key ->
                requestBuilder.header("Authorization", "Bearer $key")
            }
            val request = requestBuilder.build()

            var rawResponse = ""
            val responseTime = measureTimeMillis {
                val httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (httpResponse.statusCode() != 200) {
                    throw IllegalStateException("Ollama cloud /api/generate failed: HTTP ${httpResponse.statusCode()} - ${httpResponse.body()}")
                }
                rawResponse = httpResponse.body()
            }

            val json = Json { ignoreUnknownKeys = true }
            val obj = runCatching { json.parseToJsonElement(rawResponse) as? JsonObject }.getOrNull()
                ?: throw IllegalStateException("Invalid Ollama cloud /api/generate response payload")
            val response = obj["response"]?.jsonPrimitive?.content.orEmpty()

            val (tokensUsed, tokensGenerated) = ModelParsingUtils.parseOllamaGenerateTokenCounts(
                obj,
                estimateTokens(prompt),
                estimateTokens(response)
            )
            performanceMonitor?.recordMetric(responseTime, tokensUsed, tokensGenerated)
            response
        }
    }

    override suspend fun generateStreaming(prompt: String): Flow<String> = flow {
        withContext(Dispatchers.IO) {
            val client = HttpClient.newHttpClient()
            val body = Json.encodeToString(OllamaCloudGenerateRequest(model = modelName, prompt = prompt, stream = true))
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("${cloudConfig.apiUrl.trimEnd('/')}/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(java.time.Duration.ofMillis(cloudConfig.timeoutMs))
            cloudConfig.apiKey?.let { key ->
                requestBuilder.header("Authorization", "Bearer $key")
            }
            val request = requestBuilder.build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                val errBody = response.body().readBytes().decodeToString()
                throw IllegalStateException("Ollama cloud /api/generate failed: HTTP ${response.statusCode()} - $errBody")
            }

            val json = Json { ignoreUnknownKeys = true }
            val reader = response.body().bufferedReader()
            val fullResponse = StringBuilder()
            var line: String?
            var lastChunk: JsonObject? = null
            val startTime = System.currentTimeMillis()

            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: continue
                lastChunk = obj
                val chunk = obj["response"]?.jsonPrimitive?.content ?: ""
                if (chunk.isNotEmpty()) {
                    fullResponse.append(chunk)
                    emit(chunk)
                }
                if (obj["done"]?.jsonPrimitive?.content == "true") break
            }

            val responseTime = System.currentTimeMillis() - startTime
            val (tokensUsed, tokensGenerated) = ModelParsingUtils.parseOllamaGenerateTokenCounts(
                lastChunk, estimateTokens(prompt), estimateTokens(fullResponse.toString())
            )
            performanceMonitor?.recordMetric(responseTime, tokensUsed, tokensGenerated)
        }
    }

    override fun estimateTokens(text: String): Int {
        return TokenEstimator.estimateTokens(text)
    }
}

/**
 * Configuration for Ollama cloud instances
 */
data class OllamaCloudConfig(
    val apiUrl: String,
    val apiKey: String? = null,
    val maxOutputTokens: Long = 2048L,
    val timeoutMs: Long = 30000L,
    val retryAttempts: Int = 3,
    val region: String? = null,
    val provider: CloudProvider = CloudProvider.GENERIC
)

enum class CloudProvider {
    GENERIC,
    OLLAMA_CLOUD,
    HUGGING_FACE,
    REPLICATE,
    ANYSCALE;
    
    companion object {
        fun fromString(value: String): CloudProvider {
            return try {
                valueOf(value.uppercase().replace("-", "_"))
            } catch (e: Exception) {
                GENERIC
            }
        }
    }
}
