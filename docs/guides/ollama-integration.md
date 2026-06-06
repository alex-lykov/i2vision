# Ollama Integration Guide

## Overview

The i2-vision project supports **Ollama** as the primary LLM provider, offering both **local models** (running on your hardware) and **cloud models** (via Ollama Cloud API) through a unified interface.

## Key Features

### Unified Architecture

Ollama provides access to both local and cloud models through the same API endpoint (`localhost:11434`). The model name determines execution location:

```
┌────────────────────────────────────────────────────────┐
│              Your Application                          │
│                    ↓                                   │
│         Ollama API (localhost:11434)                   │
│              ↙                ↘                        │
│     Local Models          Cloud Models                 │
│   (Your GPU/CPU)         (Ollama Cloud)                │
│   - gemma3:1b           - minimax-m2.1:cloud           │
│   - llama3.2:3b         - deepseek-v3.1:671b-cloud     │
└────────────────────────────────────────────────────────┘
```

### Model Types

#### Local Models (Run on Your Machine)

| Model | Size | Best For |
|-------|------|----------|
| `gemma3:1b` | 815 MB | Ultra-fast responses, simple tasks |
| `qwen2.5-coder:0.5b-instruct` | 397 MB | Quick code completions |
| `llama3.2:3b` | ~2 GB | Fast general purpose |
| `llama3.2:7b` | ~4 GB | Balanced speed/quality |
| `llama3.1:8b` | ~5 GB | General purpose |
| `qwen3:4b` | 2.5 GB | Good coding assistant |
| `codellama:7b` | ~4 GB | Code generation |
| `codellama:13b` | ~8 GB | Advanced coding (needs RAM) |
| `mistral:7b` | ~4 GB | General purpose |
| `qwen2.5:7b` | ~4 GB | Multilingual tasks |
| `llama2:7b` | 3.8 GB | Legacy compatibility |

**Requirements:**
- RAM: Model size + 2-4GB overhead
- GPU (optional): CUDA/Metal for faster inference
- Disk: Model storage

#### Cloud Models (Via Ollama Cloud API)

| Model | Provider | Best For |
|-------|----------|----------|
| `minimax-m2.1:cloud` | MiniMax | Multilingual code engineering |
| `mistral-large-3:675b-cloud` | Mistral | Enterprise-grade reasoning |
| `gemma4:31b-cloud` | Google | Multimodal tasks |
| `deepseek-v3.1:671b-cloud` | DeepSeek | Advanced reasoning |
| `qwen3-coder:480b-cloud` | Alibaba | Expert-level coding |
| `qwen3.5:cloud` | Alibaba | Balanced performance |
| `glm-5.1:cloud` | Z.ai | Agentic engineering |
| `kimi-k2.6:cloud` | Moonshot | Long context tasks |
| `nemotron-3-super:cloud` | NVIDIA | Multi-agent workflows |

**Requirements:**
- Internet connection
- Ollama account (for rate limits)
- No local GPU/RAM requirements

## Configuration

### Environment Setup

```bash
# Install Ollama (if not already installed)
# Windows: Download from https://ollama.com
# macOS: brew install ollama
# Linux: curl -fsSL https://ollama.com/install.sh | sh

# Start Ollama server
ollama serve

# Pull a local model
ollama pull llama3.2:3b

# Register a cloud model
ollama pull minimax-m2.1:cloud
```

### YAML Configuration

```yaml
# .vision-ai/config.yml

llm:
  provider: ollama
  
  ollama:
    base_url: http://localhost:11434
    model: llama3.2:3b  # or minimax-m2.1:cloud for cloud
    
    # Model parameters
    temperature: 0.7
    topP: 0.9
    topK: 40
    maxTokens: 2048
    contextLength: 32768
```

### Agent Configuration

