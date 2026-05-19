# Verbalization System Architecture

## Overview

The Verbalization System converts code symbols (classes, functions, interfaces, etc.) into natural language descriptions. It is a core component of the i2-Vision discovery pipeline, enabling AI agents and human developers to understand codebases through human-readable descriptions.

## Key Components

### 1. Verbalization Engine (`DefaultVerbalizationEngine`)

Located in `verbalization-core/src/main/kotlin/com/i2vision/verbalization/`

The engine is the central orchestrator that:
- Accepts a list of `Symbol` objects from the discovery pipeline
- Applies a verbalization strategy (INCREMENTAL, MULTI_PASS, LEARNING)
- Produces `VerbalizationResult` objects containing descriptions, confidence scores, and metadata
- Integrates with caching via `VerbalizationStore` to avoid redundant processing

```kotlin
val results = engine.verbalize(
    clusterId = "user-module",
    symbols = discoveredSymbols,
    strategy = VerbalizationStrategy.INCREMENTAL,
    intent = discoveryIntent
)
```

### 2. LLM Client (`DefaultLlmVerbalizationClient`)

Located in `verbalization-core/src/main/kotlin/com/i2vision/verbalization/llm/`

Provides LLM integration with a configurable fallback to heuristic descriptions:

| Mode | Behavior |
|------|----------|
| **Mock Mode** (default) | Generates heuristic descriptions without calling external LLM. Confidence = 0.85. Used when no LLM config is present. |
| **LLM Mode** | Calls external LLM API via the `llm-client` module. Requires `.i2vision/llm.config` with `apiKey` or `provider`. |

**Mock Mode Description Generation Flow:**
1. Extract heuristic description from symbol content (`describeFromContent`)
2. Apply feedback improvements if available (`applyFeedbackImprovements`)
3. Enhance with structured data if `EnrichedSymbol` is provided (`enhanceWithStructuredData`)
4. Fall back to legacy keyword-based enhancement (`enhanceWithKeywords`) — **deprecated**

### 3. Modifier Verbalizer (`ModifierVerbalizer`)

Located in `verbalization-core/src/main/kotlin/com/i2vision/verbalization/modifier/`

Replaces regex-based modifier detection with a structured approach:

```kotlin
interface ModifierVerbalizer {
    fun verbalizeModifier(modifier: SymbolModifier): String
    fun verbalizeModifiers(modifiers: List<SymbolModifier>): String
}
```

**Implementations:**
- `KotlinModifierVerbalizer` — Kotlin-specific terminology (suspend, data class, sealed, etc.)
- `JavaModifierVerbalizer` — Java-specific terminology (record, synchronized, final, etc.)

**Example:**
```kotlin
val verbalizer = KotlinModifierVerbalizer()
verbalizer.verbalizeModifier(SymbolModifier(ModifierKind.SUSPEND)) // → "suspending"
verbalizer.verbalizeModifier(SymbolModifier(ModifierKind.DATA_CLASS)) // → "data class"
```

### 4. Verbalization Store (`FileVerbalizationStore`)

Located in `storage-core/src/main/kotlin/com/i2vision/storage/impl/`

Provides persistent caching of verbalization results:
- Stores results keyed by cluster ID and symbol hash
- Enables cache-hit detection to skip redundant verbalization
- Supports cache invalidation when symbol content changes

## Verbalization Strategies

| Strategy | Use Case | Description |
|----------|----------|-------------|
| `INCREMENTAL` | Fast, single-pass | Generates basic description from symbol content. Target: <50ms per symbol. |
| `MULTI_PASS` | Cross-layer enrichment | Runs multiple passes to add cross-references and context. Target: <200ms per symbol. |
| `LEARNING` | Feedback-driven | Applies user corrections and learns from feedback history. Target: <5000ms per symbol. |

## Self-Test Framework

Located in `discovery-validation/src/main/kotlin/com/i2vision/validation/VerbalizationSelfTest.kt`

An 8-phase validation system that runs after discovery to ensure verbalization quality:

### Phase 1: Strategy Routing Validation
Validates that the correct strategy is selected based on symbol complexity and intent.

### Phase 2: Layer Completeness Check
Ensures all required layers (VISION, STRUCTURE, LOGIC, FLOW, CODE) produce output.

### Phase 3: Quality Assessment
Analyzes each verbalization for:
- **Description length** — minimum 10 characters
- **Action verbs** — functions must contain action verbs
- **Tautological descriptions** — flagged when >80% of description tokens overlap with symbol name
- **Anti-patterns** — TOO_SHORT (suspect), NO_VERB (suspect), TAUTOLOGICAL (high severity)

### Phase 4: Cross-Layer Enrichment Verification
Compares INCREMENTAL vs MULTI_PASS output to verify enrichment adds novel tokens and cross-references.

### Phase 5: Performance Benchmarks
Measures latency against targets:
- INCREMENTAL: <50ms
- MULTI_PASS: <200ms
- LEARNING: <5000ms

### Phase 6: Cache Effectiveness
Analyzes cache hit rate and stale rate. Target: stale rate <5%.

### Phase 7: Feedback Application Validation
Verifies that user feedback corrections are applied to future verbalizations.

