# Link Service

Semantic link management for i2vision - manages cross-layer references between VSLFC artifacts.

## Purpose
Manages semantic links between Vision, Structure, Logic, Flow, and Code layers:
- Link upsertion and retrieval
- Cross-layer reference resolution
- Alias management
- Batch processing for parallel discovery

## Current Status
**Phase 1: Basic Functionality** (Complete)
- Link storage and retrieval
- Alias caching
- Reference normalization
- Batch mode for parallel discovery

## Components

### LinkService
Main service for managing semantic links:
- Load links from user home cache links.yaml
- Upsert links with deduplication
- Resolve aliases and references
- Batch mode for parallel discovery

## Parallel Discovery & Concurrency Safety

### Batch Mode
- **Implementation**: Links buffered in memory during discovery, single disk write at end
- **Purpose**: Eliminates concurrent file corruption when multiple clusters write links simultaneously
- **Usage**:
  ```kotlin
  linkService.enableBatchMode()
  // ... discovery operations
  linkService.flushBatch()  // Write all links at once
  ```

### Performance Impact
- **Before**: Constant upserts to links.yaml caused YAML parsing errors
- **After**: Single write at end eliminates corruption
- **Result**: 82% faster discovery (4m 41s vs 26m 51s)

## Usage

```kotlin
import com.i2vision.link.LinkService

val linkService = LinkService(projectRoot)

// Normal mode
linkService.upsertLinks(links)

// Batch mode (for parallel discovery)
linkService.enableBatchMode()
links.forEach { clusterLinks ->
    linkService.upsertLinks(clusterLinks)  // Buffered in memory
}
linkService.flushBatch()  // Write all at once
```

## Dependencies
- storage-core
- SnakeYAML

## Architecture
```
link-service
└── src/main/kotlin/com/i2vision/link/
    └── LinkService.kt
```

## License
MIT License

## Version
1.0.0