```yaml
# .vision-ai/vision-agent.yaml

model:
  # Local model
  id: llama3.2:7b
  provider: ollama
  
  # OR Cloud model (same provider!)
  # id: minimax-m2.1:cloud
  # provider: ollama
  
  contextLength: 32768
  maxOutputTokens: 4096
  temperature: 0.7
  topP: 0.9
```

## Usage

### CLI Usage

```bash
# Run discovery with local model
./gradlew :i2vision-cli:run --args="discover --llm-provider=ollama --ollama-model=llama3.2:7b /path/to/project"

# Run discovery with cloud model
./gradlew :i2vision-cli:run --args="discover --llm-provider=ollama --ollama-model=minimax-m2.1:cloud /path/to/project"
```

### Kotlin API Usage

#### Basic Usage

```kotlin
import com.i2vision.llm.OllamaLlmClient
import com.i2vision.llm.ModelProvider

// Local model
val ollamaClient = OllamaLlmClient(
    baseUrl = "http://localhost:11434",
    defaultModel = "llama3.2:3b"
)

// Cloud model (same client!)
val cloudClient = OllamaLlmClient(
    baseUrl = "http://localhost:11434",
    defaultModel = "minimax-m2.1:cloud"
)

// Generate text
val response = ollamaClient.generate(
    prompt = "Analyze this code structure...",
    temperature = 0.3,
    topP = 0.9,
    topK = 40,
    maxTokens = 2000,
    timeoutSeconds = 60
)

println(response)
```

#### Model Selection

```kotlin
// Use specific model for one-off request
val response = ollamaClient.generate(
    model = "qwen3-coder:480b-cloud",
    prompt = "Refactor this code...",
    temperature = 0.7,
    topP = 0.9,
    maxTokens = 4096,
    timeoutSeconds = 120
)
```

#### Check Availability

```kotlin
// Check if Ollama server is running
val isAvailable = ollamaClient.isAvailable()
println("Ollama available: $isAvailable")

// List available models
val models = ollamaClient.listModels()
println("Available models: $models")
```

### VSCode Extension

The VSCode extension provides a UI for selecting providers and models:

1. **Open an agent tab** (Vision/Structure/Logic/Flow/Code)
2. **Select provider**: `Ollama (Local + Cloud)`
3. **Select model**: Choose from grouped dropdown
   - **Local Models** (green label)
   - **Cloud Models** (blue label)
4. **Configuration auto-saves** to `.vision-ai/`

See [VSCode Provider & Model Selection](vscode-provider-model-selection.md) for detailed UI guide.

## Model Selection Guide

### When to Use Local Models

✅ **Use Local When:**
- Quick iterations and testing
- Simple tasks (formatting, small refactors)
- Privacy-sensitive code
- No internet connection
- Cost-sensitive operations
- You have adequate hardware

**Recommended Local Models:**
- `gemma3:1b` - Ultra-fast (<1s), simple tasks
- `llama3.2:3b` - Fast (1-2s), general purpose
- `llama3.2:7b` - Balanced (2-5s), code review
- `codellama:13b` - Advanced coding (needs 8GB+ RAM)

### When to Use Cloud Models

✅ **Use Cloud When:**
- Complex reasoning needed
- Large context (100K+ tokens)
- Expert-level coding tasks
- Latest model features
- Your hardware is insufficient
- Production-quality analysis

**Recommended Cloud Models:**
- `minimax-m2.1:cloud` - Multilingual code engineering
- `qwen3-coder:480b-cloud` - Expert-level coding
- `deepseek-v3.1:671b-cloud` - Advanced reasoning
- `mistral-large-3:675b-cloud` - Enterprise reasoning

## Performance Comparison

