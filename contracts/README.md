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

### VSLFC Layer Contracts

VSLFC (Vision-Structure-Logic-Flow-Code) layers use a simplified contract structure:

```
.vision-ai/
├── .vision/
│   ├── agent-config.yaml
│   └── contract.yaml          # All Vision contracts
├── .structure/
│   ├── agent-config.yaml
│   └── contract.yaml          # All Structure contracts
├── .logic/
│   ├── agent-config.yaml
│   └── contract.yaml          # All Logic contracts
├── .flow/
│   ├── agent-config.yaml
│   └── contract.yaml          # All Flow contracts
└── .code/
    ├── agent-config.yaml
    └── contract.yaml          # All Code contracts
```

Each `contract.yaml` contains all contracts for that layer:
- Documentation contracts (mapping doc sections to layer fields)
- Inter-layer contracts (incoming/outgoing between layers)
- Validation rules

#### Example contract.yaml

```yaml
# Vision Layer Contracts
version: "1.0"
layer: VISION

documentation:
  primary: "README.md"
  secondary: []

contracts:
  - id: "vision-documentation"
    name: "Vision Documentation Contract"
    mappings:
      - doc_section: "## Purpose"
        layer_field: "requirements"
        parser: "free_text"
        confidence: 0.9

  - id: "vision-to-structure"
    name: "Vision to Structure Contract"
    direction: "outgoing"
    mappings:
      - requirement_pattern: "REQ-.*"
        component_pattern: ".*Service"

validation:
  - rule: "every_doc_requirement_has_code_evidence"
    severity: "warning"
```

#### Loading VSLFC Layer Contracts

```kotlin
import com.i2vision.vslfc.DocContractYamlParser
import com.i2vision.vslfc.VSLFCLayerContracts

val parser = DocContractYamlParser(projectRoot)
val visionContracts = parser.parseAllContractsForLayer(VSLFCLayerContracts.Layer.VISION)

visionContracts.forEach { contract ->
    println("Contract: ${contract.contractId}")
    println("Layer: ${contract.layer}")
}
```

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
