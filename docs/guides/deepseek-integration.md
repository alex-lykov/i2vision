# DeepSeek Integration Guide

## Overview

The i2-vision project supports **DeepSeek** as a cloud LLM provider. You can use DeepSeek either:

1. **Via Ollama** (Recommended) - Access DeepSeek models through Ollama Cloud API
2. **Direct API** - Direct connection to DeepSeek's API

For most users, **using DeepSeek via Ollama** is recommended as it provides a unified interface and easier model switching. See [Ollama Integration Guide](ollama-integration.md) for details.

### DeepSeek Models via Ollama

- `deepseek-v3.1:671b-cloud` - Advanced reasoning (via Ollama Cloud)
- Direct API: `deepseek-chat`, `deepseek-reasoner`, `deepseek-coder`

### When to Use Direct vs Ollama

| Approach | Best For | Configuration |
|----------|----------|---------------|
| **Via Ollama** | Most users, unified interface, easy switching | `provider: ollama`, `id: deepseek-v3.1:671b-cloud` |
| **Direct API** | Advanced users, specific DeepSeek features | `provider: deepseek`, custom config |

---

## Key Features

### DeepSeek Models

- **deepseek-chat**: Fast, cost-effective model for general tasks
- **deepseek-reasoner**: Advanced reasoning model for complex analysis (shows reasoning steps)

### Advantages

| Feature | Ollama | DeepSeek |
|---------|--------|----------|
| **Cost** | Free (local) | ~$0.14/1M tokens |
| **Context Window** | Model dependent | 64K tokens |
| **Response Time** | Fast (local) | Fast (cloud) |
| **Code Reasoning** | Good | Excellent |
| **Contract Validation** | Good | Excellent |
| **Setup** | Requires local installation | API key only |

## Configuration

### Environment Variables

Set your DeepSeek API key:

```bash
export DEEPSEEK_API_KEY="your-api-key-here"
```

### YAML Configuration

Add DeepSeek configuration to your project config:

```yaml
# .vision-ai/config.yml

llm:
  provider: deepseek  # or ollama
  
  deepseek:
    api_key: ${DEEPSEEK_API_KEY}
    model: deepseek-chat  # or deepseek-reasoner
    base_url: https://api.deepseek.com
    
  ollama:
    base_url: http://localhost:11434
    model: llama3.2
```

## Usage

### CLI Usage

Run discovery with DeepSeek:

```bash
# Using environment variable
export DEEPSEEK_API_KEY="your-key-here"
./gradlew :i2vision-cli:run --args="discover --llm-provider=deepseek /path/to/project"

# Using command-line flag
./gradlew :i2vision-cli:run --args="discover --llm-provider=deepseek --deepseek-key=your-key /path/to/project"
```

### Kotlin API Usage

#### Using the Factory

```kotlin
import com.i2vision.llm.LLMClientFactory
import com.i2vision.llm.LLMProvider
import com.i2vision.llm.ProviderConfig

// Create DeepSeek client
val config = ProviderConfig(
    apiKey = System.getenv("DEEPSEEK_API_KEY") 
        ?: error("DEEPSEEK_API_KEY not set"),
    baseUrl = "https://api.deepseek.com",
    model = "deepseek-chat"
)

val llmClient = LLMClientFactory.createClient(LLMProvider.DEEPSEEK, config)

// Generate text
val response = llmClient.generate(
    prompt = "Analyze this code structure...",
    temperature = 0.3,
    topP = 0.9,
    topK = 40,
    maxTokens = 2000,
    timeoutSeconds = 60
)

println(response)
```

#### Using Helper Functions

```kotlin
import com.i2vision.llm.deepseekConfig
import com.i2vision.llm.LLMClientFactory

val config = deepseekConfig(
    apiKey = "your-api-key",
    model = "deepseek-reasoner"
)

val client = LLMClientFactory.createClient(LLMProvider.DEEPSEEK, config)
```

#### Using Chat API

```kotlin
import com.i2vision.llm.DeepSeekClient
import com.i2vision.llm.ChatMessage

val client = DeepSeekClient(
    apiKey = "your-api-key",
    defaultModel = "deepseek-chat"
)

// Single-turn generation
val response = client.generate(
    prompt = "What is the architecture of this project?",
    temperature = 0.7,
    topP = 0.9,
    topK = 40,
    maxTokens = 2048,
    timeoutSeconds = 60
)

// Multi-turn conversation
val messages = listOf(
    ChatMessage.system("You are a software architecture expert."),
    ChatMessage.user("What patterns are used in this codebase?"),
    ChatMessage.assistant("The codebase uses MVVM pattern..."),
    ChatMessage.user("Can you explain the data flow?")
)

val conversationResponse = client.chat(
    messages = messages,
    temperature = 0.7,
    topP = 0.9,
    maxTokens = 4096,
    timeoutSeconds = 60
)
```

### Integration with Discovery Pipeline

