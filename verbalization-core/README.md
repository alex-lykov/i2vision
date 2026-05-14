# verbalization-core

**Multi-Layer Verbalization Engine with Context-Aware Strategy Selection**

verbalization-core transforms code symbols into meaningful architectural descriptions using three intelligent strategies, automatically selected based on the **Cluster Context Neediness Score (CNS)**.

---

## Overview

verbalization-core is the heart of i2vision's architectural intelligence. It goes beyond simple code-to-text conversion to provide genuine understanding of code structure, behavior, and architectural role.

### Key Capabilities

- **Three Verbalization Strategies**: INCREMENTAL, MULTI_PASS, LEARNING
- **Multi-Layer Support**: Vision, Structure, Logic, Flow, and Code layers
- **CNS-Driven Strategy Selection**: Automatic strategy choice based on cluster neediness
- **Learning System**: LLM integration with user feedback loop
- **Kotlin-Aware**: Specialized handling for suspend, data class, sealed class, etc.
- **Incremental Processing**: Hash-based change detection for optimal performance

---

## Architecture

### Core Components

| Component | Purpose | Status |
|-----------|---------|--------|
| `VerbalizationEngine` | Main interface for verbalization operations | ? Complete |
| `DefaultVerbalizationEngine` | Strategy orchestration and layer coordination | ? Complete |
| `PatternMatcher` | AST-aware pattern matching with Kotlin templates | ? Complete |
| `CodeAnalyzer` | Kotlin AST visitor extracting semantic information | ? Complete |
| `ContextNeedinessCalculator` | CNS calculation with 4 components | ? Complete |
| `FeedbackStore` | JSONL feedback persistence | ? Complete |
| `LlmVerbalizationClient` | LLM integration with prompt templates | ? Complete |
| `LayerVerbalizers` | 5 layer-specific verbalizers | ? Complete |

### Strategy Hierarchy

```
VerbalizationStrategyImpl
|-- IncrementalVerbalizationStrategy (hash-based, <50ms)
|-- MultiPassVerbalizationStrategy (context refinement, <200ms)
`-- LearningVerbalizationStrategy (LLM + feedback, <5000ms)
```

### Layer Hierarchy

```
LayerVerbalizer
|-- VisionVerbalizer (requirements, constraints)
|-- StructureVerbalizer (components, dependencies)
|-- LogicVerbalizer (invariants, business rules)
|-- FlowVerbalizer (sequences, interactions)
`-- CodeVerbalizer (symbol descriptions)
```

### Layer-Specific Input Sources

Each layer verbalizer processes different types of input sources:

#### Vision Layer (`VisionVerbalizer`)
- **Primary Sources**: `README.md`, any `.md` files, `/docs/` directory content
- **Configuration**: Objects with "Config" in name (SymbolKind.OBJECT)
- **Structured Metadata**: Symbol metadata fields (`purpose`, `requirements`, `constraints`)
- **Extraction**: Purpose statements, requirements, constraints, technical decisions
- **Limitation**: Currently extracts from project-wide docs only (same vision for all clusters)

#### Structure Layer (`StructureVerbalizer`)
- **Primary Sources**: Class definitions, interface declarations, dependency graphs
- **Extraction**: Component relationships, architectural patterns, dependency analysis

#### Logic Layer (`LogicVerbalizer`)
- **Primary Sources**: Validation logic, business rules, state machines
- **Extraction**: Invariants, business rules, validation constraints

#### Flow Layer (`FlowVerbalizer`)
- **Primary Sources**: Function sequences, API endpoints, interaction patterns
- **Extraction**: Execution flows, API sequences, interaction patterns

#### Code Layer (`CodeVerbalizer`)
- **Primary Sources**: All code symbols with semantic analysis
- **Extraction**: Symbol descriptions, API documentation, inline comments

---

## Verbalization Strategies

### Strategy Selection (CNS-Based)

The **ContextNeedinessCalculator** automatically selects the optimal strategy:

| CNS Range | Strategy | Use Case | Latency (100 symbols) |
|-----------|----------|----------|----------------------|
| 0-30 | INCREMENTAL | Low need; hash-based | < 50ms |
| 31-60 | MULTI_PASS | Moderate need; context refinement | < 200ms |
| 61-100 | LEARNING | High need; LLM + feedback | < 5000ms |

### CNS Formula

```
CNS = SymbolAmbiguity(35) + StructuralComplexity(25) +
      ArchitecturalSensitivity(20) + FeedbackDiscrepancy(20)
```

