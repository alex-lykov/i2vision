# llm-client

Unified LLM client library with support for local Ollama, cloud providers, and model runtime management.

## Overview

llm-client provides a comprehensive interface for interacting with Large Language Models (LLMs) including:

- Local Ollama integration
- Cloud provider support (OpenAI, Anthropic, etc.)
- Model repository management
- Token estimation
- Performance monitoring
- Model runtime lifecycle management

## Installation

```kotlin
dependencies {
    implementation("com.i2vision:llm-client:1.0.0")
}
```

## Usage

### Local Ollama

```kotlin
import com.i2vision.llm.LocalOllamaRepository
import com.i2vision.llm.LocalModelWrapper

// Create repository
val repository = LocalOllamaRepository("http://localhost:11434")

// Scan for models
val models = repository.scanModels()
models.onSuccess { modelList ->
    println("Found ${modelList.size} models")
}

// Create model wrapper
val wrapper = LocalModelWrapper(
    modelName = "llama2",
    maxContextLength = 4096,
    baseUrl = "http://localhost:11434"
)

// Generate text
val response = wrapper.generate("Hello, world!")
```

### Cloud Providers

```kotlin
import com.i2vision.llm.CloudOllamaRepository
import com.i2vision.llm.CloudRepositoryConfig
import com.i2vision.llm.RepositoryType

// Configure cloud repository
val config = CloudRepositoryConfig(
    name = "openai",
    url = "https://api.openai.com/v1",
    apiKey = "your-api-key",
    provider = RepositoryType.GENERIC
)

val repository = CloudOllamaRepository(config)
val models = repository.scanModels()
```

### Model Runtime

```kotlin
import com.i2vision.llm.ModelRuntimeFactory

// Create runtime
val runtime = ModelRuntimeFactory.create(
    ollamaUrl = "http://localhost:11434",
    cloudConfigs = emptyList()
)

// Scan all models
val allModels = runtime.scanAllModels()

// Get local models
val localModels = runtime.getLocalModels()

// Create model wrapper
val wrapper = runtime.createModelWrapper("llama2")
```

## Features

### Model Repositories

- **LocalOllamaRepository**: Interact with local Ollama instances
- **CloudOllamaRepository**: Connect to cloud-hosted Ollama endpoints
- **ModelRepository Interface**: Unified API for different repository types

### Model Wrappers

- **LocalModelWrapper**: HTTP client for local Ollama REST APIs
- **CloudModelWrapper**: HTTP client for cloud providers with API key management
- **Streaming Support**: Real-time streaming responses

### Token Estimation

- **TokenEstimator**: Estimate token counts for prompts and responses
- **Context Length Management**: Automatic context length inference

### Model Runtime

- **ModelRuntime**: Facade for model discovery, wrapper creation, and lifecycle
- **UnifiedModelManager**: Unified management across local and cloud models
- **Model Selection Planning**: Intelligent model selection based on requirements

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please read [contributing.md](contributing.md) for details.
