# Architecture Types Module

A standalone, reusable library for multi-dimensional architecture detection and cluster analysis. This module provides tools for detecting, analyzing, and comparing software architecture patterns across projects.

## Purpose

The architecture-types module enables both discovery and development engines to:
- Detect build systems, frameworks, and architectural patterns
- Detect source code clusters dynamically without hardcoding paths
- Analyze multi-dimensional architecture signatures
- Compare architectural patterns for compatibility
- Plan migration paths between architectures
- Use LLM fallback for low-confidence detections (optional)

## Key Features

### Symbiotic Cluster and Architecture Detection

Cluster detection and architecture detection work together symbiotically:
1. **Cluster Detection First**: Detects clusters using build module boundaries (Gradle/Maven) or directory structure
2. **Per-Cluster Architecture Detection**: Detects architecture patterns for each cluster independently
3. **Confidence Evaluation**: Evaluates detection confidence and uses LLM fallback when needed
4. **Optional LLM Support**: LLM is only called when confidence < threshold (default 0.7)

### Multi-Dimensional Architecture Detection

A single project can have multiple architectural patterns simultaneously across different modules. This module supports per-module detection of:
- **Build Level**: Build system (Gradle, Maven, npm, Cargo) and build tools
- **Framework Level**: Frameworks and libraries (per-module)
- **Module Level**: Module patterns (Hexagonal, Layered, Agent, Microservices, etc.)
- **Cluster Level**: Source code clusters for organization (dynamic detection)
- **Code Level**: Design patterns and language features (per-module)
- **Deployment Level**: Deployment patterns (Monolith, Microservices, Aggregator)

### Core Components

#### Detectors (`detector/`)
- `BuildSystemDetector`: Detects build system from project root files
- `FrameworkDetector`: Detects frameworks from build files and source code
- `ModulePatternDetector`: Analyzes directory structure for architectural patterns

#### Signature (`signature/`)
- `ArchitectureSignature`: Multi-dimensional architecture signature data class
- `SignatureBuilder`: Aggregates detector outputs into complete signatures
- `SignatureMatcher`: Provides compatibility checks and similarity scoring

#### Patterns (`patterns/`)
- `Pattern`: Common architectural patterns (Hexagonal, Layered, Agent, Pipeline, Event-Driven)
- Pattern catalog for matching and classification

#### Compatibility (`compatibility/`)
- `PatternCompatibility`: Checks compatibility between architectural patterns
- `MigrationPath`: Defines migration steps and effort estimation

## Usage

### Basic Architecture Detection

```kotlin
import com.i2vision.arch.signature.SignatureBuilder

val builder = SignatureBuilder(projectRoot)
val signature = builder.build()

println("Build System: ${signature.buildSystem}")
println("Module Patterns: ${signature.modulePatterns}")
println("Clusters: ${signature.clusters}")
println("All Frameworks: ${signature.getAllFrameworks()}")
```

### Architecture Detection with LLM Fallback

```kotlin
import com.i2vision.arch.signature.SignatureBuilder

// Enable LLM fallback for low-confidence detections (confidence < 0.7)
val builder = SignatureBuilder(
    projectRoot = projectRoot,
    confidenceThreshold = 0.7,
    useLlmForLowConfidence = true,
    llmClient = myLlmClient  // Optional LLM client
)
val signature = builder.build()

// Check which modules used LLM fallback
signature.confidence.forEach { (module, confidence) ->
    if (confidence < 0.7) {
        println("$module used LLM fallback (confidence: $confidence)")
    }
}
```

### Cluster Detection Only

```kotlin
import com.i2vision.arch.signature.SignatureBuilder

val builder = SignatureBuilder(projectRoot)
val signature = builder.build()

// Get detected clusters (from build modules or directory structure)
signature.clusters.forEach { cluster ->
    println("Cluster: ${cluster.name} (${cluster.fileCount} files)")
}
```

### Per-Module Analysis

```kotlin
val signature = SignatureBuilder(projectRoot).build()

// Get architecture for a specific module
val coreModule = signature.getModuleSignature("core")
println("Core module pattern: ${coreModule?.modulePattern}")
println("Core frameworks: ${coreModule?.frameworks}")
```

### Compatibility Checking

```kotlin
import com.i2vision.arch.signature.SignatureMatcher
import com.i2vision.arch.compatibility.PatternCompatibility

val matcher = SignatureMatcher()
val signature1 = SignatureBuilder(projectRoot1).build()
val signature2 = SignatureBuilder(projectRoot2).build()

// Check if signatures are compatible
if (matcher.areCompatible(signature1, signature2)) {
    println("Architectures are compatible")
}

// Get compatibility issues
val compatibility = PatternCompatibility()
val issues = compatibility.getCompatibilityIssues(signature1, signature2)
issues.forEach { println(it) }
```

### Pattern Matching

```kotlin
import com.i2vision.arch.patterns.PatternCatalog

val signature = SignatureBuilder(projectRoot).build()
val catalog = PatternCatalog.getAllPatterns()

val matcher = SignatureMatcher()
val bestMatch = matcher.findBestMatch(signature, catalog)
println("Best matching pattern: ${bestMatch?.name}")
```

## Architecture Signature Structure

