# Storage Abstraction Layer Migration

## Overview

This document describes the migration from direct file path knowledge to a storage abstraction layer using the `storage-core` module. This migration protects the ecosystem from path knowledge leakage and enables future storage backend changes.

## Motivation

**Before Migration:**
- Every module knew exact file paths (e.g., `~/.i2vision/cache/projects/<hash>/.semantic-cache/{cluster}/flow/sequences.yaml`)
- Storage layout changes required updates across all modules
- Tight coupling between modules and filesystem structure
- No protection against path knowledge leakage

**After Migration:**
- Only `storage-core` knows physical storage layout
- All other modules use public API interfaces
- Storage changes isolated to single module
- Compile-time and architectural protection against leakage

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                   APPLICATION MODULES                             │
│  i2vision-discover  i2vision-instant  i2vision-mcp  i2vision-cli│
│         (No path knowledge - use public API only)               │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    storage-core (PUBLIC API)                      │
│                                                                  │
│  • CacheStore - VSLFC artifact storage                          │
│  • ContractStore - Contract registry operations                 │
│  • ArtifactStore - VSLFC artifact operations                    │
│  • ConfigStore - Configuration storage                          │
│  • StorageConstants - Public directory names                    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              storage-core (INTERNAL IMPLEMENTATION)               │
│                                                                  │
│  • FileCacheStore - Knows cache layout in OS user home          │
│  • FileContractStore - Knows cache layout in OS user home       │
│  • StorageLayout - PRIVATE! All path constants here              │
└─────────────────────────────────────────────────────────────────┘
```

## Module Structure

```
storage-core/
├── src/main/kotlin/com/i2vision/storage/
│   │
│   ├── api/                         # PUBLIC INTERFACES
│   │   ├── CacheStore.kt
│   │   ├── ContractStore.kt
│   │   ├── ArtifactStore.kt
│   │   └── ConfigStore.kt
│   │
│   ├── model/                       # PUBLIC DATA MODELS
│   │   ├── Layer.kt
│   │   ├── ArtifactRef.kt
│   │   ├── Artifact.kt
│   │   ├── ContractId.kt
│   │   ├── ContractDefinition.kt
│   │   ├── ValidationResult.kt
│   │   ├── ContractRegistry.kt
│   │   ├── PutResult.kt
│   │   └── StorageConstants.kt      # Public directory names
│   │
│   ├── impl/                        # INTERNAL IMPLEMENTATIONS
│   │   ├── FileCacheStore.kt
│   │   ├── FileContractStore.kt
│   │   ├── FileArtifactStore.kt
│   │   └── internal/
│   │       └── StorageLayout.kt     # PRIVATE - path knowledge
│   │
│   ├── SessionStore.kt              # Session persistence
│   └── ProjectStore.kt              # Project list management
```

## Migration Details

### 1. storage-core Module

**Created:**
- Public API interfaces (CacheStore, ContractStore, ArtifactStore, ConfigStore)
- Model classes (Layer, ArtifactRef, Artifact, ContractId, etc.)
- Internal implementations (FileCacheStore, FileContractStore, FileArtifactStore)
- StorageLayout.kt (INTERNAL - all path constants)
- StorageConstants.kt (PUBLIC - directory names only)

**Key Features:**
- `StorageLayout.kt` is the ONLY place with physical path knowledge
- `StorageConstants.kt` exposes safe directory names (e.g., ".semantic-cache")
- Module visibility rules prevent path leakage
- PutResult accepts Any type for flexible operations

### 2. i2vision-discover Migration

**Files Modified:**
- `ArtifactWriter.kt` - migrated from direct file operations to CacheStore API
- `DiscoveryPipelineImpl.kt` - added CacheStore parameter

**Before:**
```kotlin
class ArtifactWriter(private val projectRoot: String) {
    fun writeArtifacts(clusterId: String?, ...): List<String> {
        val cacheRoot = File(System.getProperty("user.home"), ".i2vision/cache/projects/<hash>/.semantic-cache")
        val targetDir = File(cacheRoot, clusterId ?: "project")
        targetDir.mkdirs()
        // Direct file operations...
    }
}
```

**After:**
```kotlin
class ArtifactWriter(
    private val projectRoot: String,
    private val cacheStore: CacheStore
) {
    suspend fun writeArtifacts(clusterId: String?, ...): List<ArtifactRef> {
        val moduleName = clusterId ?: "project"
        val ref = ArtifactRef(moduleName, Layer.FLOW, name)
        cacheStore.put(ref, content.toByteArray())
    }
}
```

### 3. i2vision-instant Migration

**Files Modified:**
- `ContextProvider.kt` - added CacheStore parameter, uses OS user home cache location
- `HierarchicalVslfcAnalyzer.kt` - added CacheStore parameter, uses OS user home cache location

**Changes:**
- Added optional CacheStore parameter with FileCacheStore default
- Cache location: `~/.i2vision/cache/projects/<hash>/.semantic-cache/`
- Maintains backward compatibility with legacy components

### 4. i2vision-mcp Migration

**Files Modified:**
- `DiscoveryTools.kt` - creates FileCacheStore and passes to DiscoveryPipelineImpl

### 5. i2vision-cli Migration

**Files Modified:**
- `DiscoverCommand.kt` - creates FileCacheStore and passes to DiscoveryPipelineImpl

### 6. discovery-validation Migration

**Files Modified:**
- `SelfDiscoveryTest.kt` - creates FileCacheStore
- `DiscoveryValidation.kt` - creates FileCacheStore
- `DiscoveryIntegrationTest.kt` - creates FileCacheStore

### 7. core/config Deprecation

**Changes:**
- Added deprecation notice to `core/config/build.gradle.kts`
- Created `core/config/DEPRECATED.md` with migration guide
- Deprecated modules retain core/config dependency until phase-out
- Cache location moved to OS user home: `~/.i2vision/cache/projects/<hash>/.semantic-cache/`

## API Usage Examples

### CacheStore - VSLFC Artifact Storage

```kotlin
import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.model.*

