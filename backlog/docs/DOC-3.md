---
id: DOC-3
title: Incremental Sync Implementation Plan
---

# Incremental Sync Implementation Plan

## Overview

This document outlines the implementation plan for incremental verbalization sync in i2-Vision. The goal is to achieve >80% cache hit rates by only re-verbalizing symbols that have actually changed.

## Implementation Status

### ✅ Phase 1: Foundation (COMPLETED)

- [x] **P0 Task #1**: Document hash input specification and implement context hash
  - Created `HashManager.kt` with two-tier hashing (local + context)
  - Documented hash input specification in DOC-2
  - Implemented dependency extraction (imports, method calls, type references)
  - Added SHA-256 hashing with consistent ordering

- [x] **P0 Task #2**: Implement fallback chain for LEARNING strategy
  - Updated `Strategies.kt` with proper fallback: LEARNING → MULTI_PASS → INCREMENTAL
  - Added timeout handling (5000ms default)
  - Implemented batch processing (100 symbols per batch)
  - Added fallback rate monitoring and logging

- [x] **P0 Task #3**: Update DefaultVerbalizationEngine with context provider
  - Integrated `HashManager` into engine
  - Created `CrossLayerContextProvider` for multi-pass strategy
  - Added hash persistence (load/save for both local and context hashes)
  - Implemented proper constructor injection for all dependencies

- [x] **P0 Task #4**: Update storage layer for context hashes
  - Extended `VerbalizationStore` interface with `putContextHashes` / `getContextHashes`
  - Implemented in `FileVerbalizationStore` with separate file storage
  - Updated mock in tests for compatibility

### 🔄 Phase 2: Integration (IN PROGRESS)

- [ ] **P1 Task #5**: Write integration tests for incremental sync
  - Test hash computation accuracy
  - Test cache hit/miss scenarios
  - Test fallback chain behavior
  - Test context hash invalidation

- [ ] **P1 Task #6**: Add metrics collection and reporting
  - Track cache hit rates
  - Monitor hash computation times
  - Log fallback rates
  - Create dashboard-ready metrics

- [ ] **P1 Task #7**: Performance optimization
  - Implement lazy context hash computation
  - Add hash caching within sync session
  - Optimize dependency extraction
  - Benchmark against targets

### 📋 Phase 3: Polish (PLANNED)

- [ ] **P2 Task #8**: Documentation and examples
  - User guide for incremental sync
  - Configuration examples
  - Troubleshooting guide
  - Performance tuning guide

- [ ] **P2 Task #9**: Cleanup and refactoring
  - Remove old PatternMatcher-only code
  - Consolidate duplicate logic
  - Improve error messages
  - Add code comments

- [ ] **P2 Task #10**: Release preparation
  - Update changelog
  - Version bump
  - Release notes
  - Migration guide (if needed)

## Technical Architecture

### Component Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    DefaultVerbalizationEngine                │
├─────────────────────────────────────────────────────────────┤
│  - HashManager (two-tier: local + context)                  │
│  - PatternMatcher (built-in + custom patterns)              │
│  - FeedbackStore (user corrections)                         │
│  - LlmClient (with timeout + fallback)                      │
│  - Strategies:                                              │
│    • IncrementalVerbalizationStrategy                       │
│    • MultiPassVerbalizationStrategy                         │
│    • LearningVerbalizationStrategy                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ uses
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      VerbalizationStore                      │
├─────────────────────────────────────────────────────────────┤
│  - putVerbalizations() / getVerbalizations()                │
│  - putHashes() / getHashes()           (local hashes)       │
│  - putContextHashes() / getContextHashes() (context hashes) │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ persists to
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    File Storage (.verbalization/)            │
├─────────────────────────────────────────────────────────────┤
│  {clusterId}/                                               │
│    verbalizations.yaml                                      │
│    .meta/                                                   │
│      hashes.yaml              (local hashes)                │
│      context-hashes.yaml      (context hashes)              │
└─────────────────────────────────────────────────────────────┘
```

### Data Flow

```
1. User triggers sync
         │
2. Load existing hashes from storage
         │
3. For each symbol:
   ├─ Compute local hash
   ├─ Check if changed (fast path)
   │   └─ If NO: Skip (cache hit) ✅
   │   └─ If YES: Continue
   ├─ Compute context hash (slow path)
   ├─ Check if context changed
   │   └─ If YES: Mark for re-verbalization
   │   └─ If NO: Check strategy requirements
   └─ Verbalize if needed
         │
