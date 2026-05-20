# ADR-001: Structured Modifier Detection for Verbalization

## Status

Accepted

## Context

The i2-Vision system provides architectural discovery and verbalization capabilities for codebases. The verbalization component transforms discovered symbols into natural language descriptions for use by LLMs and developers.

### Current Architecture

```
┌─────────────────────┐     ┌─────────────────────┐     ┌─────────────────────┐
│   Discovery Phase   │────▶│   Verbalization     │────▶│   Storage Layer     │
│   (i2vision-discover│     │   (verbalization-   │     │   (storage-core)    │
│    architecture)    │     │    core)            │     │                     │
└─────────────────────┘     └─────────────────────┘     └─────────────────────┘
         │                           │                           │
         ▼                           ▼                           ▼
   AST Analysis             addKotlinTerminology()         InMemoryStorage
   Symbol Extraction        regex-based detection          File-based Storage
```

### Current Challenges

The `addKotlinTerminology()` function in the verbalization phase:

1. **False Positives**: Uses regex patterns on raw source code, matching strings, comments, and dead code
2. **Conflated Concerns**: Combines detection and verbalization in one step
3. **Language Fragility**: Hardcoded Kotlin-specific patterns, not extensible to Java/TypeScript
4. **Grammatical Errors**: Produces awkward phrases like "abstract class Foo" instead of "abstract class implementation"

## Problem

The current regex-based approach to modifier detection in verbalization is not sustainable for long-term maintainability and extensibility.

### Specific Issues

| Issue | Impact | Example |
|-------|--------|---------|
| Regex false positives | Incorrect verbalizations | Matching "data" in `"var data = \"old\"` (unused) |
| Detection/verbalization coupling | Hard to test/modify | Can't change verbalization without affecting detection |
| Kotlin-only patterns | Blocks multi-language roadmap | Java `@Repository` not detected |
| No structural awareness | Misses architectural context | Can't detect Repository pattern by inheritance |

## Decision

Move modifier detection from the verbalization phase to the discovery phase using AST analysis. Create structured types that flow through the system.

### New Architecture

```
┌─────────────────────┐     ┌─────────────────────┐     ┌─────────────────────┐     ┌─────────────────────┐
│   Discovery Phase   │────▶│   Enrichment Phase  │────▶│   Verbalization     │────▶│   Storage Layer     │
│                     │     │                     │     │   (ModifierVerbalizer)     │                     │
└─────────────────────┘     └─────────────────────┘     └─────────────────────┘     └─────────────────────┘
         │                           │                           │                           │
         ▼                           ▼                           ▼                           ▼
   AST Analysis              EnrichedSymbol              Structured verbalization      Cached verbalizations
   Symbol Extraction         (modifiers, roles,          from structured data          with metadata
                              dependencies, flows,                                    (confidence, source)
                              businessRules)
```

### Key Components

#### 1. Structured Types (`architecture-types`)