| Model | Type | Speed | Quality | RAM | Best Use |
|-------|------|-------|---------|-----|----------|
| gemma3:1b | Local | ⚡⚡⚡ | ⭐⭐ | 1GB | Quick edits |
| llama3.2:3b | Local | ⚡⚡ | ⭐⭐⭐ | 2GB | General tasks |
| llama3.2:7b | Local | ⚡ | ⭐⭐⭐⭐ | 4GB | Code review |
| minimax-m2.1:cloud | Cloud | ⚡⚡ | ⭐⭐⭐⭐⭐ | 0GB | Complex tasks |
| qwen3-coder:480b-cloud | Cloud | ⚡ | ⭐⭐⭐⭐⭐+ | 0GB | Expert coding |

## Integration with Discovery Pipeline

```kotlin
import com.i2vision.llm.OllamaLlmClient
import com.i2vision.discover.pipeline.DiscoveryPipelineImpl

class DiscoveryAgent {
    private val ollamaClient = OllamaLlmClient(
        baseUrl = "http://localhost:11434",
        defaultModel = "llama3.2:7b"
    )
    
    suspend fun analyzeFile(file: File): AnalysisResult {
        val prompt = buildAnalysisPrompt(file)
        
        // Works with both local and cloud models
        val response = ollamaClient.generate(
            prompt = prompt,
            temperature = 0.3,
            topP = 0.9,
            topK = 40,
            maxTokens = 2000,
            timeoutSeconds = 60
        )
        
        return parseAnalysisResult(response)
    }
    
    suspend fun analyzeWithCloudModel(file: File): AnalysisResult {
        val prompt = buildAnalysisPrompt(file)
        
        // Use cloud model for complex analysis
        val response = ollamaClient.generate(
            model = "qwen3-coder:480b-cloud",
            prompt = prompt,
            temperature = 0.3,
            topP = 0.9,
            maxTokens = 4096,
            timeoutSeconds = 120
        )
        
        return parseAnalysisResult(response)
    }
}
```

## Error Handling

```kotlin
import com.i2vision.llm.OllamaApiException

try {
    val response = ollamaClient.generate(prompt, ...)
} catch (e: OllamaApiException) {
    when (e.statusCode) {
        404 -> println("Model not found or not pulled")
        503 -> println("Ollama server unavailable")
        else -> println("Ollama API error: ${e.message}")
    }
} catch (e: Exception) {
    println("Failed to generate text: ${e.message}")
}
```

## Performance Optimization

### Local Model Optimization

```bash
# Enable GPU acceleration (NVIDIA)
# Ollama auto-detects CUDA, ensure drivers are installed

# Check GPU usage
nvidia-smi

# Monitor Ollama process
# Windows: Task Manager
# Linux: htop
```

### Cloud Model Optimization

```kotlin
// Use longer timeouts for cloud models
val response = ollamaClient.generate(
    model = "qwen3-coder:480b-cloud",
    prompt = largePrompt,
    maxTokens = 8000,
    timeoutSeconds = 180  // 3 minutes for complex tasks
)
```

### Token Management

```kotlin
// Adjust context length based on model
val config = when (modelId) {
    "gemma3:1b" -> 8192
    "llama3.2:7b" -> 32768
    "minimax-m2.1:cloud" -> 256000
    else -> 32768
}
```

## Cost Considerations

### Local Models
- **Cost**: Free (your hardware)
- **Electricity**: ~10-50W during inference
- **Hardware**: GPU/CPU investment

### Cloud Models
- **Cost**: Ollama Cloud credits (check current pricing)
- **Rate Limits**: Apply based on Ollama Cloud terms
- **Latency**: Network round-trip (50-500ms)

## Testing

```kotlin
// Test Ollama connectivity
val client = OllamaLlmClient(baseUrl = "http://localhost:11434")

// Check server availability
val isAvailable = client.isAvailable()
println("Ollama server available: $isAvailable")

// List available models
val models = client.listModels()
println("Available models: ${models.joinToString()}")

// Test local model
val localResponse = client.generate(
    model = "llama3.2:3b",
    prompt = "Say hello",
    maxTokens = 50,
    timeoutSeconds = 30
)
println("Local model response: $localResponse")

// Test cloud model
val cloudResponse = client.generate(
    model = "minimax-m2.1:cloud",
    prompt = "Say hello",
    maxTokens = 50,
    timeoutSeconds = 30
)
println("Cloud model response: $cloudResponse")
```