```kotlin
import com.i2vision.llm.LLMClientFactory
import com.i2vision.llm.LLMProvider
import com.i2vision.llm.ProviderConfig
import com.i2vision.discover.pipeline.DiscoveryPipelineImpl

class DiscoveryAgent(
    private val llmProvider: LLMProvider,
    private val providerConfig: ProviderConfig
) {
    private val llmClient = LLMClientFactory.createClient(llmProvider, providerConfig)
    
    suspend fun analyzeFile(file: File): AnalysisResult {
        val prompt = buildAnalysisPrompt(file)
        
        // Use the unified interface - works with any provider
        val response = llmClient.generate(prompt, 
            temperature = 0.3,
            topP = 0.9,
            topK = 40,
            maxTokens = 2000,
            timeoutSeconds = 60
        )
        
        return parseAnalysisResult(response)
    }
    
    suspend fun chatWithContext(context: String, question: String): String {
        val messages = listOf(
            ChatMessage.system(context),
            ChatMessage.user(question)
        )
        
        // Cast to DeepSeekClient to use chat API
        if (llmClient is DeepSeekClient) {
            return llmClient.chat(messages)
        }
        
        // Fallback to generate for other providers
        return llmClient.generate("$context\n\nQuestion: $question", 
            temperature = 0.7, topP = 0.9, topK = 40, maxTokens = 2048, timeoutSeconds = 60)
    }
}
```

## Model Selection

### When to Use deepseek-chat

- Quick discovery tasks
- Code summarization
- Basic architecture analysis
- Cost-sensitive operations
- High-throughput scenarios

### When to Use deepseek-reasoner

- Complex architectural analysis
- Contract validation with detailed reasoning
- Multi-step problem solving
- Code refactoring recommendations
- Deep code understanding

## Error Handling

```kotlin
import com.i2vision.llm.DeepSeekApiException

try {
    val response = llmClient.generate(prompt, ...)
} catch (e: DeepSeekApiException) {
    when (e.statusCode) {
        401 -> println("Invalid API key")
        429 -> println("Rate limit exceeded")
        500 -> println("DeepSeek server error")
        else -> println("DeepSeek API error: ${e.message}")
    }
} catch (e: Exception) {
    println("Failed to generate text: ${e.message}")
}
```

## Performance Optimization

### Token Management

DeepSeek supports up to 64K tokens. Optimize your prompts:

```kotlin
// For large file analysis
val response = llmClient.generate(
    prompt = largeCodeContext,
    maxTokens = 8000,  // Adjust based on expected output
    timeoutSeconds = 120  // Longer timeout for large contexts
)
```

### Temperature Settings

- **0.0-0.3**: Deterministic, good for code analysis
- **0.4-0.7**: Balanced, good for general discovery
- **0.8-1.0**: Creative, good for brainstorming

## Testing

```kotlin
// Test DeepSeek connectivity
val client = DeepSeekClient(apiKey = "your-key")

// Check API availability
val isAvailable = client.isAvailable()
println("DeepSeek API available: $isAvailable")

// List available models
val models = client.listModels()
println("Available models: $models")

// Test generation
val response = client.generate(
    prompt = "Say hello",
    temperature = 0.7,
    topP = 0.9,
    topK = 40,
    maxTokens = 100,
    timeoutSeconds = 30
)
println("Response: $response")
```

## Cost Management

DeepSeek pricing (as of 2026):

- **deepseek-chat**: ~$0.14 per 1M tokens
- **deepseek-reasoner**: ~$0.28 per 1M tokens

Monitor usage:

```kotlin
// Track token usage in your application
class TokenTracker {
    var totalInputTokens = 0
    var totalOutputTokens = 0
    
    fun recordUsage(input: Int, output: Int) {
        totalInputTokens += input
        totalOutputTokens += output
        println("Total tokens: ${totalInputTokens + totalOutputTokens}")
        println("Estimated cost: \$${estimateCost()}")
    }
    
    private fun estimateCost(): Double {
        return (totalInputTokens * 0.14 + totalOutputTokens * 0.14) / 1_000_000
    }
}
```

## Migration from Ollama

Switching from Ollama to DeepSeek is seamless:

```kotlin
// Before (Ollama)
val ollamaClient = OllamaLlmClient(
    baseUrl = "http://localhost:11434",
    defaultModel = "llama3.2:3b"
)

// After (DeepSeek)
val deepseekClient = DeepSeekClient(
    apiKey = "your-key",
    baseUrl = "https://api.deepseek.com",
    defaultModel = "deepseek-chat"
)

// Both implement ModelProvider - drop-in replacement
val llmClient: ModelProvider = if (useCloud) deepseekClient else ollamaClient
```

## Security Best Practices

1. **Never commit API keys** to version control
2. **Use environment variables** or secure secret management
3. **Rotate keys periodically**
4. **Monitor usage** for unusual patterns
5. **Set rate limits** in your application

```kotlin
// Example: Load from environment
val apiKey = System.getenv("DEEPSEEK_API_KEY") 
    ?: error("DEEPSEEK_API_KEY environment variable not set")

// Example: Validate API key format
require(apiKey.startsWith("sk-")) { "Invalid DeepSeek API key format" }
```

## Troubleshooting

### Common Issues

**401 Unauthorized**
- Check API key is correct
- Verify key hasn't expired
- Ensure no extra whitespace

**429 Rate Limit**
- Implement exponential backoff
- Reduce request frequency
- Consider upgrading plan

**500 Server Error**
- Wait and retry
- Check DeepSeek status page
- Fallback to Ollama if available

**Timeout**
- Increase timeoutSeconds parameter
- Reduce maxTokens
- Check network connectivity

## Related Documents

- [LLM Client API Reference](../reference/llm-client.md)
- [Agent Configuration](./agent-config.md)
- [Ollama Integration](./ollama-integration.md)