```kotlin
// SymbolModifier: Structured modifier representation
data class SymbolModifier(
    val kind: ModifierKind,      // SUSPEND, DATA_CLASS, REPOSITORY, etc.
    val properties: Map<String, Any> = emptyMap(),
    val source: ModifierSource   // AST, ANNOTATION, NAMING, PATH, INFERENCE
)

// EnrichedSymbol: Symbol with all enriched data
data class EnrichedSymbol(
    val symbol: Symbol,
    val modifiers: List<SymbolModifier> = emptyList(),
    val structuralRole: StructuralRole? = null,  // CONTROLLER, SERVICE, REPOSITORY, etc.
    val dependencies: List<String> = emptyList(),
    val flows: List<String> = emptyList(),
    val businessRules: List<String> = emptyList(),
    val technicalContext: TechnicalContext? = null
)

// ModifierKind: Enum with all modifier categories
enum class ModifierKind {
    // Language modifiers
    SUSPEND, DATA_CLASS, VALUE_CLASS, SEALED_CLASS, SEALED_INTERFACE,
    COMPANION_OBJECT, DELEGATE, RECORD,
    // Concurrency modifiers
    SYNC, ASYNC, STATIC, FINAL, ABSTRACT, OVERRIDE,
    // Architectural modifiers
    REPOSITORY, CONTROLLER, SERVICE, FACTORY, BUILDER,
    OBSERVER, STRATEGY, SINGLETON,
    // Business modifiers
    BUSINESS_RULE, INVARIANT, VALIDATION,
    // Flow modifiers
    FLOW, SEQUENCE, EVENT_PUBLISHER, EVENT_CONSUMER,
    // Technical modifiers
    DATABASE_ACCESS, EXTERNAL_CALL, CACHE_ACCESS,
    UNKNOWN
}

// StructuralRole: Architectural role of a component
enum class StructuralRole {
    CONTROLLER, SERVICE, REPOSITORY, ENTITY, DTO,
    VALIDATOR, FACTORY, BUILDER, ORCHESTRATOR, DISPATCHER,
    TRANSFORMER, AGGREGATOR, EVENT_PRODUCER, EVENT_CONSUMER,
    EVENT_HANDLER, UTIL, CONFIG, UNKNOWN
}
```

#### 2. Modifier Extractor Interface (`architecture-types`)

```kotlin
interface ModifierExtractor<T> {
    fun extractModifiers(node: T): List<SymbolModifier>
    fun extractStructuralRole(node: T): StructuralRole?
    fun extractTechnicalContext(node: T): TechnicalContext?
    fun enrich(symbol: Symbol, node: T, builder: EnrichedSymbolBuilder)
}
```

#### 3. Kotlin Implementation (`architecture-types`)

```kotlin
class KotlinModifierExtractor : ModifierExtractor<KtNode> {
    override fun extractModifiers(node: KtNode): List<SymbolModifier> {
        // AST-based detection using PsiElement analysis
        // Detects: annotations, naming patterns, inheritance, declarations
    }

    override fun extractStructuralRole(node: KtNode): StructuralRole? {
        // @Repository, @Controller, @Service annotations
        // *Repository, *Controller, *Service naming
        // implements Repository interface
    }

    override fun extractTechnicalContext(node: KtNode): TechnicalContext {
        // @Transactional, @Async annotations
        // JPA, JDBC, RestTemplate imports
    }
}
```

#### 4. Verbalizer Pattern (`verbalization-core`)

```kotlin
// ModifierVerbalizer: Converts SymbolModifier to natural language
class ModifierVerbalizer {
    fun verbalize(modifier: SymbolModifier): String
    fun verbalizeAll(modifiers: List<SymbolModifier>): List<String>
}

// Extension functions for integration
fun EnrichedSymbol.verbaizeStructuralRole(): String?
fun EnrichedSymbol.verbalizeModifiers(): String
fun List<SymbolModifier>.verbalize(): String
```

### Data Flow

```
Discovery Phase                    Enrichment Phase              Verbalization Phase
─────────────────                  ──────────────────            ─────────────────────
                                    ┌─────────────────┐
KtFile ──────▶ Symbol ──────────▶│ EnrichedSymbol │─────────▶ Verbalization
  │                │              │  with modifiers│              with metadata
  ▼                │              │  roles, deps   │              confidence, source
AST Analysis       │              └─────────────────┘
  │                │                      │
  ▼                ▼                      ▼
ClassDeclaration ───── KotlinModifierExtractor ─────▶ ModifierVerbalizer
  │    │            (AST-based detection)          (Structured verbalization)
  │    ▼
  ▼ Annotation @Repository
    (detected as REPOSITORY)
```

## Consequences

### Positive

