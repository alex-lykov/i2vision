# discovery-engine

Discovery engine for code flow, logic, and architecture analysis.

## Overview

discovery-engine provides configurable discovery parameters and strategies for analyzing code structure:
- **DiscoveryStrategy**: Configurable parameters for flow, logic, and quality filtering
- **DiscoveryManifest**: Manifest for discovery operations
- **ModifiableParameterSet**: Tunable parameters organized by category
- **HybridDiscoveryHeuristics**: Heuristics for hybrid discovery approaches
- **FrameworkProfile**: Framework detection and profiling

## Installation

```kotlin
dependencies {
    implementation("com.i2vision:discovery-engine:1.0.0")
}
```

## Usage

### Creating a Discovery Strategy

```kotlin
import com.i2vision.discovery.DiscoveryStrategy

val strategy = DiscoveryStrategy(
    name = "conservative",
    description = "Conservative discovery with strict quality gates",
    flowConfig = DiscoveryStrategy.FlowDiscoveryConfig(
        maxCallDepth = 5,
        maxStepsPerFlow = 15,
        enableBusinessLogicFilter = true
    ),
    qualityGates = DiscoveryStrategy.QualityGateConfig(
        minBusinessSteps = 1,
        minParticipants = 1,
        maxSelfCallRatio = 0.5
    )
)
```

### Using Modifiable Parameter Set

```kotlin
import com.i2vision.discovery.ModifiableParameterSet

val params = ModifiableParameterSet.from(strategy)

// Modify parameters
params.set("maxCallDepth", 8)
params.set("minBusinessSteps", 2)

// Get modified strategy
val modifiedStrategy = params.toDiscoveryStrategy()
```

### Framework Detection

```kotlin
import com.i2vision.discovery.FrameworkProfile

val profile = FrameworkProfile.detect(projectRoot)
println("Build system: ${profile.buildSystem}")
println("Frameworks: ${profile.frameworks}")
println("Primary language: ${profile.primaryLanguage}")
```

## Features

### Discovery Strategy Configuration

**Flow Discovery Config**:
- Maximum call depth for traversal
- Maximum steps per flow
- Entry point patterns
- Business logic filtering
- Exclusion patterns

**Quality Gate Config**:
- Minimum business steps
- Minimum participants
- Self-call ratio limits
- Error path requirements
- Priority thresholds

**Link Generation Config**:
- Maximum flow links
- Maximum requirement links
- Maximum component links
- Confidence thresholds

### Discovery Stages

- **CODE**: Code structure analysis
- **FLOW**: Flow discovery
- **LOGIC**: Business logic analysis
- **STRUCTURE**: Architecture structure
- **VISION**: Vision layer analysis
- **LINKS**: Link generation
- **TRACEABILITY**: Traceability analysis

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details.
