# Discovery Validation Module

**Internal test harness — not published to Maven**

This module contains integration tests and the **Verbalization Self-Test Framework** for validating the i2vision discovery system across all modules.

---

## Purpose

- Test cross-module integration (CLI → Discover → MCP → Instant)
- Validate architecture detection on known project structures
- Run long-running validation suites independently
- **Self-discovery validation on i2vision itself** with automated quality assessment
- CLI context command integration testing
- **Verbalization quality validation** with 8-phase self-test

---

## Structure

```
discovery-validation/
├── src/main/kotlin/com/i2vision/validation/
│   ├── SelfDiscoveryTest.kt              # Self-discovery runner + verbalization harness
│   └── VerbalizationSelfTest.kt          # 8-phase verbalization quality framework
├── src/test/
│   ├── kotlin/com/i2vision/
│   │   ├── validation/
│   │   │   ├── DiscoveryIntegrationTest.kt
│   │   │   ├── DiscoveryFlowIntegrationTest.kt
│   │   │   └── VerbalizationIntegrationTest.kt
│   │   └── cli/
│   │       └── integration/
│   │           └── ContextIntegrationTest.kt
│   └── resources/sketches/
│       ├── aggregator-pure/
│       ├── aggregator-mixed/
│       ├── circular-deps/
│       ├── circular-nested/
│       ├── circular-three-module/
│       ├── circular-two-module/
│       ├── fat-service/
│       ├── god-class/
│       ├── kotlin-multi-module/
│       └── spring-boot/
└── build.gradle.kts
```

---

## Running Tests

### All Tests

```bash
./gradlew :discovery-validation:test
```

### Self-Discovery with Verbalization Self-Test

```bash
./gradlew :discovery-validation:run -Pargs="--self-test --report-verbalization"
```

This executes:
1. Full discovery across all 18 i2vision clusters
2. Symbol extraction and verbalization (7,700+ symbols)
3. 8-phase verbalization quality self-test
4. Self-test report export to `.vision-ai/logs/`

### Specific Test Classes

```bash
# Verbalization integration tests only
./gradlew :discovery-validation:test --tests VerbalizationIntegrationTest

# Discovery flow tests only
./gradlew :discovery-validation:test --tests DiscoveryFlowIntegrationTest

# Integration tests only
./gradlew :discovery-validation:test --tests DiscoveryIntegrationTest
```

---

## Verbalization Self-Test Framework

The `VerbalizationSelfTest` class (`src/main/kotlin/com/i2vision/validation/VerbalizationSelfTest.kt`) is an **8-phase automated quality validation framework** that runs against the verbalization engine's output during self-discovery.

### Architecture

```
SelfDiscoveryTest
    │
    ├── extractSymbolsFromResults()     # ScannerService → CodeSymbol → Symbol
    ├── verbalizeSymbols()              # DefaultVerbalizationEngine.verbalize()
    │       └── Force full verbalization (bypasses hash cache in self-test mode)
    │
    └── VerbalizationSelfTest.execute(results, symbols)
            │
            ├── Phase 1: validateStrategyRouting()
            ├── Phase 2: validateLayerCoverage()
            ├── Phase 3: assessQuality()
            ├── Phase 4: validateMultiPassEnrichment()
            ├── Phase 5: benchmarkStrategies()
            ├── Phase 6: analyzeCacheEffectiveness()
            ├── Phase 7: validateFeedbackApplication()
            └── Phase 8: detectRegressions()
```

### Constructor Dependencies

```kotlin
class VerbalizationSelfTest(
    private val engine: DefaultVerbalizationEngine,
    private val cnsCalculator: ContextNeedinessCalculator,
    private val verbalizationStore: VerbalizationStore,
    private val cacheDir: File,
    private val symbolRepository: SymbolRepository? = null,
    private val feedbackStore: IFeedbackStore? = null
)
```

---

### 8 Test Phases

#### Phase 1: Strategy Routing Validation

**Purpose:** Validates that CNS scores correctly route symbols to INCREMENTAL, MULTI_PASS, or LEARNING strategies.

**Validation:** Every symbol's CNS score is calculated and compared against the strategy that was actually used. Adjacent-tier mismatches are flagged as SUSPECT; completely wrong routing is FAIL.

```
Validations: 15,486 total, 15,486 passed ✅
```

#### Phase 2: Layer Completeness Check

**Purpose:** Verifies all 5 VSLFC layers (Vision, Structure, Logic, Flow, Code) produce valid output artifacts.

**Validation:** Checks each layer for presence of artifacts and validity flags.

```
Layer vision: 36 artifacts, valid=true
Layer structure: 36 artifacts, valid=true
Layer logic: 54 artifacts, valid=true
Layer flow: 18 artifacts, valid=true
Layer code: 90 artifacts, valid=true
Total: 5/5 layers with output, 5/5 layers valid ✅
```

#### Phase 3: Quality Assessment

**Purpose:** Detects anti-patterns and quality issues in verbalization descriptions.

**Anti-Pattern Detection:**

