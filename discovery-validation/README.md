# Discovery Validation Module

**Internal test harness - not published to Maven**

This module contains integration tests and architecture sketches for validating the i2vision discovery system across all modules.

## Purpose

- Test cross-module integration (CLI → Discover → MCP → Instant)
- Validate architecture detection on known project structures
- Run long-running validation suites independently
- Self-discovery validation on i2vision itself

## Structure

```
discovery-validation/
├── src/test/
│   ├── kotlin/com/i2vision/validation/
│   │   ├── SelfDiscoveryTest.kt
│   │   ├── ArchitectureValidationTest.kt
│   │   └── CrossModuleIntegrationTest.kt
│   └── resources/sketches/
│       ├── aggregator-pure/
│       ├── circular-deps/
│       ├── gradle-multi-module/
│       ├── hexagonal/
│       └── microservices/
└── build.gradle.kts
```

## Running Tests

```bash
./gradlew :discovery-validation:test
```

## Sketches

Architecture sketches are test projects with known structures used to validate architecture detection:

- **aggregator-pure**: Pure aggregator pattern with multiple modules
- **circular-deps**: Circular dependency patterns
- **gradle-multi-module**: Standard Gradle multi-module structure
- **hexagonal**: Hexagonal architecture pattern
- **microservices**: Microservices architecture pattern

## Dependencies

This module depends on ALL production modules for comprehensive integration testing.
