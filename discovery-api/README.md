# discovery-api

Discovery API interfaces for i2vision - breaks circular dependencies.

## Purpose
Defines the contract for discovery pipeline implementations, allowing orchestrator to depend on the interface rather than the concrete implementation.

## Components

### DiscoveryPipeline Interface
Main entry point for discovery operations:
- `discover(depth, clusterId, contracts)` - Depth-aware discovery
- `discover(intent, clusterId, contracts)` - Intent-based discovery

### IntentResolver Interface
Resolves high-level intents to concrete discovery parameters:
- `resolveToParameterSet(intent)` - Resolve intent to parameters
- `isValid(intent)` - Validate intent
- `validate(intent)` - Get validation errors

### Data Models
- `PipelineResult` - Result of discovery pipeline execution
- `DiscoveryArtifact` - Discovered artifact
- `DiscoveryDepth` - BROWSE, STANDARD, DEEP
- `ContractHint` - Contract-based discovery hints
- `DiscoveryIntent` - High-level discovery intent
- `ModifiableParameterSet` - Resolved parameter set

## Usage

```kotlin
import com.i2vision.discover.api.DiscoveryPipeline
import com.i2vision.discover.api.IntentResolver

class MyOrchestrator(
    private val discoveryPipeline: DiscoveryPipeline,
    private val intentResolver: IntentResolver
) {
    fun runDiscovery() {
        val result = discoveryPipeline.discover(
            depth = DiscoveryDepth.STANDARD,
            clusterId = "my-module"
        )
    }
}
```

## Dependencies
- vslfc-core
- architecture-types
- llm-client
- discovery-engine
- contracts
- intent-parser

## License
MIT License

## Version
1.0.0