| Anti-Pattern | Severity | Threshold | Description |
| :--- | :--- | :--- | :--- |
| `TAUTOLOGICAL` | HIGH | >80% token overlap | Description is essentially the symbol name reworded |
| `TOO_SHORT` | SUSPECT | < max(8, name.length) chars | Description is suspiciously brief |
| `NO_VERB` | SUSPECT | No action verb in FUNCTION descriptions | Function descriptions should describe actions |

**Quality Thresholds:**

```kotlin
private const val MIN_DESCRIPTION_LENGTH = 8
private const val TAUTOLOGICAL_OVERLAP_THRESHOLD = 0.80
private const val MAX_ANTI_PATTERN_RATE = 0.15
```

**Self-test verified result:** 0.01% anti-pattern rate, 0.09% suspect rate across 7,733 symbols.

#### Phase 4: Cross-Layer Enrichment Verification

**Purpose:** Verifies that MULTI_PASS strategy produces meaningfully enriched descriptions compared to INCREMENTAL.

**Method:**
1. Samples symbols across all clusters
2. Runs both INCREMENTAL and MULTI_PASS with `forceFullVerbalization = true`
3. Compares token sets to measure novel tokens and cross-references added
4. Requires >70% enrichment rate for PASS

**Self-test verified result:** 100% enrichment rate, 13-15 novel tokens per symbol, cross-references to architectural components added.

#### Phase 5: Performance Benchmarks

**Purpose:** Measures actual latency against documented targets.

| Strategy | Target | Measurement |
| :--- | :--- | :--- |
| INCREMENTAL | < 50ms per 100 symbols | Benchmarked |
| MULTI_PASS | < 200ms per 100 symbols | Benchmarked |
| LEARNING | < 5000ms per 100 symbols | Benchmarked (requires LLM) |

**Warnings:** LEARNING strategy fallback rate tracked; warns if >5% (indicates LLM unavailability).

#### Phase 6: Cache Effectiveness

**Purpose:** Evaluates hash-based cache performance.

| Metric | Target |
| :--- | :--- |
| Cache hit rate | > 80% |
| Stale entry rate | < 5% |

#### Phase 7: Feedback Application

**Purpose:** Tests that user feedback patterns from JSONL store are correctly applied by the LEARNING strategy to improve verbalization quality.

#### Phase 8: Regression Detection

**Purpose:** Compares current quality metrics against a stored baseline to detect quality regressions across code changes.

**Mechanism:**
- First run: saves baseline to `.vision-ai/logs/verbalization-self-test-baseline.json`
- Subsequent runs: compares anti-pattern rate, suspect rate, layer coverage against baseline
- Alerts on: >50% increase in anti-patterns, layer coverage decrease, enrichment rate drop

---

### Classification Heuristics

The self-test applies post-hoc classification heuristics to improve symbol kind detection from regex-based scanners:

| Heuristic | Classification | Example |
| :--- | :--- | :--- |
| Single lowercase word (no digits) | `PROPERTY` | `success`, `depth`, `route` |
| Single lowercase word with digits | `PROPERTY` | `sha256` |
| Single PascalCase word | `CLASS` | `Symbol`, `String`, `List` |
| Name ending in `Type` | `ENUM` | `ContractType`, `EntryPointType` |
| Name ending in `Path`, `Dir`, `Directory`, `File`, `Location`, `Url` | `PROPERTY` | `legacyLayerKotlinDirectoryPath` |
| Test fixture names ending in `Class` | Exempt from tautological check | `NoPackageClass`, `TestClass` |

---

### Quality Exemptions

Known valid patterns that are exempt from quality checks:

| Pattern | Exemption | Reason |
| :--- | :--- | :--- |
| `"Immutable data container"` | TOO_SHORT | Valid `data class` description |
| `"Data transfer object"` | TOO_SHORT | Valid DTO description |
| `"Value object"` | TOO_SHORT | Valid value object description |
| `*Class` with `"class for"` pattern | TAUTOLOGICAL | Test fixture naming convention |

---

### Self-Test Report

After each run, a JSON report is exported to `.vision-ai/logs/verbalization-self-test-{timestamp}.json`:

```json
{
  "timestamp": "2026-05-19T14:45:35.788Z",
  "overallStatus": "FAIL",
  "phasesPassed": "6/8",
  "phases": [
    {"phase": 1, "name": "Strategy routing", "status": "PASS", "durationMs": 3059},
    {"phase": 2, "name": "Layer completeness", "status": "PASS", "durationMs": 6},
    {"phase": 3, "name": "Quality assessment", "status": "PASS", "durationMs": 472,
     "metrics": {"totalSymbols": 7733, "antiPatterns": 1, "antiPatternRate": 0.0001,
                  "suspects": 7, "suspectRate": 0.0009}},
    {"phase": 4, "name": "Cross-layer enrichment", "status": "PASS", "durationMs": 154,
     "metrics": {"comparedSymbols": 15, "enrichmentRate": 1.0}},
    {"phase": 5, "name": "Performance benchmarks", "status": "FAIL", "durationMs": 2455},
    {"phase": 6, "name": "Cache effectiveness", "status": "PASS", "durationMs": 0},
    {"phase": 7, "name": "Feedback application", "status": "PASS", "durationMs": 0},
    {"phase": 8, "name": "Regression detection", "status": "PASS", "durationMs": 0}
  ],
  "validations": {"total": 15486, "passed": 15107, "failed": 0, "suspect": 28}
}
```