| Benefit | Description |
|---------|-------------|
| Zero false positives | AST analysis only matches actual code, not strings/comments |
| Language agnostic design | `ModifierExtractor<T>` interface enables Java, TypeScript support |
| Separation of concerns | Detection (discovery) vs Verbalization (presentation) |
| Testability | Structured types enable precise unit testing |
| Extensibility | New modifiers added in one place, used everywhere |
| Caching | EnrichedSymbol cached, verbalization computed once |
| Confidence scoring | Multiple sources (AST, annotation, naming) enable confidence weighting |

### Negative

| Challenge | Mitigation |
|-----------|------------|
| Cache migration | Existing index cache needs re-generation with new format |
| Breaking API change | EnrichedSymbol is internal, minimal external impact |
| Initial complexity | One-time implementation, simplifies future changes |
| Learning curve | Clear documentation (this ADR) and extension guide |

### Neutral

| Impact | Description |
|--------|-------------|
| Memory usage | EnrichedSymbol larger than raw Symbol, but reduces compute |
| Discovery time | AST analysis slightly slower than regex, but more accurate |

## Compliance

- [x] `architecture-types` module updated with `SymbolModifier`, `EnrichedSymbol`, `ModifierExtractor`
- [x] `i2vision-discover` updated to use `KotlinModifierExtractor` for enrichment
- [x] `verbalization-core` refactored to use `ModifierVerbalizer` pattern
- [x] All tests passing (152 tests in verbalization-core)
- [x] Documentation complete (this ADR, migration guide)

## Future Extensibility

### Multi-Language Support

```kotlin
// Java implementation
class JavaModifierExtractor : ModifierExtractor<PsiClass> {
    override fun extractModifiers(node: PsiClass): List<SymbolModifier> {
        // @Repository, @Service, @Component annotations
        // implements Repository, Service interfaces
        // *Repository, *Service naming patterns
    }
}

// TypeScript implementation
class TypeScriptModifierExtractor : ModifierExtractor<PsiClass> {
    override fun extractModifiers(node: PsiClass): List<SymbolModifier> {
        // @Controller, @Injectable decorators
        // extends Controller, Service classes
        // *Controller, *Service naming patterns
    }
}
```

### New Modifier Types

Add new `ModifierKind` values and update verbalizer:

```kotlin
enum class ModifierKind {
    // Existing...
    MICROSERVICE,   // NEW: Microservice boundary indicator
    EVENT_SOURCED,  // NEW: Event sourcing pattern
    // ...
}

// ModifierVerbalizer.kt
private fun verbalizeMicroservice(modifier: SymbolModifier): String {
    return "microservice component"
}
```

### Rule-Based Enrichment

Extend `TechnicalContext` with custom rules:

```kotlin
data class TechnicalContext(
    val hasDatabaseAccess: Boolean = false,
    val hasExternalCalls: Boolean = false,
    val hasCacheAccess: Boolean = false,
    val isTransactional: Boolean = false,
    val isAsync: Boolean = false,
    val properties: Map<String, Any> = emptyMap()
    // Future: custom rules
    val securityContext: SecurityContext? = null,
    val retryPolicy: RetryPolicy? = null
)
```

## Related ADRs

- **ADR-002**: Cross-layer enrichment for MULTI_PASS verbalization strategy
- **ADR-003**: Feedback guardrails with scope, expiry, and review workflow

## References

- [Migration Guide](../migrations/structured-modifier-detection.md)
- [CNS System Architecture](../reference/cns-calibration-plan.md)
- [EnrichedSymbol Source Code](architecture-types/src/main/kotlin/com/i2vision/arch/signature/EnrichedSymbol.kt)
- [ModifierExtractor Source Code](architecture-types/src/main/kotlin/com/i2vision/arch/detector/ModifierExtractor.kt)
- [ModifierVerbalizer Source Code](verbalization-core/src/main/kotlin/com/i2vision/verbalization/modifier/ModifierVerbalizer.kt)

---

**Decision Date**: 2024-05-14
**Author**: Architecture Team
**Reviewers**: Development Team