| Component | Weight | Measures |
|-----------|--------|----------|
| Symbol Ambiguity | 35% | Low confidence, duplicate names, missing docs |
| Structural Complexity | 25% | Cyclomatic complexity, external dependencies |
| Architectural Sensitivity | 20% | Domain module, cross-cutting flows |
| Feedback Discrepancy | 20% | Gap between heuristic and user feedback |

---

## Usage

### Basic Verbalization

```kotlin
import com.i2vision.verbalization.DefaultVerbalizationEngine
import com.i2vision.verbalization.strategy.IncrementalVerbalizationStrategy
import com.i2vision.intent.DiscoveryIntent

val engine = DefaultVerbalizationEngine(
    patternMatcher = PatternMatcher(),
    hashManager = HashManager(),
    llmClient = DefaultLlmVerbalizationClient(),
    feedbackStore = FeedbackStore(cacheDir)
)

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
    intent = DiscoveryIntent(goal = IntentGoal.FULL_DISCOVERY)
)

results.forEach { result ->
    println("${result.symbol.name}: ${result.description}")
    // Output: authenticate: Suspend function that validates credentials via bcrypt
}
```

### Multi-Layer Verbalization

```kotlin
import com.i2vision.verbalization.layer.LayerVerbalizerFactory
import com.i2vision.vslfc.VSLFCLayer

// Get verbalizers for all layers
val factory = LayerVerbalizerFactory()
val visionVerbalizer = factory.getVerbalizer(VSLFCLayer.VISION)
val codeVerbalizer = factory.getVerbalizer(VSLFCLayer.CODE)

// Verbalize each layer
val visionResult = visionVerbalizer.verbalize(symbol, context, intent)
val codeResult = codeVerbalizer.verbalize(symbol, context, intent)
```

### Custom Patterns

```kotlin
import com.i2vision.vslfc.VerbalizationPattern

// Register custom pattern
val customPattern = VerbalizationPattern(
    codePattern = "class (\\w+)Agent",
    description = "AI Agent for $1 operations",
    confidence = 0.9
)
patternMatcher.registerPattern(customPattern)
```

### Feedback Collection

```kotlin
import com.i2vision.verbalization.feedback.FeedbackStore

val feedbackStore = FeedbackStore(cacheDir)

// Record user feedback
feedbackStore.recordFeedback(
    symbol = symbol,
    originalDescription = "Service class for auth operations",
    correction = "Validates credentials via bcrypt, issues JWT with role claims",
    rating = 5,
    reason = "original too vague"
)

// Feedback is automatically used by LEARNING strategy
```

---

## Storage Structure

### Multi-Layer Cache

```
.semantic-cache/{cluster}/
|-- vision/vision.yaml           # Vision layer verbalizations
|-- structure/architecture.yaml  # Structure layer verbalizations
|-- logic/rules.yaml             # Logic layer verbalizations
|-- flow/flows.yaml              # Flow layer verbalizations
|-- code/verbalizations.yaml     # Code layer verbalizations
|-- learning/feedback.jsonl      # User feedback (JSONL)
`-- .meta/
    `-- hashes.yaml              # Per-layer hash tracking
```

### Feedback Format (JSONL)

```jsonl
{"timestamp": 1709234567890, "symbolId": "core/auth/AuthService#authenticate",
 "originalDescription": "Service class for auth operations",
 "correction": "Validates credentials via bcrypt, issues JWT with role claims, enforces rate limiting",
 "userId": "dev@company.com", "rating": 5, "reason": "original too vague"}
```

---

## Configuration

### Feature Flags (`.i2vision/config/verbalization.yaml`)

```yaml
verbalization:
  enabled: true
  defaultStrategy: INCREMENTAL
  multiLayerEnabled: true
  cnsEnabled: true
  learningEnabled: true
  llm:
    provider: openai  # or anthropic, vertexai
    model: gpt-4
    apiKeyEnv: OPENAI_API_KEY
```

### LLM Configuration (`.i2vision/llm.config`)

```properties
provider=openai
model=gpt-4
apiKey=sk-...
temperature=0.3
maxTokens=500
timeoutMs=30000
```

---

## Kotlin-Specific Enhancements

The system automatically detects and enhances Kotlin terminology:

| Pattern | Enhancement |
|---------|-------------|
| `suspend` function | Adds "asynchronous operation", "non-blocking" |
| `data class` | Adds "immutable value container" |
| `sealed class` | Adds "restricted hierarchy for state modeling" |
| `inline class` | Adds "zero-overhead type wrapper" |
| `companion object` | Adds "static factory/utility holder" |
| `by` delegation | Adds "delegate implementation" |