### Phase 8: Regression Detection
Compares current output against baselines to detect quality regressions.

### Quality Thresholds

```kotlin
const val MIN_DESCRIPTION_LENGTH = 10
const val MIN_ENRICHMENT_RATE = 0.7
const val MAX_STALE_RATE = 0.05
const val MAX_ANTI_PATTERN_RATE = 0.15
const val TAUTOLOGICAL_OVERLAP_THRESHOLD = 0.80
```

## Tautological Detection

A description is flagged as tautological when:
1. **Token overlap ratio** > 80%: Most meaningful words in the description also appear in the symbol name
2. **Generic prefix pattern**: Description is essentially just the symbol name with a generic verb like "performs", "handles", "manages"

**Example of tautological description:**
```
Symbol: "UserService"
Description: "performs user service" → TAUTOLOGICAL (generic prefix + high overlap)
```

**Example of good description:**
```
Symbol: "UserService"
Description: "Manages user authentication and profile operations" → OK
```

## Integration with Discovery Pipeline

The verbalization system is integrated into the `SelfDiscoveryTest` discovery validation:

```kotlin
// Step 6a: Generate verbalizations for each cluster
val intent = IntentDiscoveryIntent(
    goal = IntentGoal.FULL_DISCOVERY,
    focus = setOf(LayerFocus.VISION, LayerFocus.STRUCTURE, LayerFocus.LOGIC, LayerFocus.FLOW, LayerFocus.CODE),
    depth = discoveryDepth,
    quality = QualityFocus.BALANCED,
    forceFullVerbalization = selfTestMode  // Bypass cache in self-test mode
)

val results = verbalizationEngine.verbalize(
    clusterId = cluster.name,
    symbols = clusterSymbols,
    strategy = VerbalizationStrategy.INCREMENTAL,
    intent = intent
)

// Step 6b: Run self-test if enabled
if (selfTestMode) {
    val testReport = selfTest.execute(allResults, symbolsMap)
    // Export JSON report to .vision-ai/logs/
}
```

## Data Flow

```
Discovery Pipeline
       │
       ▼
┌─────────────────┐
│ Symbol Extractor │ → List<Symbol> (name, kind, content, filePath, lineNumber)
└─────────────────┘
       │
       ▼
┌──────────────────────┐
│ VerbalizationEngine  │ → Selects strategy based on intent
└──────────────────────┘
       │
       ▼
┌──────────────────────────┐
│ LlmVerbalizationClient   │ → Mock mode or LLM API call
│   ├─ describeFromContent │    (heuristic extraction)
│   ├─ enhanceWithStructured│    (ModifierVerbalizer)
│   └─ applyFeedback       │    (feedback history)
└──────────────────────────┘
       │
       ▼
┌─────────────────────┐
│ VerbalizationResult │ → description, confidence, strategy, metadata
└─────────────────────┘
       │
       ▼
┌─────────────────────┐
│ VerbalizationStore  │ → Persistent cache (clusterId + symbolHash)
└─────────────────────┘
       │
       ▼
┌─────────────────────┐
│ Self-Test Framework │ → 8-phase quality validation
└─────────────────────┘
```

## Configuration

### LLM Configuration File
Create `.i2vision/llm.config` to enable real LLM mode:
```properties
provider=openai
model=gpt-4
apiKey=your-api-key-here
temperature=0.3
maxTokens=500
timeoutMs=30000
```

### Programmatic Configuration
```kotlin
val config = LlmClientConfig(
    provider = "openai",
    model = "gpt-4",
    temperature = 0.3,
    maxTokens = 500,
    allowMockFallback = true,  // Fallback to mock if LLM unavailable
    timeoutMs = 30000
)
val client = DefaultLlmVerbalizationClient(config)
```

## Testing

### Integration Tests
Located in `discovery-validation/src/test/kotlin/com/i2vision/validation/VerbalizationIntegrationTest.kt`

Covers:
- Phase 1: Basic verbalization engine output
- Phase 2: Storage and retrieval
- Phase 3: Strategy-specific behavior (INCREMENTAL, MULTI_PASS)
- Phase 4: Cache and hash management
- Phase 5: Integration with discovery output

### Running Self-Test
```bash
# Run discovery with self-test enabled
gradlew :discovery-validation:run --args="--self-test"

# Or via the SelfDiscoveryTest main class
gradlew :discovery-validation:run
```

Self-test reports are exported to `.vision-ai/logs/verbalization-self-test-<timestamp>.json`.

## Key Files

| File | Purpose |
|------|---------|
| `verbalization-core/.../DefaultVerbalizationEngine.kt` | Main verbalization orchestrator |
| `verbalization-core/.../DefaultLlmVerbalizationClient.kt` | LLM client with mock fallback |
| `verbalization-core/.../modifier/ModifierVerbalizer.kt` | Structured modifier verbalization |
| `discovery-validation/.../VerbalizationSelfTest.kt` | 8-phase quality validation |
| `discovery-validation/.../ValidationTypes.kt` | Report data classes |
| `discovery-validation/.../SelfDiscoveryTest.kt` | Discovery runner with self-test integration |
| `storage-core/.../FileVerbalizationStore.kt` | Persistent verbalization cache |