## Troubleshooting

### Ollama Server Won't Start

```bash
# Check if Ollama is installed
ollama --version

# Check if port is in use
netstat -an | grep :11434

# Restart Ollama
# Windows: Restart Ollama app from system tray
# macOS: ollama serve
# Linux: systemctl restart ollama
```

### Model Not Found

```bash
# Pull the model
ollama pull llama3.2:3b

# For cloud models (registers them)
ollama pull minimax-m2.1:cloud

# List available models
ollama ls
```

### Cloud Models Not Working

```bash
# Check internet connection
ping ollama.com

# Test cloud model directly
ollama run minimax-m2.1:cloud "Hello"

# Check Ollama Cloud account status
# Visit https://ollama.com/account
```

### Local Models Slow

```bash
# Check RAM availability
# Windows: Task Manager → Performance
# Linux: free -h

# Check if using GPU
nvidia-smi  # NVIDIA

# Solutions:
# 1. Close other applications
# 2. Use smaller model (gemma3:1b)
# 3. Enable GPU acceleration
# 4. Reduce context length
```

### Low Quality Responses

```bash
# Try a larger model
ollama pull llama3.2:7b

# Or use cloud model for complex tasks
ollama pull qwen3-coder:480b-cloud

# Adjust temperature (lower = more focused)
# In config: temperature: 0.3
```

## Security Best Practices

1. **Local Models**: Keep Ollama server on localhost only
2. **Cloud Models**: Use HTTPS (handled by Ollama Cloud)
3. **API Access**: Don't expose Ollama API to public network
4. **Model Updates**: Regularly update models for security patches

```bash
# Update models
ollama pull llama3.2:7b

# Check Ollama version
ollama --version

# Update Ollama
# Download latest from https://ollama.com
```

## Migration Between Models

Switching between local and cloud models is seamless:

```kotlin
// Configuration-based switching
val modelId = if (useCloud) "minimax-m2.1:cloud" else "llama3.2:7b"

val client = OllamaLlmClient(
    baseUrl = "http://localhost:11434",
    defaultModel = modelId
)

// Or per-request switching
val response = client.generate(
    model = selectedModelId,  // Can be local or cloud
    prompt = prompt
)
```

## VSCode Extension Integration

The VSCode extension provides a unified UI for model selection:

```typescript
// Provider dropdown
Provider: [Ollama (Local + Cloud) ▼]

// Model dropdown (grouped)
Model: [─────────────────────────────────── ▼]
       
       Local Models (Run on your machine)
       ├─ Gemma 3 1B (Local, Fast)
       ├─ Llama 3.2 3B (Local, Fast)
       └─ ...
       
       Cloud Models (Via Ollama Cloud API)
       ├─ MiniMax M2.1 (Cloud)
       ├─ DeepSeek V3.1 671B (Cloud)
       └─ ...
```

See [VSCode Provider & Model Selection](vscode-provider-model-selection.md) for detailed usage.

## Related Documents

- [VSCode Provider & Model Selection](vscode-provider-model-selection.md) - UI guide
- [DeepSeek Integration](deepseek-integration.md) - Direct DeepSeek API
- [Deployment Guide](deployment.md) - Server deployment
- [LLM Client API Reference](../reference/llm-client.md) - API documentation

## Resources

- **Ollama Website**: https://ollama.com
- **Model Library**: https://ollama.com/library
- **GitHub**: https://github.com/ollama/ollama
- **Documentation**: https://github.com/ollama/ollama/tree/main/docs

---

**Last Updated:** 2026-06-06  
**Ollama Version:** Check with `ollama --version`
