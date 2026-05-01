# intent-parser

Intent parsing and models for high-level user intent specification.

## Overview

intent-parser provides a declarative API for specifying discovery intents, abstracting away low-level parameter
configuration:

- **DiscoveryIntent**: High-level intent specification with goal, focus, depth, and quality preferences
- **IntentParser**: Parse intents from CLI arguments or configuration
- **IntentFeatureFlags**: Feature flags for gradual rollout of intent-based systems

## Installation

```kotlin
dependencies {
    implementation("com.i2vision:intent-parser:1.0.0")
}
```

## Usage

### Creating Intents

```kotlin
import com.i2vision.intent.DiscoveryIntent
import com.i2vision.intent.IntentGoal
import com.i2vision.intent.LayerFocus
import com.i2vision.intent.IntentDepth
import com.i2vision.intent.QualityFocus

val intent = DiscoveryIntent(
    goal = IntentGoal.FULL_DISCOVERY,
    focus = LayerFocus.CORE,
    depth = IntentDepth.STANDARD,
    quality = QualityFocus.BALANCED
)
```

### Parsing from CLI Arguments

```kotlin
import com.i2vision.intent.IntentParser

val args = mapOf(
    "intent" to "full_discovery",
    "focus" to "structure,logic,flow",
    "depth" to "standard",
    "quality" to "balanced"
)

val intent = IntentParser.parse(args)
```

### Validation

```kotlin
val intent = DiscoveryIntent(
    goal = IntentGoal.FLOW_MAPPING,
    focus = setOf(LayerFocus.CODE) // Invalid: FLOW_MAPPING requires FLOW focus
)

if (!intent.isValid()) {
    val errors = intent.validate()
    println("Invalid intent: ${errors.joinToString(", ")}")
}
```

## Features

### Intent Goals

- **FULL_DISCOVERY**: Complete discovery of all layers
- **REFACTORING_ANALYSIS**: Focus on refactoring opportunities
- **ARCHITECTURE_AUDIT**: Audit architecture and dependencies
- **FLOW_MAPPING**: Map flows and interactions
- **DOCUMENTATION_GENERATION**: Generate documentation
- **QUICK_OVERVIEW**: Quick overview for exploration

### Layer Focus

- **VISION**: Vision layer analysis
- **STRUCTURE**: Structure layer analysis
- **LOGIC**: Logic layer analysis
- **FLOW**: Flow layer analysis
- **CODE**: Code layer analysis
- Predefined sets: `ALL`, `CORE`, `ANALYSIS`

### Depth Levels

- **BROWSE**: Shallow scan for quick overview
- **STANDARD**: Standard depth for most use cases
- **DEEP**: Deep analysis with LLM enhancement

### Quality Focus

- **QUALITY**: Prioritize quality over quantity
- **BALANCED**: Balanced approach
- **QUANTITY**: Prioritize maximum discovery

### Verbalization Configuration

Control how code symbols are described in natural language:

```kotlin
import com.i2vision.intent.VerbalizationConfig
import com.i2vision.vslfc.VerbalizationStrategy

val intent = DiscoveryIntent(
    goal = IntentGoal.DOCUMENTATION_GENERATION,
    verbalization = VerbalizationConfig.BASIC // Incremental strategy
)

// Or with custom settings
val customVerbalization = VerbalizationConfig(
    enabled = true,
    strategy = VerbalizationStrategy.MULTI_PASS,
    customPatternsPath = ".vision-ai/patterns.yaml",
    feedbackEnabled = true
)
```

#### Verbalization Modes

- **DISABLED**: No verbalization (default)
- **BASIC**: Incremental verbalization for speed
- **QUALITY**: Multi-pass verbalization for accuracy
- **LEARNING**: LLM-enhanced with user feedback

#### CLI Arguments

```bash
# Simple verbalization modes
--verbalize=basic      # Incremental strategy
--verbalize=quality    # Multi-pass strategy  
--verbalize=learning   # Learning strategy with feedback

# Detailed configuration
--verbalization-strategy=multi_pass
--verbalization-patterns=.vision-ai/patterns.yaml
--verbalization-feedback=true
```

## Feature Flags

Control intent system behavior via environment variables:

```bash
# Enable/disable intent system
export I2VISION_INTENT_ENABLED=true

# Enable intent resolution logging
export I2VISION_INTENT_LOGGING=true

# Enable strict validation mode
export I2VISION_INTENT_STRICT=true

# Enable intent-only mode (disable strategy system)
export I2VISION_INTENT_ONLY=false

# Enable verbalization features
export I2VISION_VERBALIZATION_ENABLED=true

# Enable verbalization feedback collection
export I2VISION_VERBALIZATION_FEEDBACK=false
```

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please read [contributing.md](contributing.md) for details.
