# verbalization-core

**Multi-Layer Verbalization Engine with Context-Aware Strategy Selection**

`verbalization-core` transforms code symbols into meaningful architectural descriptions using three intelligent strategies, automatically selected based on the **Cluster Context Neediness Score (CNS)**.

---

## Overview

`verbalization-core` is the heart of i2vision's architectural intelligence. It goes beyond simple code-to-text conversion to provide genuine understanding of code structure, behavior, and architectural role. The system analyzes symbols across all five VSLFC layers and produces human-readable descriptions that capture not just what code *is*, but what it *does* and *why it exists* within the broader architecture.

**Self-test verified quality:** 99.9% of 7,733 symbols produce valid descriptions with 0.01% anti-pattern rate.

---

## Key Capabilities

| Capability | Description |
| :--- | :--- |
| **Three Verbalization Strategies** | INCREMENTAL (hash-based, <50ms), MULTI_PASS (context refinement, <200ms), LEARNING (LLM + feedback, <5000ms) |
| **Multi-Layer Support** | Vision, Structure, Logic, Flow, and Code layers with dedicated verbalizers |
| **CNS-Driven Strategy Selection** | Automatic strategy choice based on cluster neediness (0-100 score) |
| **Learning System** | LLM integration with user feedback loop for continuous improvement |
| **Kotlin-Aware** | Specialized patterns for `suspend`, `data class`, `sealed class`, `inline class`, `object`, `companion object`, delegation |
| **Incremental Processing** | Two-tier hash-based change detection (local + context) for optimal performance |
| **Intelligent Fallback** | CamelCase-to-words conversion with 20+ action verb mappings when no pattern matches |
| **Spring Framework Support** | Patterns for `@RestController`, `@Service`, `@Repository`, `@Component` annotations |

---

## Architecture

### Core Components

| Component | Purpose | Status |
| :--- | :--- | :--- |
| `VerbalizationEngine` | Main interface for verbalization operations | ✅ Complete |
| `DefaultVerbalizationEngine` | Strategy orchestration, hash management, result storage | ✅ Complete |
| `PatternMatcher` | Pattern matching engine with Kotlin templates and intelligent fallback | ✅ Complete |
| `HashManager` | Two-tier hash tracking (local + context) for incremental processing | ✅ Complete |
| `ContextNeedinessCalculator` | CNS calculation with 4 weighted components | ✅ Complete |
| `FeedbackStore` | JSONL feedback persistence and retrieval | ✅ Complete |
| `LlmVerbalizationClient` | LLM integration with prompt templates and fallback chain | ✅ Complete |
| `CrossLayerContextProvider` | Provides architectural context from all layers for MULTI_PASS enrichment | ✅ Complete |

### Strategy Hierarchy

```
VerbalizationStrategy (interface)
├── IncrementalVerbalizationStrategy   (hash-based, <50ms per 100 symbols)
├── MultiPassVerbalizationStrategy     (cross-layer context, <200ms)
└── LearningVerbalizationStrategy      (LLM + feedback, <5000ms)
```

### Strategy Fallback Chain

```
LEARNING ──(timeout/error)──→ MULTI_PASS ──(unavailable)──→ INCREMENTAL
```

### Layer Hierarchy

```
LayerVerbalizer (interface)
├── VisionVerbalizer     → requirements, constraints, purpose
├── StructureVerbalizer  → components, dependencies, architectural patterns
├── LogicVerbalizer      → invariants, business rules, validation constraints
├── FlowVerbalizer       → execution sequences, API endpoints, interactions
└── CodeVerbalizer       → symbol descriptions, API documentation, inline comments
```

---

## Layer-Specific Input Sources

### Vision Layer (`VisionVerbalizer`)

| Source | Extraction |
| :--- | :--- |
| `README.md` | Purpose statements, requirements, constraints |
| `/docs/` directory | Technical decisions, architectural intent |
| Configuration objects (`*Config`) | Structured metadata |
| `.vision-ai/.vision/requirements` | Human-authored requirements |

**Current limitation:** Extracts from project-wide documentation only; per-cluster vision extraction is planned.

### Structure Layer (`StructureVerbalizer`)

| Source | Extraction |
| :--- | :--- |
| Class definitions, interface declarations | Component relationships |
| Dependency graphs | Architectural patterns, coupling analysis |
| Package structure | Module organization |

### Logic Layer (`LogicVerbalizer`)

| Source | Extraction |
| :--- | :--- |
| Validation logic, state machines | Business invariants |
| Conditional branches, when expressions | Decision rules |
| Error handling patterns | Validation constraints |

### Flow Layer (`FlowVerbalizer`)

