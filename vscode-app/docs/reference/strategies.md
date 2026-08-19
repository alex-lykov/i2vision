# Verbalization Strategies

## Overview

Verbalization strategies transform raw code symbols into meaningful architectural descriptions. The system uses three complementary strategies, automatically selected based on the **Cluster Context Neediness Score (CNS)**.

---

## Strategy Types

| Strategy | Use Case | Latency (100 symbols) | CNS Range |
|----------|----------|----------------------|-----------|
| **INCREMENTAL** | Hash-based change detection | < 50ms | 0-30 |
| **MULTI_PASS** | Context-aware refinement | < 200ms | 31-60 |
| **LEARNING** | LLM + feedback integration | < 5000ms | 61-100 |

---

## Strategy Selection

The **ContextNeedinessCalculator** automatically selects the optimal strategy based on CNS:

```kotlin
val cns = calculator.calculate(clusterId)
val strategy = when {
    cns.total <= 30 -> VerbalizationStrategy.INCREMENTAL
    cns.total <= 60 -> VerbalizationStrategy.MULTI_PASS
    else -> VerbalizationStrategy.LEARNING
}
```

### CNS Components

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

## INCREMENTAL Strategy

### Purpose

Fast, hash-based verbalization for unchanged or minimally changed code.

### How It Works

1. Compute hash for each symbol
2. Skip symbols that haven't changed (cache hit)
3. Use pattern matching for changed symbols
4. Update hash after successful verbalization

### Implementation

```kotlin
class IncrementalVerbalizationStrategy(
    private val patternMatcher: PatternMatcher,
    private val hashManager: HashManager
) : VerbalizationStrategyImpl {

    override suspend fun verbalize(
        symbols: List<Symbol>,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {
        // Hash-based change detection
        // Pattern matching for changed symbols
        // Cache update
    }
}
```

### Best For

- ✅ Routine discovery runs
- ✅ Small code changes
- ✅ Performance-critical scenarios
- ✅ Low CNS clusters (0-30)

### Performance

- **Latency**: < 50ms per 100 symbols
- **Cache Hit Rate**: > 80%
- **Confidence**: 0.6-0.8 (pattern-dependent)

---

## MULTI_PASS Strategy

### Purpose

Context-aware refinement for moderately complex clusters requiring deeper analysis.

### How It Works

**Pass 1: Basic Pattern Matching**
- Generate initial descriptions using patterns
- Confidence: ~0.7

**Pass 2: Context Refinement**
- Retrieve architectural context (dependencies, related symbols)
- Refine descriptions with broader context
- Boost confidence by +0.2

### Implementation

```kotlin
class MultiPassVerbalizationStrategy(
    private val patternMatcher: PatternMatcher,
    private val contextProvider: ContextProvider,
    private val hashManager: HashManager
) : VerbalizationStrategyImpl {

    override suspend fun verbalize(
        symbols: List<Symbol>,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {
        // Pass 1: Pattern matching
        // Pass 2: Context refinement
        // Return enhanced results
    }
}
```

### Context Enhancement Examples

| Pattern | Enhancement |
|---------|-------------|
| "service class" | "service class (coordinates with 3 related services)" |
| "handles requests" | "handles requests (with database access)" |
| "processes data" | "processes data (async operation)" |

### Best For

- ✅ Moderate CNS clusters (31-60)
- ✅ Service-oriented architectures
- ✅ Clusters with cross-cutting concerns
- ✅ When context adds significant value

### Performance

- **Latency**: < 200ms per 100 symbols
- **Confidence Boost**: +0.2 over INCREMENTAL
- **Context Usage**: Dependencies, related symbols, architectural layer

---

## LEARNING Strategy

### Purpose

AI-powered verbalization with continuous improvement through user feedback.

### How It Works

1. **Check Feedback First**: If user provided high-rated correction (rating >= 4), use it
2. **Build Context**: Extract dependencies, related symbols, architectural layer
3. **LLM Generation**: Send enriched prompt to LLM with:
   - Symbol information (name, kind, file, code)
   - Heuristic description from pattern matcher
   - Architectural context
   - Feedback history
4. **Combine Results**: Use LLM output or fall back to heuristic

### Implementation

```kotlin
class LearningVerbalizationStrategy(
    private val patternMatcher: PatternMatcher,
    private val llmClient: LlmVerbalizationClient,
    private val feedbackStore: FeedbackStore,
    private val hashManager: HashManager,
    private val clusterId: String = "default"
) : VerbalizationStrategyImpl {

    override suspend fun verbalize(
        symbols: List<Symbol>,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {
        // 1. Check for user feedback
        // 2. Build context for LLM
        // 3. Generate with LLM + feedback history
        // 4. Return enriched results
    }
}
```

### Feedback Format (JSONL)

```jsonl
{"timestamp": 1709234567890, "symbolId": "core/auth/AuthService#authenticate", 
 "originalDescription": "Service class for auth operations", 
 "correction": "Validates credentials via bcrypt, issues JWT with role claims, enforces rate limiting", 
 "userId": "dev@company.com", "rating": 5, "reason": "original too vague"}
```

### LLM Prompt Template

```
You are an expert software architect creating documentation for code symbols.
Given the following information, produce a concise, accurate description.

## Symbol Information
- Name: {symbol.name}
- Kind: {symbol.kind}
- File: {symbol.filePath}
- Code: {symbol.content}

## Current Heuristic Description
{heuristic.description}

## Architectural Context
- Module: {context.moduleName}
- Dependencies: {context.dependencies}
- Related symbols: {context.relatedSymbols}
- Architectural Layer: {context.architecturalLayer}

## User Feedback History
{feedbackHistory}

## Task
Write a description that:
1. Explains what the symbol DOES, not just what it IS
2. Includes business-relevant details (validation rules, side effects)
3. Mentions architectural role if relevant
4. Is 1-2 sentences maximum

Output only the description, no markdown.
```

