# i2vision-instant

## Purpose

i2vision-instant provides instant context and analysis capabilities for code intelligence. It serves as the core context provider that delivers rich, multi-layered code understanding to IDEs and LLMs.

## Architecture

The module is organized around the VSLFC (Vision, Structure, Logic, Flow, Code) layered architecture:

### Core Components

**ContextProvider** (`context/`)
- Primary entry point for instant context generation
- Integrates artifact discovery, strategy suggestions, and advanced analysis
- Provides cache management for performance optimization
- Supports both single-file and multi-file context requests

**Artifact Discovery** (`artifact/`)
- `GenericArtifactLoader`: Flexible artifact loading from directories
- `ArtifactDiscoveryConfig`: Configurable discovery rules for different layers
- Supports VSLFC-specific and generic discovery patterns
- YAML-based configuration for easy customization

**Analysis** (`analysis/`)
- `FileAnalyzer`: Advanced file-level analysis
  - Complexity metrics (cyclomatic, cognitive)
  - Pattern detection (design patterns, anti-patterns)
  - Code smell detection (long methods, god classes, etc.)
  - Maintainability index calculation

**Hierarchical Analysis** (`analyzer/`)
- `HierarchicalVslfcAnalyzer`: Cluster detection and hierarchical analysis
- `ClusterMetricsAggregator`: Aggregates cluster-level VSLFC metrics
- Supports subfolder cluster detection within modules
- Provides hotspots and improvement suggestions

**Strategy Library** (`strategy/`)
- `StrategyLibrary`: Discovery strategy integration
- Pre-built strategies for different project types
- Confidence-based strategy suggestions
- File type detection and categorization

**Cache Management** (`cache/`)
- `CacheManager`: Thread-safe context caching
- Expiry-based cache invalidation
- Pattern-based cache cleanup
- Cache statistics and monitoring

**Proactive Context** (`proactive/`)
- `ProactiveContext`: Proactive IDE suggestions
- Cross-module analysis for related files
- Quality metrics aggregation

## Data Models

### InstantContext
```kotlin
data class InstantContext(
    val modulePath: String,
    val files: List<FileInfo>,
    vslfcArtifacts: Map<String, Any>,
    artifacts: List<Artifact>,
    strategySuggestions: List<StrategySuggestion>,
    complexityDetails: ComplexityDetails?,
    // ... additional fields
)
```

### Quality Metrics
- Complexity scores (cyclomatic, cognitive)
- Cohesion metrics (component cohesion)
- Coupling analysis
- Maintainability index

## Usage

### Basic Context Request
```kotlin
val contextProvider = ContextProvider(projectRoot)
val context = contextProvider.getContext(filePath, task)
```

### CLI Integration
The ContextProvider is integrated with the i2vision CLI through the `context` command:

**Basic Context (works immediately):**
```bash
# Get context for a file
i2vision context file --path=AuthService.kt --task=debug

# Get context for multiple files
i2vision context files file1.kt file2.kt file3.kt
```

**Enhanced Context (requires discovery):**
```bash
# Get enhanced context with flows, rules, components
i2vision context enhanced --path=AuthService.kt
```

**Cache Management:**
```bash
# Check cache status
i2vision context cache stats

# Clean expired cache
i2vision context cache clean

# Invalidate specific patterns
i2vision context cache invalidate --pattern=*.kt
```

**Cache Detection:**
The `hasDiscoveryCache()` method checks if discovery artifacts exist for a module:
```kotlin
val hasCache = contextProvider.hasDiscoveryCache("i2vision-instant")
// Returns true if .semantic-cache/i2vision-instant/flow and logic directories exist
```

### Cache Management
```kotlin
contextProvider.invalidateCache(pattern)
contextProvider.cleanCache()
val stats = contextProvider.getCacheStats()
```

### Artifact Discovery
```kotlin
val config = ArtifactDiscoveryConfig.load(configPath)
val loader = GenericArtifactLoader(projectRoot, config)
val artifacts = loader.loadArtifacts(modulePath)
```

### Hierarchical Analysis
```kotlin
val analyzer = HierarchicalVslfcAnalyzer(projectRoot)
val result = analyzer.analyzeModuleWithClusters(modulePath)
```

## Dependencies

- `i2vision-discover`: Discovery engine integration
- `vslfc-core`: VSLFC data structures
- `index-provider`: Index-based lookups
- `storage-core`: Semantic cache access
- `link-service`: Cross-module link analysis
- Jackson + SnakeYAML: JSON/YAML parsing
- SLF4J: Logging
- Kotlin Coroutines: Async operations

## Configuration

Artifact discovery is configured via YAML:

```yaml
layers:
  - name: vision
    patterns: ["**/*.md", "**/*.yaml"]
  - name: structure
    patterns: ["**/build.gradle.kts", "**/pom.xml"]
```

## Performance

- Context caching with 5-minute default expiry
- Parallel artifact loading
- Incremental analysis with cache invalidation
- Confidence-based early termination

## Integration Points

- **i2vision-mcp**: Context tools for MCP protocol
- **Launcher**: CLI integration for instant context
- **Orchestrator**: Discovery pipeline integration