| Source | Extraction |
| :--- | :--- |
| Function call sequences | Execution flows |
| API endpoint definitions | API sequences |
| Event chains, coroutine flows | Interaction patterns |

### Code Layer (`CodeVerbalizer`)

| Source | Extraction |
| :--- | :--- |
| All code symbols with semantic analysis | Natural language descriptions |
| Kotlin modifiers and annotations | Symbol roles and behaviors |
| Doc comments and inline comments | API documentation |

---

## Strategy Selection (CNS-Based)

The `ContextNeedinessCalculator` automatically selects the optimal strategy based on cluster characteristics:

| CNS Range | Strategy | Use Case | Latency (100 symbols) |
| :--- | :--- | :--- | :--- |
| 0–30 | INCREMENTAL | Low need; stable, well-documented code | < 50ms |
| 31–60 | MULTI_PASS | Moderate need; cross-cutting concerns | < 200ms |
| 61–100 | LEARNING | High need; complex domain logic | < 5000ms |

### CNS Formula

```
CNS = SymbolAmbiguity(35) + StructuralComplexity(25) +
      ArchitecturalSensitivity(20) + FeedbackDiscrepancy(20)
```

| Component | Weight | Measures |
| :--- | :--- | :--- |
| **Symbol Ambiguity** | 35% | Low confidence scores, duplicate names, missing documentation |
| **Structural Complexity** | 25% | Cyclomatic complexity, external dependencies, fan-out |
| **Architectural Sensitivity** | 20% | Domain module detection, cross-cutting flows, core vs. utility |
| **Feedback Discrepancy** | 20% | Semantic distance between heuristic and user-corrected descriptions |

---

## Intelligent Fallback Descriptions

When no pattern matches a symbol, the system generates descriptions using intelligent camelCase-to-words conversion with context-appropriate phrasing:

| Symbol Kind | Pattern | Example Input | Example Output |
| :--- | :--- | :--- | :--- |
| FUNCTION | Action verb mapping | `extractModulePath` | `Extracts module path` |
| FUNCTION | Boolean check | `isValidUser` | `Checks valid user` |
| FUNCTION | Special cases | `main` | `Main entry point` |
| FUNCTION | Special cases | `println` | `Prints line to output` |
| CLASS | Descriptive | `UserRepository` | `Class representing user repository` |
| PROPERTY | Descriptive | `symbolRepository` | `Property holding symbol repository` |
| UNKNOWN | Inferred from naming | `SymbolKind` | `Type 'SymbolKind'` |

**20+ action verb mappings:** `get`→Gets, `is`/`has`→Checks, `create`/`build`→Creates, `delete`/`remove`→Removes, `update`/`modify`→Updates, `find`/`search`→Finds, `parse`/`extract`→Parses, `validate`/`verify`→Validates, `load`/`fetch`→Loads, `save`/`store`→Saves, `send`/`publish`→Sends, `process`/`execute`/`run`→Executes, `show`/`display`→Displays, `clean`/`sanitize`/`normalize`→Cleans, `fallback`→Falls back to, `bridge`→Bridges, `map`→Maps.

---

## Usage

### Basic Verbalization

```kotlin
import com.i2vision.verbalization.DefaultVerbalizationEngine
import com.i2vision.verbalization.PatternMatcher
import com.i2vision.verbalization.HashManager
import com.i2vision.verbalization.feedback.FeedbackStore
import com.i2vision.verbalization.llm.DefaultLlmVerbalizationClient
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

### Force Full Verbalization (Bypass Cache)

```kotlin
val intent = DiscoveryIntent(
    goal = IntentGoal.FULL_DISCOVERY,
    forceFullVerbalization = true  // Skip hash cache, re-verbalize all symbols
)
val results = engine.verbalize(clusterId, symbols, intent)
```

### Multi-Layer Verbalization

```kotlin
import com.i2vision.verbalization.layer.LayerVerbalizerFactory
import com.i2vision.vslfc.VSLFCLayer

val factory = LayerVerbalizerFactory()
val visionVerbalizer = factory.getVerbalizer(VSLFCLayer.VISION)
val codeVerbalizer = factory.getVerbalizer(VSLFCLayer.CODE)

val visionResult = visionVerbalizer.verbalize(symbol, context, intent)
val codeResult = codeVerbalizer.verbalize(symbol, context, intent)
```

### Custom Patterns

```kotlin
import com.i2vision.vslfc.VerbalizationPattern

val customPattern = VerbalizationPattern(
    codePattern = "class (\\w+)Agent",
    description = "AI Agent for $1 operations",
    confidence = 0.9
)
patternMatcher.registerPattern(customPattern)

// Load patterns from config file
patternMatcher.loadCustomPatterns(File(".vision-ai/config/verbalization-patterns.yaml"))
```

### Feedback Collection

```kotlin
val feedbackStore = FeedbackStore(cacheDir)

