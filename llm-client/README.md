# LLM Client Module

Unified LLM client library for i2-vision with support for multiple providers including **Ollama** (local) and **DeepSeek** (cloud).

## Features

- **Multi-Provider Support**: Unified interface for different LLM backends
- **Provider Factory**: Easy client creation with configuration
- **Chat & Generate APIs**: Support for both simple generation and conversation history
- **Error Handling**: Provider-specific exception types
- **Configuration**: Environment variables, config files, or programmatic setup

## Supported Providers

| Provider | Type | Cost | Best For |
|----------|------|------|----------|
| **Ollama** | Local | Free | Development, testing, privacy |
| **DeepSeek** | Cloud | ~$0.14/1M tokens | Production, complex reasoning, 64K context |

## Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.i2vision:llm-client:1.0.0")
}
```

## Quick Start

### Using the Factory

```kotlin
import com.i2vision.llm.*

// Ollama (local)
val ollamaConfig = ollamaConfig(
    baseUrl = "http://localhost:11434",
    model = "llama3.2:3b"
)
val ollamaClient = LLMClientFactory.createClient(LLMProvider.OLLAMA, ollamaConfig)

// DeepSeek (cloud)
val deepseekConfig = deepseekConfig(
    apiKey = System.getenv("DEEPSEEK_API_KEY") ?: error("Missing API key"),
    model = "deepseek-chat"
)
val deepseekClient = LLMClientFactory.createClient(LLMProvider.DEEPSEEK, deepseekConfig)

// Generate text
val response = deepseekClient.generate(
    prompt = "Explain this code architecture",
    temperature = 0.7,
    topP = 0.9,
    topK = 40,
    maxTokens = 2048,
    timeoutSeconds = 60
)
```

### Using Provider-Specific Clients

```kotlin
import com.i2vision.llm.*

// Direct Ollama client
val ollama = OllamaLlmClient(
    baseUrl = "http://localhost:11434",
    defaultModel = "llama3.2:3b"
)

// Direct DeepSeek client
val deepseek = DeepSeekClient(
    apiKey = "sk-xxx",
    baseUrl = "https://api.deepseek.com",
    defaultModel = "deepseek-chat"
)

// Check availability
val isAvailable = deepseek.isAvailable()
val models = deepseek.listModels()
```

### Chat API (DeepSeek)

```kotlin
import com.i2vision.llm.*

val client = DeepSeekClient(apiKey = "sk-xxx")

// Multi-turn conversation
val messages = listOf(
    ChatMessage.system("You are a software architect"),
    ChatMessage.user("What patterns are used here?"),
    ChatMessage.assistant("The code uses MVVM pattern..."),
    ChatMessage.user("Explain the data flow")
)

val response = client.chat(
    messages = messages,
    temperature = 0.7,
    topP = 0.9,
    maxTokens = 4096,
    timeoutSeconds = 60
)
```

## Configuration

### Environment Variables

```bash
export DEEPSEEK_API_KEY="sk-xxx"
export OLLAMA_BASE_URL="http://localhost:11434"
```

### Config File (.i2vision/llm.config)

```properties
provider=deepseek

# DeepSeek settings
deepseek.api_key=sk-xxx
deepseek.model=deepseek-chat
deepseek.base_url=https://api.deepseek.com

# Ollama settings
ollama.base_url=http://localhost:11434
ollama.model=llama3.2:3b
```

### Programmatic Configuration

```kotlin
val config = ProviderConfigBuilder()
    .apiKey("sk-xxx")
    .baseUrl("https://api.deepseek.com")
    .model("deepseek-chat")
    .build()

val client = LLMClientFactory.createClient(LLMProvider.DEEPSEEK, config)
```

## ModelProvider Interface

All providers implement the unified `ModelProvider` interface:

```kotlin
interface ModelProvider {
    suspend fun generate(
        prompt: String,
        temperature: Double,
        topP: Double,
        topK: Int,
        maxTokens: Int,
        timeoutSeconds: Long
    ): String
}
```

This allows seamless switching between providers:

```kotlin
class MyService(private val llm: ModelProvider) {
    suspend fun analyze(code: String): String {
        return llm.generate(
            prompt = "Analyze: $code",
            temperature = 0.3,
            topP = 0.9,
            topK = 40,
            maxTokens = 2000,
            timeoutSeconds = 60
        )
    }
}

// Use with any provider
val serviceWithOllama = MyService(ollamaClient)
val serviceWithDeepSeek = MyService(deepseekClient)
```

## Error Handling

### Ollama Exceptions

```kotlin
try {
    ollamaClient.generate(...)
} catch (e: OllamaApiException) {
    when (e.statusCode) {
        404 -> println("Model not found")
        500 -> println("Ollama server error")
        else -> println("Ollama error: ${e.message}")
    }
}
```

### DeepSeek Exceptions

```kotlin
try {
    deepseekClient.generate(...)
} catch (e: DeepSeekApiException) {
    when (e.statusCode) {
        401 -> println("Invalid API key")
        429 -> println("Rate limit exceeded")
        500 -> println("DeepSeek server error")
        else -> println("DeepSeek error: ${e.message}")
    }
}
```

## Provider Comparison

### Ollama

**Pros:**
- ✅ Free (runs locally)
- ✅ Privacy (no data leaves your machine)
- ✅ Fast (no network latency)
- ✅ Many models available

**Cons:**
- ❌ Requires local installation
- ❌ Model quality depends on what you download
- ❌ Limited context window (model-dependent)
- ❌ Uses your hardware resources

**Best for:**
- Development and testing
- Privacy-sensitive applications
- Quick iterations
- Offline scenarios

### DeepSeek

**Pros:**
- ✅ High-quality reasoning
- ✅ 64K token context window
- ✅ No setup (API key only)
- ✅ Consistent performance
- ✅ Excellent for code analysis

**Cons:**
- ❌ Cost (~$0.14/1M tokens)
- ❌ Requires internet
- ❌ Data sent to cloud
- ❌ Rate limits may apply

**Best for:**
- Production deployments
- Complex architectural analysis
- Large file analysis
- High-quality verbalization

## Model Selection

### Ollama Models

```kotlin
// Lightweight (fast, less capable)
ollamaConfig(model = "llama3.2:1b")
ollamaConfig(model = "phi3:mini")