### Kotlin-Specific Enhancements

The LLM client automatically detects and enhances Kotlin terminology:

| Pattern | Enhancement |
|---------|-------------|
| `suspend` function | Adds "asynchronous operation", "non-blocking" |
| `data class` | Adds "immutable value container" |
| `sealed class` | Adds "restricted hierarchy for state modeling" |
| `inline class` | Adds "zero-overhead type wrapper" |
| `companion object` | Adds "static factory/utility holder" |
| `by` delegation | Adds "delegate implementation" |

### Best For

- ✅ High CNS clusters (61-100)
- ✅ Complex domain logic
- ✅ When user feedback is available
- ✅ Documentation-critical codebases

### Performance

- **Latency**: < 5000ms per 100 symbols (LLM API call)
- **Confidence**: 0.8-0.95 (with feedback)
- **Improvement**: >20% over heuristic (measured via feedback corrections)

---

## Configuration

### Feature Flags (`.i2vision/config/verbalization.yaml`)

```yaml
verbalization:
  enabled: true
  defaultStrategy: INCREMENTAL
  cnsEnabled: true
  multiLayerEnabled: true
  learningEnabled: true
  llm:
    provider: openai  # or anthropic, vertexai
    model: gpt-4
    apiKeyEnv: OPENAI_API_KEY
```

### Strategy Override via CLI

```bash
# Force specific strategy
./gradlew :i2vision-cli:run --args="discover my-cluster --strategy LEARNING"

# Let CNS decide (default)
./gradlew :i2vision-cli:run --args="discover my-cluster"
```

---

## Quality Metrics

### Confidence Scores

| Strategy | Base Confidence | With Feedback | With Context |
|----------|-----------------|---------------|--------------|
| INCREMENTAL | 0.6-0.8 | N/A | N/A |
| MULTI_PASS | 0.7-0.9 | N/A | +0.2 |
| LEARNING | 0.6-0.8 | 0.95 | +0.1 |

### Success Criteria

| Metric | Target | Measurement |
|--------|--------|-------------|
| Description quality | >4.0/5.0 | User feedback ratings |
| Symbol coverage | >95% | Symbols with verbalizations |
| Cache hit rate | >80% | Incremental strategy |
| Learning improvement | >20% | Feedback corrections |

---

## Multi-Layer Integration

Verbalization strategies work across all 5 VSLFC layers:

| Layer | Strategy Application |
|-------|---------------------|
| **Vision** | MULTI_PASS/LEARNING for requirements analysis |
| **Structure** | INCREMENTAL for component detection |
| **Logic** | LEARNING for business rule extraction |
| **Flow** | MULTI_PASS for interaction mapping |
| **Code** | All strategies (primary verbalization layer) |

### Layer-Specific Verbalizers

```kotlin
interface LayerVerbalizer {
    val layerName: String
    suspend fun verbalize(
        symbol: Symbol,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): VerbalizationResult
}

// Implementations:
// - VisionVerbalizer (VisionContext)
// - StructureVerbalizer (StructureContext)
// - LogicVerbalizer (LogicContext)
// - FlowVerbalizer (FlowContext)
// - CodeVerbalizer (CodeContext)
```

---

## Monitoring

### Metrics to Track

```yaml
# Logged during discovery
cns_score: 45.2
strategy_selected: MULTI_PASS
symbols_processed: 150
cache_hits: 120
llm_calls: 0
feedback_used: 0
avg_confidence: 0.82
```

### MCP Endpoints

```kotlin
// Get CNS for specific cluster
@McpTool
fun getClusterNeediness(clusterId: String): ClusterNeedinessScore

// List clusters ranked by neediness
@McpTool
fun listClustersByNeediness(limit: Int = 10): List<ClusterNeedinessScore>
```

---

## Best Practices

1. **Start with INCREMENTAL**: Default strategy handles 80% of cases efficiently
2. **Let CNS Decide**: Trust the automatic strategy selection based on cluster neediness
3. **Provide Feedback**: Rate and correct descriptions to improve LEARNING strategy
4. **Monitor Cache Hits**: >80% indicates healthy incremental processing
5. **Use MULTI_PASS for Services**: Context refinement adds value for service-oriented code
6. **Enable LEARNING for Core Domain**: High-value code deserves LLM-powered descriptions

---

## Implementation Status

| Component | Status | Location |
|-----------|--------|----------|
| IncrementalVerbalizationStrategy | ✅ Complete | `verbalization-core/strategy/Strategies.kt` |
| MultiPassVerbalizationStrategy | ✅ Complete | `verbalization-core/strategy/Strategies.kt` |
| LearningVerbalizationStrategy | ✅ Complete | `verbalization-core/strategy/Strategies.kt` |
| ContextNeedinessCalculator | ✅ Complete | `verbalization-core/metrics/` |
| FeedbackStore | ✅ Complete | `verbalization-core/feedback/` |
| LlmVerbalizationClient | ✅ Complete | `verbalization-core/llm/` |
| DefaultLlmVerbalizationClient | ✅ Complete | `verbalization-core/llm/` |
| LayerVerbalizers (5) | ✅ Complete | `verbalization-core/layer/` |

---

## References

- **[Verbalization Refactoring Plan](../../backlog/docs/DOC-1.md)**: Complete architecture documentation
- **[VSLFC Layers](../concepts/vslfc-layers.md)**: Five-layer contract system
- **[verbalization-core README](../../verbalization-core/README.md)**: Module documentation
- **[Test Coverage](../../verbalization-core/src/test/)**: Unit and integration tests
- **[MCP Tools](../guides/mcp-tools.md)**: Cluster neediness endpoints
