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
│   │   │   └── DiscoveryFlowIntegrationTest.kt
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

## Recent Changes

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
- All other production modules