---

## Performance Benchmarks

### Target Latency (per 100 symbols)

| Strategy | Target | Achieved |
|----------|--------|----------|
| INCREMENTAL | < 50ms | ? < 50ms |
| MULTI_PASS | < 200ms | ? < 200ms |
| LEARNING | < 5000ms | ? < 5000ms |

### Cache Performance

| Metric | Target | Achieved |
|--------|--------|----------|
| Cache hit rate | > 80% | ? > 80% |
| Cache size | < 1MB per 1000 symbols | ? Achieved |
| Symbol coverage | > 95% | ? > 95% |

### Quality Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| Description quality | > 4.0/5.0 | User feedback ratings |
| Learning improvement | > 20% | Feedback corrections |
| CNS accuracy | > 0.7 correlation | CNS vs. actual LLM queries |

---

## Testing

### Test Coverage

| Test Suite | Purpose | Coverage | Status |
|------------|---------|----------|--------|
| `CodeAnalyzerTest` | AST parsing accuracy | > 90% branch | ? Complete |
| `PatternMatcherTest` | Pattern matching with Kotlin | All patterns | ? Complete |
| `ConfidenceEstimatorTest` | Score calculation | 100% formula | ? Complete |
| `LayerVerbalizersTest` | All 5 layers | All layers | ? Complete |
| `CNSCalculatorTest` | All 4 CNS components | All components | ? Complete |
| `CrossLayerEnrichmentTest` | Cross-layer context extraction | All layers | ? Complete |
| `CrossLayerTypesTest` | Extended context types | All types | ? Complete |
| `LearningVerbalizationStrategyTest` | LLM and feedback integration | All scenarios | ? Complete |

---

## Data Flow Overview

This section describes how data flows through the i2-Vision system from code discovery to LLM consumption.

### End-to-End Data Flow

```
+==================================================================================+
|                           DISCOVERY PHASE                                        |
|  +-----------------+     +-----------------+     +-----------------------------+  |
|  |  IndexProvider  | --> |  SymbolScanner  | --> |  SymbolEnrichment           |  |
|  |  (Code Index)   |     |  (AST Parser)   |      |  (KotlinModifierExtractor)  |  |
|  +-----------------+     +-----------------+     +-----------------------------+  |
|         |                       |                        |                        |
|         v                       v                        v                        |
|   Code symbols             Parsed symbols           EnrichedSymbol                |
|   (location, kind)         (type, modifiers)        (modifiers, roles, deps)     |
+==================================================================================+
                                    |
                                    v
+==================================================================================+
|                        VERBALIZATION PHASE                                       |
|  +-----------------+     +-----------------+     +-----------------------------+  |
|  | Verbalization   | --> |  CNS Calculator | --> |  Strategy Selector          |  |
|  |   Engine        |     |  (CNS scoring)  |      |  (INCREMENTAL/MULTI_PASS/   |  |
|  +-----------------+     +-----------------+      |   LEARNING)                |  |
|         |                       |                +-----------------------------+  |
|         |                       |                         |                        |
|         v                       v                         v                        |
|   Symbols, intent          CNS score (0-100)        Selected strategy             |
|   and clusters             determines need          based on neediness            |
+==================================================================================+
                                    |
                                    v
+==================================================================================+
|                           STRATEGY EXECUTION                                     |
|                                                                                   |
|  +============================================================================+   |
|  | INCREMENTAL Strategy (CNS 0-30): Hash-based verbalization                  |   |
|  |  +--------------+     +--------------+     +--------------------------+    |   |
|  |  | Check Cache  | --> | Hash Symbols | --> | Retrieve Cached Verbatims|    |   |
|  |  +--------------+     +--------------+     +--------------------------+    |   |
|  |        |                    |                      |                        |   |
|  |        v                    v                      v                        |   |
|  |   Hit?                 Compute hash            Return cached                 |   |
|  |   |                                                                  |        |   |
|  |   +---- No ---------> Generate from patterns (PatternMatcher)                |   |
|  +============================================================================+   |
|                                                                                   |
|  +============================================================================+   |
|  | MULTI_PASS Strategy (CNS 31-60): Cross-layer refinement                     |   |
|  |  +--------------+     +--------------+     +--------------------------+    |   |
|  |  | Initial      | --> | Enrich with  | --> | Refine with              |    |   |
|  |  | Description  |     | StructureCtx |     | Flow/LogicCtx            |    |   |
|  |  +--------------+     +--------------+     +--------------------------+    |   |
|  |        |                    |                      |                        |   |
|  |        v                    v                      v                        |   |
|  |   Base verbalization   Dependencies, roles    Calling sequences,           |   |
|  |                                               business rules                 |   |
|  +============================================================================+   |
|                                                                                   |
|  +============================================================================+   |
|  | LEARNING Strategy (CNS 61-100): LLM with feedback                           |   |
|  |  +--------------+     +--------------+     +--------------------------+    |   |
|  |  | Check        | --> | Query LLM    | --> | Apply User Feedback      |    |   |
|  |  | Feedback     |     | with Prompt  |      | (high-rated corrections)|    |   |
|  |  +--------------+     +--------------+     +--------------------------+    |   |
|  |        |                    |                      |                        |   |
|  |        v                    v                      v                        |   |
|  |   Rated feedback      LLM generates           Corrections applied           |   |
|  |   (rating >= 4)       descriptions            from JSONL store             |   |
|  +============================================================================+   |
+==================================================================================+
                                    |
                                    v
+==================================================================================+
|                             STORAGE LAYER                                        |
|  +============================================================================+   |
|  |  InMemoryStorage / FileStorage                                           |    |
|  |  +-------------+   +-------------+   +-------------+   +-----------------+ |    |
|  |  | vision/     |   | structure/  |   | logic/      |   | learning/       | |    |
|  |  | *.yaml      |   | *.yaml      |   | *.yaml      |   | feedback.jsonl  | |    |
|  |  +-------------+   +-------------+   +-------------+   +-----------------+ |    |
|  +============================================================================+   |
+==================================================================================+
                                    |
                                    v
+==================================================================================+
|                           MCP SERVER / LLM CONSUMPTION                           |
|  +-----------------+     +-----------------+     +-----------------------------+  |
|  |  MCP Server     | --> |  Get Verbalized | --> |  Context for               |  |
|  |  (Model Context |     |  Descriptions   |      |  LLM Prompts               |  |
|  |   Protocol)     |     |  from Storage   |      |                            |  |
|  +-----------------+     +-----------------+     +-----------------------------+  |
|         |                       |                        |                        |
|         v                       v                        v                        |
|   Tool definitions        Semantic cache              Enhanced LLM responses       |
|   for code Q&A           lookups                     with architectural context    |
+==================================================================================+
```