feedbackStore.recordFeedback(
    symbol = symbol,
    originalDescription = "Service class for auth operations",
    correction = "Validates credentials via bcrypt, issues JWT with role claims, enforces rate limiting",
    rating = 5,
    reason = "original too vague"
)
// Feedback is automatically used by the LEARNING strategy
```

---

## Storage Structure

### Multi-Layer Cache

```
.semantic-cache/{cluster}/
├── vision/
│   └── vision.yaml              # Vision layer verbalizations
├── structure/
│   └── architecture.yaml        # Structure layer verbalizations
├── logic/
│   └── rules.yaml               # Logic layer verbalizations
├── flow/
│   └── flows.yaml               # Flow layer verbalizations
├── code/
│   └── verbalizations.yaml      # Code layer verbalizations
├── learning/
│   └── feedback.jsonl           # User feedback (JSONL format)
└── .meta/
    └── hashes.yaml              # Two-tier hash tracking (local + context)
```

### Feedback Format (JSONL)

```jsonl
{"timestamp": 1709234567890, "symbolId": "core/auth/AuthService#authenticate", "originalDescription": "Service class for auth operations", "correction": "Validates credentials via bcrypt, issues JWT with role claims, enforces rate limiting", "userId": "dev@company.com", "rating": 5, "reason": "original too vague"}
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
  forceFullVerbalization: false
  llm:
    provider: openai
    model: gpt-4
    apiKeyEnv: OPENAI_API_KEY
```

### CNS Configuration (`.i2vision/config/cns.yaml`)

```yaml
cns:
  weights:
    symbolAmbiguity: 35
    structuralComplexity: 25
    architecturalSensitivity: 20
    feedbackDiscrepancy: 20
  thresholds:
    incremental: 30
    multiPass: 60
  patternMatching:
    lowConfidenceThreshold: 0.7
    duplicateNamePenalty: 5
    missingDocPenalty: 3
```

### LLM Configuration (`.i2vision/llm.config`)

```properties
provider=openai
model=gpt-4
apiKey=sk-...
temperature=0.3
maxTokens=500
timeoutMs=30000
batchSize=100
```

---

## Kotlin-Specific Enhancements

| Pattern | Enhancement |
| :--- | :--- |
| `suspend fun` | Adds "asynchronous operation", "non-blocking" |
| `data class` | "Immutable data container" |
| `sealed class` | "Restricted hierarchy for state modeling" |
| `inline class` | "Zero-overhead type wrapper" |
| `companion object` | "Static factory/utility holder" |
| `by` delegation | "Delegate implementation" |
| `@RestController` | "REST API endpoint" |
| `@Service` | "Business logic service" |
| `@Repository` | "Data access layer" |
| `@Component` | "Spring-managed component" |

---

## Performance Benchmarks

### Target Latency (per 100 symbols)

| Strategy | Target | Status |
| :--- | :--- | :--- |
| INCREMENTAL | < 50ms | ✅ Achieved |
| MULTI_PASS | < 200ms | ✅ Achieved |
| LEARNING | < 5000ms | ✅ Achieved (when LLM available) |

### Cache Performance

| Metric | Target | Status |
| :--- | :--- | :--- |
| Cache hit rate | > 80% | ✅ Achieved |
| Symbol coverage | > 95% | ✅ Achieved |
| Description quality rate | > 99% | ✅ 99.9% (7,733 symbols verified) |
| Anti-pattern rate | < 1% | ✅ 0.01% |

---

## Quality Metrics

| Metric | Target | Measurement |
| :--- | :--- | :--- |
| Description quality | 4.0/5.0 | User feedback ratings |
| Learning improvement | 20%+ | Feedback correction application rate |
| CNS accuracy | 0.7 correlation | CNS vs. actual LLM query need |
| Multi-pass enrichment rate | > 70% | Symbols with enriched descriptions |
| Anti-pattern rate | < 1% | Tautological, too-short, or verb-missing descriptions |

---

## Test Coverage

| Test Suite | Purpose | Status |
| :--- | :--- | :--- |
| `CodeAnalyzerTest` | AST parsing accuracy | ✅ Complete |
| `PatternMatcherTest` | Pattern matching with Kotlin templates | ✅ Complete |
| `LayerVerbalizersTest` | All 5 layer verbalizers | ✅ Complete |
| `CNSCalculatorTest` | All 4 CNS components | ✅ Complete |
| `CrossLayerEnrichmentTest` | Cross-layer context extraction | ✅ Complete |
| `LearningVerbalizationStrategyTest` | LLM and feedback integration | ✅ Complete |
| `FeedbackStoreTest` | JSONL persistence (8 tests) | ✅ Complete |
| `DefaultLlmVerbalizationClientTest` | Kotlin terminology handling | ✅ Complete |
| `VerbalizationSelfTest` | End-to-end quality validation (8 phases) | ✅ Complete |

---

## Data Flow

### End-to-End Pipeline

```
                         DISCOVERY PHASE
  IndexProvider ──→ SymbolScanner ──→ SymbolEnrichment
       │                  │                  │
       v                  v                  v
  Code symbols      Parsed symbols     EnrichedSymbol
  (location, kind)  (type, modifiers)  (modifiers, roles, deps)
                                           │
                                           v
                       VERBALIZATION PHASE
  VerbalizationEngine ──→ CNSCalculator ──→ StrategySelector
       │                       │                  │
       v                       v                  v
  Symbols, intent         CNS (0-100)      INCREMENTAL/MULTI_PASS/LEARNING
                                                    │
                                                    v
                        STRATEGY EXECUTION
  ┌─ INCREMENTAL: Check cache → Hash → Retrieve or Generate
  ├─ MULTI_PASS:  Initial desc → Enrich with Structure → Refine with Flow/Logic
  └─ LEARNING:    Check feedback → Query LLM → Apply corrections
                                           │
                                           v
                            STORAGE LAYER
  .semantic-cache/{cluster}/
  ├── vision/     ├── structure/    ├── logic/
  ├── flow/       ├── code/         └── learning/
                                           │
                                           v
                     MCP SERVER / LLM CONSUMPTION
  MCP Server ──→ Get Verbalized Descriptions ──→ Enhanced LLM Prompts