// Balanced
ollamaConfig(model = "llama3.2:3b")
ollamaConfig(model = "mistral:7b")

// Powerful (slow, more capable)
ollamaConfig(model = "llama3.1:8b")
ollamaConfig(model = "codellama:13b")
```

### DeepSeek Models

```kotlin
// Fast and cost-effective
deepseekConfig(model = "deepseek-chat")

// Advanced reasoning
deepseekConfig(model = "deepseek-reasoner")
```

## Testing

```kotlin
import com.i2vision.llm.*
import kotlinx.coroutines.runBlocking

fun testDeepSeek() = runBlocking {
    val client = DeepSeekClient(apiKey = "sk-xxx")
    
    // Test connectivity
    val available = client.isAvailable()
    println("API available: $available")
    
    // List models
    val models = client.listModels()
    println("Available models: $models")
    
    // Test generation
    val response = client.generate(
        prompt = "Say hello in one sentence",
        temperature = 0.7,
        topP = 0.9,
        topK = 40,
        maxTokens = 50,
        timeoutSeconds = 30
    )
    println("Response: $response")
}

fun testOllama() = runBlocking {
    val client = OllamaLlmClient()
    
    // Check availability
    val available = client.isAvailable()
    println("Ollama available: $available")
    
    // List models
    val models = client.listModels()
    println("Models: $models")
}
```

## Performance Tips

### Token Management

```kotlin
// For large contexts, increase timeout
val response = client.generate(
    prompt = largeCodebase,
    maxTokens = 8000,
    timeoutSeconds = 120  // Longer timeout
)
```

### Temperature Settings

- **0.0-0.3**: Deterministic (code analysis, fact extraction)
- **0.4-0.7**: Balanced (general discovery, documentation)
- **0.8-1.0**: Creative (brainstorming, suggestions)

### Batch Processing

```kotlin
// Process multiple prompts efficiently
val prompts = listOf("prompt1", "prompt2", "prompt3")
val responses = prompts.map { prompt ->
    async { client.generate(prompt, ...) }
}.awaitAll()
```

## Migration Guide

### From Direct API Calls

**Before:**
```kotlin
val httpClient = HttpClient()
val response = httpClient.post("https://api.deepseek.com/v1/chat/completions") {
    // ... manual request building
}
```

**After:**
```kotlin
val client = DeepSeekClient(apiKey = "sk-xxx")
val response = client.generate(prompt, ...)
```

### From Ollama-only

**Before:**
```kotlin
val client = OllamaLlmClient()
```

**After (supports both):**
```kotlin
val client: ModelProvider = if (useCloud) {
    DeepSeekClient(apiKey = "sk-xxx")
} else {
    OllamaLlmClient()
}
```

## Troubleshooting

### Common Issues

**"API key not found"**
```bash
# Set environment variable
export DEEPSEEK_API_KEY="sk-xxx"

# Or use config file
echo "deepseek.api_key=sk-xxx" >> .i2vision/llm.config
```

**"Ollama not available"**
```bash
# Install Ollama
curl -fsSL https://ollama.com/install.sh | sh

# Pull a model
ollama pull llama3.2:3b

# Start server
ollama serve
```

**"Rate limit exceeded"**
- Implement exponential backoff
- Reduce request frequency
- Consider upgrading your DeepSeek plan

**"Timeout"**
- Increase `timeoutSeconds` parameter
- Reduce `maxTokens`
- Check network connectivity (for DeepSeek)

## API Reference

### DeepSeekClient

```kotlin
class DeepSeekClient(
    apiKey: String,
    baseUrl: String = "https://api.deepseek.com",
    defaultModel: String = "deepseek-chat"
) : ModelProvider

// Methods
suspend fun generate(prompt: String, ...): String
suspend fun chat(messages: List<ChatMessage>, ...): String
suspend fun isAvailable(): Boolean
suspend fun listModels(): List<String>
```

### OllamaLlmClient

```kotlin
class OllamaLlmClient(
    baseUrl: String = "http://localhost:11434",
    defaultModel: String = "llama3.2:3b"
) : ModelProvider

// Methods
suspend fun generate(prompt: String, ...): String
suspend fun generate(model: String, prompt: String, ...): String
suspend fun isAvailable(): Boolean
suspend fun listModels(): List<String>
```

### LLMClientFactory

```kotlin
object LLMClientFactory {
    fun createClient(provider: LLMProvider, config: ProviderConfig): ModelProvider
    fun createOllamaClient(baseUrl: String, model: String): OllamaLlmClient
    fun createDeepSeekClient(apiKey: String, baseUrl: String, model: String): DeepSeekClient
}
```

## License

MIT License - see LICENSE file for details.

## Related Documentation

- [DeepSeek Integration Guide](../docs/guides/deepseek-integration.md)
- [Agent Implementation Guide](../docs/guides/agent-implementation.md)
- [Verbalization Engine](../verbalization-core/README.md)