```kotlin
data class ArchitectureSignature(
    // Build level (global)
    val buildSystem: BuildSystem?,
    val buildTools: List<BuildTool>,
    
    // Framework level (per-module)
    val moduleFrameworks: Map<String, List<Framework>>,
    val libraries: List<Library>,
    
    // Module level (per-module)
    val modulePatterns: Map<String, ModulePattern>,
    
    // Cluster level (global - source code clusters)
    val clusters: List<ClusterInfo>,
    
    // Code level (per-module)
    val moduleDesignPatterns: Map<String, List<DesignPattern>>,
    val moduleLanguageFeatures: Map<String, List<LanguageFeature>>,
    
    // Deployment level (global)
    val deploymentPattern: DeploymentPattern,
    
    // Meta (includes confidence scores for LLM fallback decisions)
    val confidence: Map<String, Double>,
    val detectedAt: Instant
)
```

## Multi-Dimensional Architecture

A single project can have multiple architectural patterns simultaneously:

```
Project Root
├── core/                    ← Hexagonal Architecture
│   ├── domain/
│   ├── application/
│   └── infrastructure/
├── services/                ← Microservices
│   ├── auth-service/
│   └── payment-service/
├── shared/                  ← Shared Kernel (DDD)
│   └── contracts/
└── launcher/                ← MVC/Desktop
    ├── ui/
    ├── controllers/
    └── models/
```

The architecture-types module detects each module's architecture independently, providing a complete multi-dimensional signature.

## Integration

### Gradle Dependency

```kotlin
dependencies {
    implementation(project(":architecture-types"))
}
```

### With Discovery Engine

The TaskExecutor in the orchestrator module uses architecture-types for architecture detection with LLM support:

```kotlin
import com.i2vision.arch.signature.SignatureBuilder

// Basic detection (no LLM)
val signatureBuilder = SignatureBuilder(projectRoot)
val signature = signatureBuilder.build()

// With LLM fallback for low-confidence clusters
val signatureBuilder = SignatureBuilder(
    projectRoot = projectRoot,
    confidenceThreshold = 0.7,
    useLlmForLowConfidence = true,
    llmClient = llmClient
)
val signature = signatureBuilder.build()
```

## Cluster Detection

The module detects clusters dynamically without hardcoding paths:

1. **Build Module Boundaries (Primary)**: Extracts modules from `settings.gradle.kts` or `pom.xml`
2. **Directory Structure (Fallback)**: Scans project structure for source directories
3. **Source File Counting**: Counts files in each cluster to determine cluster size

### Build Module Detection

```kotlin
private fun detectBuildModules(root: File): List<String> {
    // Detects Gradle multi-module projects from settings.gradle.kts
    // Detects Maven multi-module projects from pom.xml
    // Returns list of module names
}
```

### Directory Structure Clustering

When build modules are not detected, the module falls back to directory structure clustering:
- Scans top-level directories
- Checks for source files (kt, java, scala, groovy, py, js, ts, go, rs)
- Includes nested subdirectories as separate clusters
- Sorts clusters by file count (largest first)

## LLM Fallback Strategy

### Confidence Evaluation

The module calculates confidence scores for each module's architecture detection based on:
- Frameworks detected (contributes 0.3 to confidence)
- Design patterns detected (contributes 0.35 to confidence)
- Language features detected (contributes 0.35 to confidence)

### LLM Fallback Triggers

LLM fallback is triggered when:
- Module confidence < threshold (default 0.7)
- LLM is enabled (`useLlmForLowConfidence = true`)
- LLM client is available

### LLM Fallback Behavior

When LLM fallback is triggered:
- LLM analyzes the cluster with low confidence
- Merges LLM result with heuristic detection
- Increases confidence score
- Marks result as "heuristic+llm" source

### User Control

Users can control LLM usage via parameters:
- `confidenceThreshold`: Minimum confidence to accept heuristic result (default 0.7)
- `useLlmForLowConfidence`: Enable LLM fallback for low-confidence clusters
- `llmClient`: LLM client instance for fallback analysis

## Deployment Pattern Detection

The module detects deployment patterns (Monolith, Microservices, Modular Monolith, Aggregator) based on cluster analysis:

### Detection Logic
- **File**: `signature/SignatureBuilder.kt`
- **Method**: `detectDeploymentPattern()`

The deployment pattern is determined as follows:
1. Filter out empty clusters (fileCount = 0)
2. If no valid clusters: `UNKNOWN`
3. If single cluster: `MONOLITH`
4. If multiple clusters with independent builds and >= 5 clusters: `MICROSERVICES`
5. Otherwise: `MODULAR_MONOLITH` (default for Gradle multi-module projects)

### Fix Applied
Previously, the logic defaulted to `AGGREGATOR` for multi-module Gradle projects, which was incorrect. The fix ensures that:
- Gradle multi-module projects with 2-4 clusters are correctly identified as `MODULAR_MONOLITH`
- Only projects with >= 5 independent build modules are identified as `MICROSERVICES`
- This matches the typical Gradle multi-module project structure

## Sequence Diagram

See [Cluster and Architecture Detection Flow](./docs/cluster-architecture-detection.sd) for the detailed sequence diagram showing how cluster detection and architecture detection work together.

## License

MIT License - This module is designed to be reusable across different projects and tools.

## Future Enhancements

- Additional detectors (DeploymentPatternDetector, DesignPatternDetector)
- Enhanced pattern catalog with more architectural patterns
- Machine learning-based pattern detection
- Architecture recommendation engine
- LLM prompt generation for low-confidence cluster analysis
- Import density analysis for cluster detection
