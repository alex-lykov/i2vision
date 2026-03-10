# Unified Model Architecture

This module provides a unified architecture for fetching and managing models from both local and cloud repositories.

## Architecture Overview

### Core Components

1. **ModelRepository<T: ModelMetadata>** - Common abstraction for model repositories
2. **ModelMetadata** - Base interface for all model metadata
3. **UnifiedModelService** - Central service managing multiple repositories
4. **ModelParsingUtils** - Common utilities for parsing Ollama API responses

### Repository Types

- **LOCAL_OLLAMA** - Local Ollama instance
- **CLOUD_OLLAMA** - Cloud-hosted Ollama
- **HUGGING_FACE** - Hugging Face models
- **REPLICATE** - Replicate models
- **ANYSCALE** - Anyscale models
- **GENERIC** - Generic Ollama-compatible endpoints

## Benefits

### Before Refactoring
- ❌ Duplicated parsing logic across multiple classes
- ❌ Inconsistent model info structures
- ❌ Scattered repository management
- ❌ Complex conversion logic between model types

### After Refactoring
- ✅ Single source of truth for model parsing
- ✅ Consistent model metadata across all repositories
- ✅ Unified repository management
- ✅ Simplified model type handling
- ✅ Reactive model updates with Flow
- ✅ Graceful error handling and fallbacks

## Usage Examples

### Creating a Service with Local Models Only
```kotlin
val service = UnifiedModelServiceFactory.createWithLocalOllama("http://localhost:11434")
val models = service.refreshAllModels()
```

### Creating a Service with Cloud Repositories
```kotlin
val cloudConfigs = listOf(
    CloudRepositoryConfig(
        name = "My Cloud Provider",
        url = "https://api.example.com",
        apiKey = "your-api-key",
        provider = RepositoryType.GENERIC
    )
)
val service = UnifiedModelServiceFactory.createWithCloudRepositories(cloudConfigs)
```

### Creating a Service with Both Local and Cloud
```kotlin
val service = UnifiedModelServiceFactory.createWithLocalAndCloud(
    ollamaUrl = "http://localhost:11434",
    cloudConfigs = cloudConfigs
)
```

### Observing Model Updates
```kotlin
service.models.collect { models ->
    println("Available models: ${models.size}")
}
```

### Searching Models
```kotlin
val searchResults = service.searchModels("llama")
val localModels = service.getModelsByType(RepositoryType.LOCAL_OLLAMA)
```

## Migration Guide

The refactored architecture maintains backward compatibility:

### Legacy Classes
- `ModelRegistry` - Now uses `LocalOllamaRepository` internally
- `CloudModelRegistry` - Now uses `CloudOllamaRepository` internally
- `UnifiedModelManager` - Uses `UnifiedModelService` with legacy fallbacks

### Migration Path
1. **Phase 1**: Legacy classes continue to work unchanged
2. **Phase 2**: Gradually migrate to `UnifiedModelService`
3. **Phase 3**: Deprecate legacy classes

### Example Migration
```kotlin
// Old way
val localRegistry = ModelRegistry("http://localhost:11434")
val models = localRegistry.scanModels()

// New way
val service = UnifiedModelServiceFactory.createWithLocalOllama("http://localhost:11434")
val result = service.refreshAllModels()
val models = result.getOrNull() ?: emptyList()
```

## Error Handling

The new architecture provides robust error handling:

- **Result types** for all operations
- **Graceful fallbacks** to legacy implementations
- **Error aggregation** from multiple repositories
- **Reactive error reporting** through Flow

## Performance Benefits

- **Reduced code duplication** - Single parsing logic
- **Better resource management** - Centralized HTTP client handling
- **Reactive updates** - Only fetch when needed
- **Parallel repository scanning** - Improved performance
- **Caching** - Built-in model caching
