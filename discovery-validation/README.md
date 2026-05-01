# Discovery Validation Module

**Internal test harness - not published to Maven**

This module contains integration tests and architecture sketches for validating the i2vision discovery system across all
modules.

## Purpose

- Test cross-module integration (CLI → Discover → MCP → Instant)
- Validate architecture detection on known project structures
- Run long-running validation suites independently
- Self-discovery validation on i2vision itself
- CLI context command integration testing

## Structure

```
discovery-validation/
├── src/test/
│   ├── kotlin/com/i2vision/
│   │   ├── validation/
│   │   │   ├── SelfDiscoveryTest.kt
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

## Running Tests

```bash
./gradlew :discovery-validation:test
```

To run specific test classes:

```bash
# Run only verbalization tests
./gradlew :discovery-validation:test --tests VerbalizationIntegrationTest

# Run only discovery flow tests
./gradlew :discovery-validation:test --tests DiscoveryFlowIntegrationTest

# Run only integration tests
./gradlew :discovery-validation:test --tests DiscoveryIntegrationTest
```

## Verbalization Testing

The `VerbalizationIntegrationTest` suite validates the new verbalization engine that transforms code symbols into natural language descriptions. Tests cover:

- **Symbol Verbalization**: Converts code symbols (classes, functions, interfaces, etc.) into descriptive text
- **Storage & Retrieval**: Verifies verbalization results are correctly stored in the semantic cache
- **Strategies**: Tests INCREMENTAL (hash-based), MULTI_PASS (context-aware), and LEARNING (LLM-based) strategies
- **Pattern Recognition**: Validates custom pattern registration and matching
- **Change Detection**: Tests hash-based detection of modified symbols for incremental updates
- **Discovery Integration**: Validates verbalization works with discovered architecture symbols

### Running Verbalization Tests Only

```bash
./gradlew :discovery-validation:test --tests VerbalizationIntegrationTest
```

## Test Helper Methods

Tests use helper methods to ensure consistent project root resolution and reduce code duplication:

- `getSketchRoot(sketchName: String)`: Loads architecture sketches from test resources
- `setupDiscovery(projectRoot: File)`: Sets up discovery pipeline with standard configuration

## Sketches

Architecture sketches are test projects with known structures used to validate architecture detection:

- **aggregator-pure**: Pure aggregator pattern with module-a, module-b, module-c
- **aggregator-mixed**: Mixed aggregator pattern
- **circular-deps**: Circular dependency patterns
- **circular-nested**: Nested circular dependencies
- **circular-three-module**: Three-module circular dependency
- **circular-two-module**: Two-module circular dependency
- **fat-service**: Fat service anti-pattern
- **god-class**: God class anti-pattern
- **kotlin-multi-module**: Kotlin multi-module structure
- **spring-boot**: Spring Boot framework structure

## Test Suites

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
- **Sketch validation**: Tests against architectural patterns
- **Parallel discovery**: Validates correctness under concurrent execution

### VerbalizationIntegrationTest (NEW)
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

## Recent Changes

- Added comprehensive `VerbalizationIntegrationTest` suite for the new verbalization engine
- Implemented tests for verbalization strategies: INCREMENTAL, MULTI_PASS, LEARNING
- Added tests for symbol storage, retrieval, and hash-based change detection
- Enhanced discovery validation to track verbalization integration
- Moved ContextIntegrationTest from i2vision-cli to discovery-validation for centralized testing
- Updated tests to use architecture sketches instead of temporary test files
- Fixed path duplication bug by using canonical paths in project root resolution
- Added helper methods to reduce code duplication in test setup
- Tests now run against realistic project structures instead of simple temp files

## Dependencies

This module depends on ALL production modules for comprehensive integration testing, including:
- i2vision-cli (for CLI integration testing)
- i2vision-discover
- i2vision-instant
- i2vision-mcp
- verbalization-core (for verbalization engine testing)
- vslfc-core (for VSLFC types and structures)
- All other production modules
