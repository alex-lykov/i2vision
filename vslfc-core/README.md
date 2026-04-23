# vslfc-core

Core data models and contracts for VSLFC (Vision-Structure-Logic-Flow-Code) layered discovery.

## Overview

vslfc-core provides the foundational data structures and contract system for the VSLFC layered discovery framework. It
defines the contract models, validation, and loading mechanisms used across the i2vision ecosystem.

## Features

- **Contract Models**: Complete data model for VSLFC discovery contracts
- **Contract Loading**: YAML-based contract loading with caching
- **Contract Validation**: Comprehensive validation with error and warning reporting
- **Layer Expectations**: Define expectations for Vision, Structure, Logic, Flow, and Code layers
- **Quality Gates**: Enforce quality standards during discovery

## Installation

```kotlin
dependencies {
    implementation("com.i2vision:vslfc-core:1.0.0")
}
```

## Usage

### Loading Contracts

```kotlin
import com.i2vision.vslfc.contracts.ContractLoader

val loader = ContractLoader()
val contract = loader.loadContract("authentication")

if (contract != null) {
    println("Loaded contract: ${contract.metadata.name}")
}
```

### Validating Contracts

```kotlin
import com.i2vision.vslfc.contracts.ContractValidator

val validator = ContractValidator()
val result = validator.validate(contract)

if (result.isValid) {
    println("Contract is valid")
} else {
    result.errors.forEach { error ->
        println("Error: ${error.field} - ${error.message}")
    }
}
```

### Creating Contracts Programmatically

```kotlin
import com.i2vision.vslfc.contracts.*

val contract = DiscoveryContract(
    metadata = ContractMetadata(
        name = "my-contract",
        version = "1.0",
        description = "My custom discovery contract",
        targetLayers = listOf("all")
    ),
    layerExpectations = LayerExpectations(
        vision = VisionExpectations(
            requiredCapabilities = listOf("authentication", "authorization")
        )
    ),
    discoveryConfig = DiscoveryConfig(
        depth = DiscoveryDepth.STANDARD,
        includePatterns = listOf("**/*.kt")
    ),
    qualityGates = QualityGates(
        minCoveragePercentage = 80.0
    )
)
```

## Contract Structure

```yaml
contract:
  name: authentication
  version: "1.0"
  description: "Authentication-focused discovery contract"
  target_layers: ["all"]
  priority: HIGH

layer_expectations:
  vision:
    required_capabilities:
      - authentication
      - authorization
    constraints:
      - No hardcoded credentials

discovery_config:
  depth: STANDARD
  include_patterns:
    - "**/*.kt"
    - "**/*.java"
  exclude_patterns:
    - "**/test/**"
  auto_discover: true

quality_gates:
  min_coverage_percentage: 80.0
  max_orphaned_rules: 10
  enforce_test_coverage: true
```

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please read [contributing.md](contributing.md) for details.