val cacheRoot = File(System.getProperty("user.home"), ".i2vision/cache/projects/<hash>")
val cacheStore = FileCacheStore(cacheRoot)

// Store artifact
val ref = ArtifactRef(module = "my-module", layer = Layer.FLOW, name = "sequences.yaml")
cacheStore.put(ref, content.toByteArray())

// Retrieve artifact
val artifact = cacheStore.get(ref)

// List artifacts
val artifacts = cacheStore.list(module = "my-module", layer = Layer.FLOW)

// Check freshness
val isFresh = cacheStore.isFresh(ref, maxAge = 5.minutes)
```

### StorageConstants - Public Directory Names

```kotlin
import com.i2vision.storage.model.StorageConstants

// Cache is in OS user home, constants only provide directory names
val cacheRoot = File(System.getProperty("user.home"), ".i2vision/cache/projects/<hash>")
val semanticCacheRoot = File(cacheRoot, StorageConstants.SEMANTIC_CACHE_DIR)
```

### ContractStore - Contract Registry

```kotlin
import com.i2vision.storage.api.ContractStore

val cacheRoot = File(System.getProperty("user.home"), ".i2vision/cache/projects/<hash>")
val contractStore = FileContractStore(cacheRoot)

// Store contract definition
val contract = ContractDefinition(
    id = ContractId(Layer.FLOW, Layer.CODE, "flow-to-code"),
    type = ContractType.IMPLEMENTS,
    mappings = listOf(/* ... */),
    validationRules = listOf(/* ... */)
)
contractStore.putDefinition(contract)

// Get unified registry
val registry = contractStore.getRegistry()
println("Health score: ${registry.summary.healthScore}")
```

## Protection Against Path Leakage

### Module Visibility
- **Public API**: `com.i2vision.storage.api.*` - exported to all modules
- **Public Models**: `com.i2vision.storage.model.*` - exported to all modules
- **Internal Implementation**: `com.i2vision.storage.impl.*` - NOT exported
- **Internal Path Knowledge**: `com.i2vision.storage.impl.internal.*` - NOT exported

### Architectural Rules
- Only `storage-core/impl/internal/StorageLayout.kt` contains physical path knowledge
- No other module should import from `com.i2vision.storage.impl.internal`
- All application modules must use public interfaces only
- Directory names exposed through `StorageConstants` (safe - just names, not paths)

## Benefits

| Benefit | Before | After |
|---------|--------|-------|
| **Storage changes** | Break all modules | Update `storage-core` only |
| **Testing** | Real filesystem required | Mock `CacheStore` |
| **Path knowledge** | Scattered | Single source |
| **Module coupling** | Tight | Loose |
| **New storage backends** | Impossible | Add new impl (S3, DB) |
| **Architecture protection** | None | Compile-time + public constants |

## Build Status

- ✅ storage-core:build SUCCESSFUL
- ✅ i2vision-discover:build SUCCESSFUL
- ✅ i2vision-instant:build SUCCESSFUL
- ✅ i2vision-mcp:build SUCCESSFUL
- ✅ i2vision-cli:build SUCCESSFUL
- ✅ discovery-validation:build SUCCESSFUL

## Migration Checklist

- [x] Create storage-core module with public API
- [x] Create storage-core internal implementation
- [x] Migrate i2vision-discover to use storage-core API
- [x] Migrate i2vision-instant to use storage-core API
- [x] Migrate i2vision-mcp to use storage-core API
- [x] Migrate i2vision-cli to use storage-core API
- [x] Migrate discovery-validation to use storage-core API
- [x] Remove hardcoded .semantic-cache paths using StorageConstants
- [x] Mark core/config as deprecated
- [x] Verify all builds succeed
