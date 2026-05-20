---
id: DOC-2
title: Two-Tier Hash Specification for Incremental Verbalization
---

# Two-Tier Hash Specification for Incremental Verbalization

## Overview

This document specifies the hash-based change detection system for incremental verbalization in i2-Vision. The system implements a two-tier hashing approach to maximize cache hit rates while ensuring accurate invalidation when code changes.

## Design Goals

- **Cache Hit Rate Target**: >80%
- **Performance Target**: <50ms per 100 symbols (local hash), <200ms per 100 symbols (context hash)
- **Accuracy**: Zero false negatives (never skip a symbol that actually changed)
- **Minimal False Positives**: Avoid re-verbalizing unchanged symbols

## Hash Types

### 1. Local Hash (Fast Check)

**Purpose**: Detect direct changes to a symbol's own source code.

**Computed From**:
- Symbol name
- Symbol kind (class, function, property, etc.)
- Symbol content (full body text)
- Symbol metadata (annotations, modifiers, visibility)
- Symbol signature (parameters, return type)

**Formula**:
```
localHash = SHA256(
  name + ":" +
  kind + ":" +
  content + ":" +
  metadata(sorted) + ":" +
  signature
)
```

**Use Case**: Primary check for incremental sync. Fast computation (~0.5ms per symbol).

**Invalidation Triggers**:
- ✅ Function body changed
- ✅ Class properties modified
- ✅ Annotations added/removed
- ✅ Parameter list changed
- ✅ Return type changed

### 2. Context Hash (Slow Check)

**Purpose**: Detect changes in symbol's dependencies and calling context.

**Computed From**:
- All Local Hash inputs PLUS:
- Direct dependencies (imports used in symbol body)
- Called methods/functions (extracted from symbol content)
- Calling symbols (reverse lookup - who calls this symbol)
- Interface/parent class references
- Type annotations and generic parameters

**Formula**:
```
contextHash = SHA256(
  localHash + ":" +
  dependencyHashes(sorted, joined by "|") + ":" +
  extractedDependencySignatures(sorted, joined by ",")
)
```

**Use Case**: Secondary check for cross-file changes. Slower computation (~2ms per symbol).

**Invalidation Triggers**:
- ✅ Called method renamed/removed
- ✅ Interface contract changed
- ✅ Dependency class modified
- ✅ Import statements changed
- ✅ Type hierarchy modified

## Hash Storage

### File Structure

```
.verbalization/
  {clusterId}/
    verbalizations.yaml      # Verbalization results
    .meta/
      hashes.yaml            # Local hashes (symbol -> hash)
      context-hashes.yaml    # Context hashes (symbol -> hash)
```

### Storage Format

**hashes.yaml**:
```yaml
"src/main/kotlin/com/example/MyService.kt:42:processPayment": "a1b2c3d4e5f6..."
"src/main/kotlin/com/example/MyService.kt:58:validateAmount": "f6e5d4c3b2a1..."
```

**context-hashes.yaml**:
```yaml
"src/main/kotlin/com/example/MyService.kt:42:processPayment": "9f8e7d6c5b4a..."
"src/main/kotlin/com/example/MyService.kt:58:validateAmount": "4a5b6c7d8e9f..."
```

## Invalidation Algorithm

### Incremental Sync Flow

```
for each symbol in cluster:
  1. Compute currentLocalHash
  2. Check if local hash changed:
     - If NO: Skip verbalization (cache hit)
     - If YES: Continue to step 3
  3. Compute currentContextHash (optional, for high-confidence checks)
  4. Check if context hash changed:
     - If YES: Mark for re-verbalization
     - If NO: Check strategy requirements
  5. Verbalize if needed
  6. Update both hashes in storage
```

### Pseudocode

```kotlin
fun needsReverbalization(symbol: Symbol): Boolean {
    val currentLocalHash = hashManager.computeLocalHash(symbol)
    
    // Fast path: check local hash first
    if (!hashManager.hasLocalChanged(symbol, currentLocalHash)) {
        return false  // Cache hit - no changes
    }
    
    // Slow path: check context hash for cross-file changes
    val dependencies = getDependencies(symbol)
    val currentContextHash = hashManager.computeContextHash(symbol, dependencies)
    
    if (hashManager.hasContextChanged(symbol, currentContextHash)) {
        return true  // Context changed - re-verbalize
    }
    
    // Local changed but context stable - depends on strategy
    return strategy.requiresLocalRefresh()
}
```

## Dependency Extraction

### Import Dependencies

```kotlin
val importPattern = Regex("""import\s+([\w.]+)""")
val imports = importPattern.findAll(content)
    .map { it.groupValues[1] }
    .filter { !it.startsWith("kotlin") && !it.startsWith("java") }
```

### Method Call Dependencies

```kotlin
val callPattern = Regex("""(\w+)\s*\(""")
val calls = callPattern.findAll(content)
    .map { it.groupValues[1] }
    .filter { it.length > 2 && it.first().isUpperCase() }
```

