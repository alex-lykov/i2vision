# verbalization-core

Core verbalization engine for transforming code patterns into natural language descriptions.

## Overview

verbalization-core provides the foundational components for converting code symbols into human-readable descriptions. It implements three verbalization strategies (Incremental, Multi-Pass, Learning) and supports custom pattern matching for project-specific descriptions.

## Features

- **Incremental Strategy**: Only verbalizes changed symbols using hash-based change detection
- **Multi-Pass Strategy**: Refines descriptions with broader architectural context
- **Learning Strategy**: Uses LLM with user feedback for highest quality descriptions
- **Custom Patterns**: User-defined regex patterns for project-specific verbalization
- **Hash Management**: Tracks symbol changes to avoid redundant processing
- **Storage Integration**: Works with storage-core for caching and persistence

## Installation

```kotlin
dependencies {
    implementation("com.i2vision:verbalization-core:1.0.0")
}
```

## Usage

### Basic Verbalization

```kotlin
import com.i2vision.verbalization.DefaultVerbalizationEngine
import com.i2vision.verbalization.VerbalizationStrategy
import com.i2vision.intent.DiscoveryIntent

val engine = DefaultVerbalizationEngine(cacheStore)
val symbols = listOf(
    Symbol(
        name = "authenticate",
        kind = SymbolKind.FUNCTION,
        filePath = "core/auth/AuthService.kt",
        lineNumber = 25,
        content = "suspend fun authenticate(credentials: Credentials): Token"
    )
)

val results = engine.verbalize(
    clusterId = "core/auth",
    symbols = symbols,
    strategy = VerbalizationStrategy.INCREMENTAL,
    intent = DiscoveryIntent(/* ... */)
)

results.forEach { result ->
    println("${result.symbol.name}: ${result.description}")
    // Output: authenticate: Asynchronously executes authentication operation
}
```

### Custom Patterns

```kotlin
import com.i2vision.verbalization.VerbalizationPattern
import java.io.File

// Register custom pattern
val customPattern = VerbalizationPattern(
    codePattern = "class (\\w+)Agent",
    description = "AI Agent for $1 operations",
    confidence = 0.9
)
engine.registerPattern(customPattern)

// Load patterns from config file
engine.loadCustomPatterns(File(".vision-ai/config/verbalization-patterns.yaml"))
```

### Configuration File

```yaml
# .vision-ai/config/verbalization-patterns.yaml
patterns:
  - code_pattern: "class (\\w+)Agent"
    description: "AI Agent for $1 operations"
    confidence: 0.9

  - code_pattern: "@RestController"
    description: "REST API endpoint"
    confidence: 0.95

  - code_pattern: "require\\((.+)\\)"
    description: "Validates that $1"
    confidence: 0.85
```

## Architecture

### Core Components

- **VerbalizationEngine**: Main interface for verbalization operations
- **PatternMatcher**: Matches code against patterns to generate descriptions
- **HashManager**: Tracks symbol changes for incremental processing
- **Strategies**: Different approaches to verbalization (Incremental, Multi-Pass, Learning)

### Strategy Comparison

| Strategy | When to Use | Performance | Quality | Context Awareness |
|----------|-------------|-------------|---------|-------------------|
| **Incremental** | Default, fast scans | ⚡ Fast | Medium | Low |
| **Multi-Pass** | Architecture analysis | Medium | High | High |
| **Learning** | Quality-focused, with LLM | Slow | Highest | Highest |

### Storage Structure

```
.semantic-cache/{cluster}/verbalization/
├── verbalizations.yaml        # Generated descriptions
└── .meta/
    └── hashes.yaml           # Change tracking
```

## Integration Points

### With Discovery Pipeline

```kotlin
// In DiscoveryPipelineImpl
val symbols = extractSymbols(cluster)
val verbalizations = verbalizationEngine.verbalize(
    clusterId, symbols, strategy, intent
)
// Store both symbols and verbalizations
```

### With Instant Context

```kotlin
// In ContextProvider
fun getContext(filePath: String): InstantContext {
    val symbols = indexProvider.extractSymbols(filePath)
    val verbalizations = verbalizationEngine.getCachedVerbalizations(symbols)
    return InstantContext(symbols = symbols, verbalizations = verbalizations)
}
```

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details.
