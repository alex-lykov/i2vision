# storage-core

Storage abstraction layer for the i2vision modular ecosystem. Provides unified storage interfaces with protected physical layout knowledge.

## Architecture

storage-core is the **single source of truth** for all physical storage paths in the i2vision ecosystem.

```
┌─────────────────────────────────────────────────────────────────┐
│                   APPLICATION MODULES                             │
│  i2vision-discover  i2vision-instant  i2vision-mcp  i2vision-develop│
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
│  • SessionStore - Session persistence                            │
│  • ProjectsStore - Project list management                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              storage-core (INTERNAL IMPLEMENTATION)               │
│                                                                  │
│  • FileCacheStore - Knows user home cache layout (via I2VisionPaths)                 │
│  • FileContractStore - Knows .vision-ai layout                   │
│  • StorageLayout - PRIVATE! All path constants here              │
└─────────────────────────────────────────────────────────────────┘
```

## Module Structure

```
storage-core/
├── src/main/kotlin/com/i2vision/storage/
│   │
│   ├── api/                         # PUBLIC INTERFACES (No path knowledge)
│   │   ├── CacheStore.kt            # VSLFC artifact storage
│   │   ├── ContractStore.kt         # Contract registry operations
│   │   ├── ArtifactStore.kt         # VSLFC artifact operations
│   │   └── ConfigStore.kt           # Configuration storage
│   │
│   ├── model/                       # PUBLIC DATA MODELS
│   │   ├── Layer.kt                 # VSLFC layer enumeration
│   │   ├── ArtifactRef.kt           # Artifact reference
│   │   ├── Artifact.kt              # Artifact with metadata
│   │   ├── ContractId.kt            # Contract identifier
│   │   ├── ContractDefinition.kt    # Contract definition
│   │   ├── ValidationResult.kt       # Validation result
│   │   └── ContractRegistry.kt      # Unified contract registry
│   │
│   ├── impl/                        # INTERNAL IMPLEMENTATIONS (Path knowledge here!)
│   │   ├── FileCacheStore.kt        # Knows .semantic-cache layout
│   │   ├── FileContractStore.kt     # Knows .vision-ai layout
│   │   ├── FileArtifactStore.kt     # Knows artifact paths
│   │   └── internal/
│   │       └── StorageLayout.kt     # PRIVATE! All path constants here
│   │
│   ├── SessionStore.kt              # Session persistence
│   └── ProjectStore.kt              # Project list management
```

## Public API Usage

### CacheStore - VSLFC Artifact Storage

```kotlin
import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.model.*
import java.io.File

// Create store (only storage-core knows physical layout)
val cacheStore = FileCacheStore(File(projectRoot))

// Store artifact (caller doesn't know where it's stored)
val ref = ArtifactRef(module = "my-module", layer = Layer.FLOW, name = "sequences.yaml")
val result = cacheStore.put(ref, content.toByteArray())

// Retrieve artifact
val artifact = cacheStore.get(ref)

// List artifacts by module and layer
val artifacts = cacheStore.list(module = "my-module", layer = Layer.FLOW)

// Check freshness
val isFresh = cacheStore.isFresh(ref, maxAge = 5.minutes)

// Get artifacts affected by file changes
val affected = cacheStore.getAffected(changedFiles = listOf("src/main/kotlin/Service.kt"))
```

### ContractStore - Contract Registry

```kotlin
import com.i2vision.storage.api.ContractStore
import com.i2vision.storage.model.*

val contractStore = FileContractStore(File(projectRoot))

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

### Architectural Rules
- Only `storage-core/impl/internal/StorageLayout.kt` contains physical path knowledge
- No other module should import from `com.i2vision.storage.impl.internal`
- All application modules must use public interfaces only

## Features

### CacheStore
- Store/retrieve VSLFC artifacts by module, layer, and name
- List artifacts by module and layer
- Check artifact freshness with TTL
- Get artifacts affected by file changes (incremental sync)
- Clear artifacts by module

### ContractStore
- Store contract definitions (design-time)
- Store validation results (runtime)
- Get unified contract registry with health score
- List all contract definitions

### ArtifactStore
- Store artifacts with metadata
- Delete artifacts
- List artifacts by module or layer

### SessionStore
- Session persistence (in-memory or database-backed)
- Automatic cleanup of inactive sessions
- Project path association

### ProjectsStore
- Project list management
- Active project tracking
- Simple CRUD operations

## Installation

```kotlin
dependencies {
    implementation("com.i2vision:storage-core:1.0.0")
}
```

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please read [contributing.md](contributing.md) for details.

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please read [contributing.md](contributing.md) for details.