### Integration Points

#### 1. IndexProvider -> Symbol Scanning

| Aspect | Description |
|--------|-------------|
| **Input** | Raw code files (`.kt`, `.java`, `.ts`) |
| **Output** | `Symbol` objects with `name`, `kind`, `filePath`, `lineNumber` |
| **Integration** | `IndexProvider.getSymbols(clusterId)` returns list of symbols for a cluster |
| **Cache Key** | `{clusterId}:{filePath}:{lineNumber}` |

**Example:**
```kotlin
val symbols: List<Symbol> = indexProvider.getSymbols("core/auth")
// [Symbol("authenticate", FUNCTION, "auth/AuthService.kt", 25), ...]
```

#### 2. Symbol Enrichment -> EnrichedSymbol

| Aspect | Description |
|--------|-------------|
| **Input** | `Symbol` from IndexProvider |
| **Output** | `EnrichedSymbol` with `modifiers`, `structuralRole`, `dependencies`, `flows`, `businessRules` |
| **Integration** | `KotlinModifierExtractor.extractModifiers(node)` performs AST analysis |
| **Cache Key** | `{symbolId}:enriched` |

**Example:**
```kotlin
val enriched = KotlinModifierExtractor().enrich(symbol, ktNode)
println(enriched.modifiers)
// [SymbolModifier(REPOSITORY, ANNOTATION), SymbolModifier(ASYNC, AST)]
println(enriched.structuralRole)
// StructuralRole.SERVICE
```

#### 3. VerbalizationEngine -> Storage

| Aspect | Description |
|--------|-------------|
| **Input** | `EnrichedSymbol`, `VerbalizationIntent` |
| **Output** | `VerbalizationResult` with `description`, `confidence`, `metadata` |
| **Integration** | `VerbalizationEngine.verbalize(clusterId, symbols, intent)` |
| **Storage** | `Storage.storeVerbalization(clusterId, layer, result)` |

**Example:**
```kotlin
val result = engine.verbalize(
    clusterId = "core/auth",
    symbols = enrichedSymbols,
    intent = DiscoveryIntent(goal = IntentGoal.FULL_DISCOVERY)
)
println(result.description)
// "Suspend function that validates credentials via bcrypt and issues JWT tokens"
```

#### 4. Storage -> MCP Server

