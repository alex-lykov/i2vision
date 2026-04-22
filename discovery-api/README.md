# discovery-api

Discovery API interfaces for i2vision - breaks circular dependencies.

## Purpose
Defines the contract for discovery pipeline implementations, allowing orchestrator to depend on the interface rather than the concrete implementation.

## Package Structure
`com.i2vision.discover.api` - API interfaces and models

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
- `DiscoveryArtifact` - Discovered artifact with layer, path, content, and confidence
- `DiscoveryDepth` - BROWSE, STANDARD, DEEP
- `DiscoveryGoal` - UNDERSTAND, ANALYZE, VALIDATE, GENERATE, REFACTOR
- `IntentDepth` - BROWSE, STANDARD, DEEP
- `DiscoveryQuality` - FAST, BALANCED, THOROUGH
- `ContractHint` - Contract-based discovery hints
- `DiscoveryIntent` - High-level discovery intent with goal, depth, quality, and layer focus
- `ModifiableParameterSet` - Resolved parameter set with flexible JSON parameters

## Usage

```kotlin
import com.i2vision.discover.api.DiscoveryPipeline
import com.i2vision.discover.api.IntentResolver
import com.i2vision.discover.api.models.DiscoveryDepth
import com.i2vision.discover.api.models.DiscoveryIntent
import com.i2vision.discover.api.models.DiscoveryGoal
import com.i2vision.discover.api.models.IntentDepth
import com.i2vision.discover.api.models.DiscoveryQuality
import kotlinx.coroutines.runBlocking

class MyOrchestrator(
    private val discoveryPipeline: DiscoveryPipeline,
    private val intentResolver: IntentResolver
) {
    fun runDiscovery() = runBlocking {
        // Depth-aware discovery
        val result = discoveryPipeline.discover(
            depth = DiscoveryDepth.STANDARD,
            clusterId = "my-module"
        )

        // Intent-based discovery
        val intent = DiscoveryIntent(
            goal = DiscoveryGoal.UNDERSTAND,
            depth = IntentDepth.STANDARD,
            quality = DiscoveryQuality.BALANCED
        )
        val intentResult = discoveryPipeline.discover(
            intent = intent,
            clusterId = "my-module"
        )
    }
}
```

## Dependencies
- vslfc-core
- architecture-types
- discovery-engine
- contracts
- intent-parser

## License
MIT License

## Version
1.0.0