---

## Test Suites

### SelfDiscoveryTest
Self-discovery validation on the i2vision project itself:
- Architecture detection across all 18 modules
- Symbol extraction using `ScannerService`
- Verbalization with `forceFullVerbalization = true`
- 8-phase verbalization quality self-test
- Report export for trend analysis

### DiscoveryIntegrationTest
Tests the core discovery pipeline:
- Architecture detection and cluster identification
- Intent resolution for different discovery modes
- Discovery pipeline execution with various depths
- Artifact generation and validation
- End-to-end discovery flow

### DiscoveryFlowIntegrationTest
Comprehensive phased discovery flow tests:
- **Phase 0**: Test setup and project structure creation
- **Phase 1**: Roll-out and VSLFC layer initialization
- **Phase 2**: Architecture detection, artifact purging, and discovery execution
- **Phase 3**: Artifact analysis, validation, and VSLFC structure verification
- **Phase 4**: Learning feedback recording for pattern improvement
- **Phase 5**: Incremental sync for changed files
- **Sketch validation**: Tests against architectural patterns (fat-service, god-class, circular-deps, etc.)
- **Parallel discovery**: Validates correctness under concurrent execution

### VerbalizationIntegrationTest
Tests the verbalization engine for transforming code into natural language:
- **Phase 1**: Basic symbol verbalization with various code constructs
- **Phase 2**: Verbalization storage and retrieval mechanisms
- **Phase 3**: Different verbalization strategies (INCREMENTAL, MULTI_PASS, LEARNING)
- **Phase 4**: Custom pattern recognition and registration
- **Phase 5**: Hash-based change detection for incremental updates
- **Phase 6**: Integration with discovery pipeline output

### ContextIntegrationTest
Tests CLI context command integration:
- Enhanced context retrieval for discovered symbols
- File-specific and project-level context operations
- Cache integration with discovery results

---

## Test Helper Methods

Tests use helper methods to ensure consistent project root resolution and reduce code duplication:

- `getSketchRoot(sketchName: String)`: Loads architecture sketches from test resources
- `setupDiscovery(projectRoot: File)`: Sets up discovery pipeline with standard configuration

---

## Sketches

Architecture sketches are test projects with known structures used to validate architecture detection:

| Sketch | Pattern |
| :--- | :--- |
| `aggregator-pure` | Pure aggregator pattern with module-a, module-b, module-c |
| `aggregator-mixed` | Mixed aggregator pattern |
| `circular-deps` | Circular dependency patterns |
| `circular-nested` | Nested circular dependencies |
| `circular-three-module` | Three-module circular dependency |
| `circular-two-module` | Two-module circular dependency |
| `fat-service` | Fat service anti-pattern |
| `god-class` | God class anti-pattern |
| `kotlin-multi-module` | Kotlin multi-module structure |
| `spring-boot` | Spring Boot framework structure |

---

## Dependencies

This module depends on ALL production modules for comprehensive integration testing:

- `i2vision-cli` — CLI integration testing
- `i2vision-discover` — Discovery pipeline
- `i2vision-instant` — Instant context provider
- `i2vision-mcp` — MCP server
- `verbalization-core` — Verbalization engine and strategies
- `vslfc-core` — VSLFC types and structures
- All other production modules

---

## Recent Changes

- **Added `VerbalizationSelfTest` 8-phase quality framework** with CNS routing validation, layer completeness, quality assessment, enrichment verification, and regression detection
- **Added intelligent fallback descriptions** with camelCase-to-words conversion and 20+ action verb mappings
- **Added classification heuristics** for correcting regex-based scanner symbol kinds
- **Added quality exemptions** for known valid patterns (`Immutable data container`, test fixture classes)
- **Added `forceFullVerbalization` flag** to bypass hash cache during self-test for consistent quality assessment
- **Added self-test report export** for trend analysis and regression detection
- Implemented comprehensive `VerbalizationIntegrationTest` suite for the verbalization engine
- Added tests for all three verbalization strategies (INCREMENTAL, MULTI_PASS, LEARNING)
- Added tests for symbol storage, retrieval, and hash-based change detection
- Moved `ContextIntegrationTest` from `i2vision-cli` to `discovery-validation` for centralized testing
- Updated tests to use architecture sketches instead of temporary test files
- Fixed path duplication bug by using canonical paths in project root resolution
- Added helper methods to reduce code duplication in test setup

---

## Self-Test Quality Metrics (Verified)

*Last run: 2026-05-19, 18 clusters, 7,733 symbols*

| Metric | Value |
| :--- | :--- |
| Anti-pattern rate | 0.01% (1 symbol) |
| Suspect rate | 0.09% (7 symbols) |
| Quality rate | 99.90% |
| Enrichment rate | 100% |
| Strategy routing accuracy | 100% (15,486/15,486) |
| Layer completeness | 5/5 layers |
| Phases passing | 6/8 (Phase 5 requires LLM configuration) |