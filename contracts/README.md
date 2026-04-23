# contracts

VSLFC contracts and validation for discovery operations.

## Overview

contracts provides the contract system for validating discovery operations and components:

- **ComponentValidator**: Validates directories as valid components/clusters for discovery
- **ContractModels**: Data models for contract definitions
- **ContractValidator**: Validates contract definitions
- **ContractLoader**: Loads contracts from YAML/JSON files

## Installation

```kotlin
dependencies {
    implementation("com.i2vision:contracts:1.0.0")
}
```

## Usage

### Component Validation

```kotlin
import com.i2vision.contracts.ComponentValidator

val validator = ComponentValidator(projectRoot)
val result = validator.validate("core/orchestrator")

if (result.isValid) {
    println("Valid component: ${result.category}")
    println("Confidence: ${result.confidence}")
} else {
    println("Excluded: ${result.reason}")
}
```

### Loading Contracts

```kotlin
import com.i2vision.contracts.ContractLoader

val loader = ContractLoader(projectRoot)
val contracts = loader.loadAll()

contracts.forEach { contract ->
    println("Contract: ${contract.name}")
    println("Type: ${contract.type}")
}
```

## Features

### Component Categories

- **SOURCE**: Primary source code
- **TEST**: Test code
- **DOCUMENTATION**: Docs, examples
- **BUILD_ARTIFACT**: Generated/build output
- **CACHE**: Semantic cache, etc.
- **CONFIG**: Configuration files
- **EMPTY**: No meaningful content

### Validation Philosophy

- **Hard exclusions** (cache, build artifacts) → NOT discovered at all
- **Soft exclusions** (tests, docs) → Discovered with quality tracking
- **Source directories** → Discovered normally

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details.