4. Store results + updated hashes
         │
5. Report metrics (cache hit rate, etc.)
```

## Key Classes and Responsibilities

### HashManager
**File**: `verbalization-core/src/main/kotlin/com/i2vision/verbalization/HashManager.kt`

**Responsibilities**:
- Compute local hashes (fast, symbol-only)
- Compute context hashes (slower, includes dependencies)
- Track hash changes (hasLocalChanged, hasContextChanged)
- Store and retrieve hashes from memory cache

**Key Methods**:
```kotlin
fun computeLocalHash(symbol: Symbol): String
fun computeContextHash(symbol: Symbol, dependencies: List<Symbol>): String
fun hasLocalChanged(symbol: Symbol, currentHash: String): Boolean
fun hasContextChanged(symbol: Symbol, currentHash: String): Boolean
fun updateHash(symbol: Symbol, localHash: String, contextHash: String?)
```

### IncrementalVerbalizationStrategy
**File**: `verbalization-core/src/main/kotlin/com/i2vision/verbalization/strategy/Strategies.kt`

**Responsibilities**:
- Fast incremental sync using local hashes only
- Pattern-based description generation
- Skip unchanged symbols (cache hits)

**Performance Target**: <50ms per 100 symbols

### MultiPassVerbalizationStrategy
**File**: `verbalization-core/src/main/kotlin/com/i2vision/verbalization/strategy/Strategies.kt`

**Responsibilities**:
- Two-pass verbalization (basic + context refinement)
- Cross-layer enrichment (Flow, Logic, Structure)
- Context hash computation and checking

**Performance Target**: <200ms per 100 symbols

### LearningVerbalizationStrategy
**File**: `verbalization-core/src/main/kotlin/com/i2vision/verbalization/strategy/Strategies.kt`

**Responsibilities**:
- LLM-based description generation
- Fallback chain: LLM → MULTI_PASS → INCREMENTAL
- User feedback integration
- Batch processing for efficiency

**Performance Target**: <5000ms per 100 symbols
**Fallback Rate Target**: <5%

### CrossLayerContextProvider
**File**: `verbalization-core/src/main/kotlin/com/i2vision/verbalization/DefaultVerbalizationEngine.kt`

**Responsibilities**:
- Extract dependencies from symbol content
- Detect architectural layer
- Identify calling flows
- Extract business rules

**Integration**: Used by MultiPassVerbalizationStrategy

## Testing Strategy

### Unit Tests

**HashManager Tests**:
- [ ] Test local hash computation consistency
- [ ] Test context hash includes dependencies
- [ ] Test change detection accuracy
- [ ] Test hash persistence (load/save)

**Strategy Tests**:
- [ ] Test Incremental strategy cache hits
- [ ] Test MultiPass strategy context refinement
- [ ] Test Learning strategy fallback chain
- [ ] Test timeout handling in Learning strategy

**Integration Tests**:
- [ ] Test full incremental sync flow
- [ ] Test with real file storage
- [ ] Test with large symbol sets (1000+ symbols)
- [ ] Test concurrent sync scenarios

### Performance Tests

**Benchmarks**:
- [ ] Hash computation time (local vs context)
- [ ] Cache hit rate with realistic code changes
- [ ] End-to-end sync time for various cluster sizes
- [ ] Memory usage during sync

**Targets**:
- Local hash: <0.5ms per symbol
- Context hash: <2ms per symbol
- Cache hit rate: >80%
- Overall sync: <10s for 1000 symbols (80% cache hit)

## Configuration

### Hash Configuration

```yaml
verbalization:
  hash:
    algorithm: SHA-256
    includeContent: true
    includeMetadata: true
    includeSignature: true
    contextHash:
      enabled: true
      includeDependencies: true
      includeImports: true
      includeMethodCalls: true
```

### Strategy Configuration

```yaml
verbalization:
  strategy:
    default: LEARNING
    fallback:
      enabled: true
      timeoutMs: 5000
      maxRetries: 1
    batch:
      size: 100
      parallelism: 4
    incremental:
      enabled: true
      cacheHitRateThreshold: 0.8
