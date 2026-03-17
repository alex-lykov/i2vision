# i2vision-discover

Full discovery engine for i2vision - Code → Vision discovery.

## Purpose
Implements the discovery pipeline interfaces to extract layered architecture from code:
- Code → Flows → Logic → Structure → Vision

## Current Status
**Phase 1: Interface Implementation** (Minimal)
- DiscoveryPipeline interface implemented with minimal functionality
- IntentResolver interface implemented with basic validation
- Ready for orchestrator integration

**Phase 2: Full Implementation** (Blocked)
- Original DiscoveryPipeline (2550 lines) requires orchestrator internal dependencies:
  - IndexProvider
  - LinkService
  - TaskExecutor
  - VisionAiStructureManager
- Enhancement deferred until orchestrator internal components are extracted

## Components

### DiscoveryPipelineImpl
Implementation of DiscoveryPipeline interface:
- Depth-based discovery (BROWSE, STANDARD, DEEP)
- Intent-based discovery
- Contract-based discovery

### IntentResolverImpl
Implementation of IntentResolver interface:
- Intent to parameter set resolution
- Intent validation
- Error reporting

## Usage

```kotlin
import com.i2vision.discover.pipeline.DiscoveryPipelineImpl
import com.i2vision.discover.intent.IntentResolverImpl

val intentResolver = IntentResolverImpl()
val discoveryPipeline = DiscoveryPipelineImpl(
    projectRoot = "/path/to/project",
    intentResolver = intentResolver
)

val result = discoveryPipeline.discover(
    depth = DiscoveryDepth.STANDARD,
    clusterId = "core/orchestrator"
)
```

## Dependencies
- discovery-api (interfaces)
- vslfc-core
- architecture-types
- llm-client
- discovery-engine
- contracts
- intent-parser
- conf-agent-core
- storage-core

## Architecture
```
i2vision-discover
├── pipeline/
│   └── DiscoveryPipelineImpl.kt
├── intent/
│   └── IntentResolverImpl.kt
├── artifact/
│   └── ArtifactWriter.kt
└── flow/
    └── FlowDiscovery.kt
```

## Parallel Discovery & Concurrency Safety

### Performance
- **82% faster**: 4m 41s vs 26m 51s through parallel cluster processing
- Success rate: 92-100% with graceful degradation

### Concurrency Safety Mechanisms

#### Cluster-Level Locking
- **File**: `artifact/ArtifactWriter.kt`
- **Implementation**: Mutex per cluster prevents concurrent write corruption
- **Details**: Each cluster gets its own Mutex lock. Artifact writes are synchronized per cluster, allowing parallel writes to different clusters while preventing concurrent writes to the same cluster.

#### Graceful Degradation
- **File**: `flow/FlowDiscovery.kt`
- **Implementation**: Try-catch around call graph building
- **Details**: Prevents "Nodes must be provided" errors from failing entire clusters. When call graph building fails or returns empty, flow discovery returns empty results instead of throwing exceptions.

### Sequence Diagrams
- [`docs/seqdiag/parallel-discovery-concurrency.sd`](../../docs/seqdiag/parallel-discovery-concurrency.sd) - Detailed concurrency techniques
- [`docs/seqdiag/unified-framework-discovery-flow.sd`](../../docs/seqdiag/unified-framework-discovery-flow.sd) - Unified discovery flow with synchronization

## License
MIT License

## Version
1.0.0