### Type Reference Dependencies

```kotlin
val typePattern = Regex(""":\s*(\w+)""")
val types = typePattern.findAll(content)
    .map { it.groupValues[1] }
    .filter { it.first().isUpperCase() && it.length > 2 }
```

## Performance Optimizations

### 1. Lazy Context Hash Computation

Only compute context hash if local hash changed:

```kotlin
if (!localHashChanged) {
    return false  // Skip expensive context hash
}
```

### 2. Batch Processing

Process symbols in batches of 100 for LLM strategies:

```kotlin
val batches = symbols.chunked(100)
for (batch in batches) {
    processBatch(batch)
}
```

### 3. Hash Caching

Cache computed hashes during a single sync session:

```kotlin
private val hashCache = mutableMapOf<String, String>()

fun getHash(symbol: Symbol): String {
    val key = symbol.key()
    return hashCache.getOrPut(key) { computeHash(symbol) }
}
```

### 4. Parallel Computation

Compute hashes in parallel for large clusters:

```kotlin
symbols.parStream()
    .map { computeLocalHash(it) }
    .collect(toList())
```

## Cache Hit Rate Analysis

### Expected Distribution

| Change Type | Frequency | Detection | Strategy |
|-------------|-----------|-----------|----------|
| No change | 80% | Local hash match | Skip |
| Local body change | 10% | Local hash mismatch | Re-verbalize |
| Dependency change | 5% | Context hash mismatch | Re-verbalize |
| Interface change | 3% | Context hash mismatch | Re-verbalize |
| Annotation change | 2% | Local hash mismatch | Re-verbalize |

### Target Metrics

- **Overall Cache Hit Rate**: >80%
- **False Negative Rate**: 0% (critical - must never miss a change)
- **False Positive Rate**: <5% (acceptable - re-verbalizing unchanged is safe but wasteful)
- **Hash Computation Overhead**: <10% of total sync time

## Edge Cases

### 1. First-Time Verbalization

**Scenario**: No stored hashes exist.

**Handling**: Treat as "changed" and verbalize all symbols.

```kotlin
val storedHash = hashes[symbolKey]
if (storedHash == null) {
    return true  // First time - always verbalize
}
```

### 2. Hash Collision

**Scenario**: Two different symbol contents produce same hash (extremely rare with SHA-256).

**Handling**: Acceptable risk. SHA-256 collision probability is ~10^-77.

### 3. Symbol Renamed

**Scenario**: Symbol renamed but content unchanged.

**Handling**: Treated as deletion + addition. Old hash entry becomes orphan (cleaned up periodically).

### 4. File Moved

**Scenario**: File moved to different directory.

**Handling**: Symbol key includes file path, so treated as new symbol. Old entry becomes orphan.

### 5. Circular Dependencies

**Scenario**: Symbol A depends on B, B depends on A.

**Handling**: No special handling needed. Hash computation is acyclic (only depends on current state, not computed hashes).

## Monitoring and Metrics

### Key Metrics to Track

```kotlin
data class HashMetrics(
    val totalSymbols: Int,
    val localHashHits: Int,
    val localHashMisses: Int,
    val contextHashHits: Int,
    val contextHashMisses: Int,
    val verbalizationsSkipped: Int,
    val verbalizationsPerformed: Int,
    val avgLocalHashTimeMs: Double,
    val avgContextHashTimeMs: Double,
    val cacheHitRate: Double  // (localHashHits + contextHashHits) / totalSymbols
)
```

### Logging

```kotlin
logger.info {
    "Incremental sync complete: " +
    "${metrics.verbalizationsSkipped} skipped, " +
    "${metrics.verbalizationsPerformed} verbalized, " +
    "cache hit rate: ${String.format("%.1f", metrics.cacheHitRate * 100)}%"
}
```

## Future Enhancements

### 1. Semantic Hashing

Use AST-based hashing instead of text-based for better resilience to formatting changes.

### 2. Dependency Graph Caching

Cache dependency graph to avoid re-extraction on every sync.

### 3. Incremental Context Hash Updates

When a symbol changes, only update context hashes for symbols that depend on it (reverse dependency lookup).

### 4. Machine Learning for Hash Strategy

Learn which symbols benefit from context hash checking vs. local hash only.

## Implementation Checklist

- [x] Define hash input specification (this document)
- [x] Implement `HashManager` with two-tier hashing
- [x] Update `VerbalizationStore` interface with context hash methods
- [x] Implement `FileVerbalizationStore` context hash persistence
- [x] Update strategies to use hash manager
- [ ] Add hash metrics collection and reporting
- [ ] Implement orphan hash cleanup
- [ ] Add performance benchmarks
- [ ] Write integration tests for incremental sync

## Related Documents

- [Verbalization Strategy Selection](./strategy-selection.md)
- [Incremental Sync Architecture](./incremental-sync.md)
- [Performance Optimization Guide](./performance.md)