```

## Migration Path

### For Existing Projects

1. **Update Dependencies**:
   ```gradle
   implementation 'com.i2vision:verbalization-core:1.1.0'
   ```

2. **Migrate Hash Storage** (automatic on first sync):
   - Old format: Single hash file
   - New format: Separate local + context hash files
   - Migration: Automatic, no user action needed

3. **Configuration Changes**:
   - Old: `verbalization.strategy=LEARNING`
   - New: Same, but now supports fallback chain automatically

### Breaking Changes

- ❌ None - fully backward compatible

### Deprecations

- ⚠️ `VerbalizationEngine.needsReverbalization(symbol, hash)` - use `needsReverbalizationWithContext()` for context-aware checks

## Success Metrics

### Quantitative

- [ ] Cache hit rate >80% (measured over 100 syncs)
- [ ] False negative rate = 0% (critical)
- [ ] False positive rate <5%
- [ ] Sync time reduction >60% (compared to full sync)
- [ ] Fallback rate <5% (for LEARNING strategy)

### Qualitative

- [ ] User feedback: "Sync feels faster"
- [ ] Documentation: Clear and complete
- [ ] Code quality: No linting errors, good test coverage
- [ ] Performance: Meets all targets

## Risks and Mitigations

### Risk 1: Hash Collisions

**Probability**: Extremely low (SHA-256)
**Impact**: High (would miss changes)
**Mitigation**: Acceptable risk, monitor for reports

### Risk 2: Performance Regression

**Probability**: Medium
**Impact**: High (slow syncs)
**Mitigation**: 
- Lazy context hash computation
- Benchmarking before release
- Performance tests in CI

### Risk 3: False Positives (Unnecessary Re-verbalization)

**Probability**: Medium
**Impact**: Low (wastes time but correct)
**Mitigation**: 
- Fine-tune dependency extraction
- Monitor false positive rate
- Add configuration to disable context hash if needed

### Risk 4: Storage Bloat

**Probability**: Low
**Impact**: Low (hashes are small)
**Mitigation**: 
- Periodic orphan cleanup
- Compression for large hash files

## Next Steps

1. **Complete Phase 2** (current priority):
   - Write integration tests
   - Add metrics collection
   - Performance optimization

2. **Run Performance Benchmarks**:
   - Test with real projects
   - Measure cache hit rates
   - Optimize based on results

3. **Documentation**:
   - User guide
   - API documentation
   - Migration guide

4. **Release**:
   - Code review
   - QA testing
   - Version bump
   - Publish

## Appendix: Example Usage

### Basic Incremental Sync

```kotlin
val engine = DefaultVerbalizationEngine(
    verbalizationStore = FileVerbalizationStore(cacheStore),
    hashManager = HashManager()
)

val symbols = symbolRepository.getClusterSymbols("my-cluster")
val results = engine.verbalize(
    clusterId = "my-cluster",
    symbols = symbols,
    strategy = VerbalizationStrategy.INCREMENTAL,
    intent = DiscoveryIntent.COMPONENT_DISCOVERY
)

// Results only include changed symbols
println("Verbalized ${results.size} of ${symbols.size} symbols")
```

### Learning Strategy with Fallback

```kotlin
val engine = DefaultVerbalizationEngine(
    verbalizationStore = FileVerbalizationStore(cacheStore),
    llmClient = DefaultLlmVerbalizationClient(),
    feedbackStore = FeedbackStore()
)

val results = engine.verbalize(
    clusterId = "my-cluster",
    symbols = symbols,
    strategy = VerbalizationStrategy.LEARNING,
    intent = DiscoveryIntent.COMPONENT_DISCOVERY
)

// Check fallback rate
val fallbackRate = results.count { 
    it.metadata["fallback_used"] == "true" 
} / results.size.toDouble()

println("Fallback rate: ${String.format("%.1f", fallbackRate * 100)}%")
```

### Context-Aware Change Detection

```kotlin
val hashManager = HashManager()
val symbol = // ... get symbol

val localHash = hashManager.computeLocalHash(symbol)
val contextHash = hashManager.computeContextHash(symbol, dependencies)

if (hashManager.hasChanged(symbol, localHash, contextHash)) {
    // Symbol or its context changed - re-verbalize
    verbalize(symbol)
} else {
    // Cache hit - skip verbalization
    println("Cache hit for ${symbol.name}")
}
```