| Aspect | Description |
|--------|-------------|
| **Input** | MCP tool request for cluster context |
| **Output** | `SymbolVerbalization` objects for LLM consumption |
| **Integration** | `McpServer.getClusterContext(clusterId)` queries storage |
| **Cache** | In-memory cache with TTL for hot clusters |

**Example:**
```kotlin
// MCP Tool: get_cluster_context
val context = mcpServer.getClusterContext("core/auth")
// Returns: { "symbols": [...], "architecture": "...", "flows": [...] }
```

### Sequence Diagram (Detailed)

```
Developer      McpServer      Storage       Engine       Index         LLM
   |              |              |             |             |            |
   | "How does auth work?"      |             |             |            |
   |------------->|              |             |             |            |
   |              | getClusterContext()        |             |            |
   |              |------------->|             |             |            |
   |              |  Cache hit?  |             |             |            |
   |              |<- - - - - - -|             |             |            |
   |              |              |             |             |            |
   |              | verbalize("core/auth", intent)          |            |
   |              |---------------------------->|             |            |
   |              |              |             |             |            |
   |              |              |             | getSymbols("core/auth")   |
   |              |              |             |------------>|            |
   |              |              |             | [Symbol("AuthService"),   |
   |              |              |             |  Symbol("authenticate")]  |
   |              |              |             |<------------|            |
   |              |              |             |             |            |
   |              |              |             | For each symbol:          |
   |              |              |             |  Calculate CNS score      |
   |              |              |             |  Select strategy          |
   |              |              |             |             |            |
   |              |              |             | Strategy: INCREMENTAL    |
   |              |              |             |             |            |
   |              |              |             | checkCache(symbol)        |
   |              |              |             |------------>|            |
   |              |              |             |  Cache hit? |            |
   |              |              |             |<------------|            |
   |              |              |             |             |            |
   |              |              |             | storeVerbalization()      |
   |              |              |             |------------>|            |
   |              |              |             |             |            |
   |              |              |             | Verbalization results     |
   |              |              |<----------------------------|            |
   |              |              |             |             |            |
   |              | Cached verbalizations     |             |            |
   |<-------------|              |             |             |            |
   |              |              |             |             |            |
   "AuthService authenticates users via bcrypt..."
```

### Layer Integration Details

| Layer | Input Source | Output | Integration Point |
|-------|--------------|--------|-------------------|
| **Vision** | `README.md`, `/docs/` | Architectural intent | `VisionVerbalizer.extractPurpose(symbol)` |
| **Structure** | Class graph, dependencies | Component relationships | `StructureVerbalizer.analyzeDependencies(symbol)` |
| **Logic** | Validation rules, invariants | Business rules | `LogicVerbalizer.extractInvariants(symbol)` |
| **Flow** | Call sequences, APIs | Execution flows | `FlowVerbalizer.traceFlows(symbol)` |
| **Code** | Symbol definition, modifiers | Natural language | `CodeVerbalizer.verbalize(symbol)` |

### Performance Considerations

| Phase | Latency | Optimization |
|-------|---------|--------------|
| Discovery (Index) | ~100ms/1000 files | Parallel AST parsing |
| Enrichment | ~50ms/100 symbols | Batch extraction |
| CNS Calculation | ~10ms | Pre-computed metrics |
| INCREMENTAL | <50ms | Hash-based cache lookup |
| MULTI_PASS | <200ms | Context-aware refinement |
| LEARNING | <5000ms | Async LLM queries |
| Storage | <5ms | Write-behind caching |
| MCP Lookup | <1ms | In-memory cache |

### Error Handling

```
+-----------------+     +-----------------+     +-----------------------------+
| Error in Index  | --> | Return cached   | --> | Log warning, continue      |
| (file not found)|     | if available    |      | with remaining symbols     |
+-----------------+     +-----------------+     +-----------------------------+

+-----------------+     +-----------------+     +-----------------------------+
| Error in LLM    | --> | Fallback to     | --> | Log error, use heuristic   |
| (timeout/error) |     | INCREMENTAL     |      | verbalization              |
+-----------------+     +-----------------+     +-----------------------------+

+-----------------+     +-----------------+     +-----------------------------+
| Cache corruption| --> | Rebuild cache   | --> | Log warning, re-verbaliize  |
| (invalid hash)  |     | from scratch    |      | all symbols                |
+-----------------+     +-----------------+     +-----------------------------+
```

---

## Related Documentation

- [Architecture Decision Records](../adr/README.md)
- [CNS Calibration Plan](../reference/cns-calibration-plan.md)
- [Migration Guide: Regex to Structured Detection](../migrations/structured-modifier-detection.md)