```

### Strategy Execution Detail

```
INCREMENTAL (CNS 0-30):
  Check Cache → [Hit: Return cached] [Miss: Generate from patterns]
  Uses two-tier hashing: local hash (symbol content) + context hash (dependencies)

MULTI_PASS (CNS 31-60):
  Base verbalization → Enrich with StructureContext → Refine with Flow/LogicContext
  Adds: "Coordinates with N service(s): @Dependency1, @Dependency2..."

LEARNING (CNS 61-100):
  Check FeedbackStore → Build LLM prompt with feedback → Query LLM → Apply corrections
  Fallback: MULTI_PASS on timeout/error
```

---

## Integration Points

### 1. IndexProvider → Symbol Scanning

| Aspect | Detail |
| :--- | :--- |
| **Input** | Raw code files (`.kt`, `.java`, `.ts`, `.py`) |
| **Output** | `Symbol` objects with name, kind, filePath, lineNumber, content |
| **Integration** | `IndexProvider.getSymbols(clusterId)` returns list of symbols for a cluster |

### 2. Discovery Pipeline → Verbalization

| Aspect | Detail |
| :--- | :--- |
| **Input** | `List<Symbol>` + `DiscoveryIntent` |
| **Output** | `List<VerbalizationResult>` with description, confidence, strategy, metadata |
| **Integration** | `DiscoveryPipelineImpl` calls `engine.verbalize(clusterId, symbols, intent)` during Step 2.5 |

### 3. VerbalizationEngine → Storage

| Aspect | Detail |
| :--- | :--- |
| **Storage** | `VerbalizationStore` → `FileVerbalizationStore` → `.semantic-cache/{cluster}/` |
| **Persistence** | Results stored as YAML; hashes stored in `.meta/hashes.yaml` |
| **Retrieval** | `store.getVerbalizations(clusterId)` for cached access |

### 4. Storage → MCP Server

| Aspect | Detail |
| :--- | :--- |
| **Input** | MCP tool request for cluster context |
| **Output** | `SymbolVerbalization` objects for LLM prompt assembly |
| **Integration** | `McpServer.getClusterContext(clusterId)` queries storage |

---

## Error Handling

| Scenario | Behavior |
| :--- | :--- |
| **File not found** | Return cached verbalizations if available; log warning, continue |
| **LLM timeout/error** | Fallback chain: LEARNING → MULTI_PASS → INCREMENTAL |
| **Cache corruption** | Rebuild cache from scratch; log warning |
| **Empty symbols list** | Return empty results; log warning |
| **Invalid pattern** | Skip pattern; log warning; continue with remaining patterns |
| **Hash mismatch** | Re-verbalize symbol; update hash |

---

## Related Documentation

- [Self-Test Framework Documentation](../docs/verbalization-system.md#self-test-framework)
- [Architecture Decision Records](../docs/adr/)
- [CNS Calibration Plan](../docs/reference/cns-calibration-plan.md)

---

*Last updated: 2026-05-19. Quality metrics verified by self-test across 7,733 symbols in 18 clusters.*