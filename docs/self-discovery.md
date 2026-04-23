# i2vision Self-Discovery Results

i2vision successfully analyzes its own codebase—proving the discovery engine works on real-world, multi-module Kotlin projects.

## Latest Run (2026-04-23)

| Metric | Value |
|--------|-------|
| Clusters Detected | 18 |
| Success Rate | 100% (18/18) |
| Total Duration | 68 seconds |
| Deployment Pattern | MODULAR_MONOLITH |
| Build System | GRADLE_KTS |
| Total Artifacts | 180 |

## Cluster Breakdown

| Cluster | Files | Status |
|---------|-------|--------|
| discovery-validation | 319 | ✅ |
| storage-core | 281 | ✅ |
| i2vision-instant | 274 | ✅ |
| i2vision-mcp | 261 | ✅ |
| i2vision-cli | 260 | ✅ |
| vslfc-core | 245 | ✅ |
| i2vision-discover | 236 | ✅ |
| discovery-api | 215 | ✅ |
| i2vision-architecture | 207 | ✅ |
| intent-parser | 202 | ✅ |
| llm-client | * | ✅ |
| contracts | * | ✅ |
| architecture-types | * | ✅ |
| index-provider | * | ✅ |
| discovery-engine | * | ✅ |
| link-service | * | ✅ |
| conf-agent-core | * | ✅ |
| build-tests | * | ✅ |

## What This Validates

- ✅ **Cluster detection** correctly identifies all Gradle modules
- ✅ **Architecture detection** recognizes MODULAR_MONOLITH pattern
- ✅ **Parallel processing** handles 18 clusters concurrently
- ✅ **Artifact generation** produces VSLFC layers for each cluster
- ✅ **Zero failures** across all modules

## Run It Yourself

```bash
./gradlew :discovery-validation:run
```

Logs are written to `.vision-ai/logs/discovery-log-*.txt`

## Enhanced Context Validation

The self-discovery test also validates the enhanced context feature:

```bash
# Run with context validation
./gradlew :discovery-validation:run --args="--test-context"
```

This tests:
- Basic context (symbols extraction)
- Cache detection (which clusters have enhanced context)
- Enhanced context (flows, rules, components)
- Cache statistics

## CLI Context Command Validation

```bash
# Run with CLI command validation
./gradlew :discovery-validation:run --args="--test-cli"
```

This validates:
- `context file` command
- `context enhanced` command
- `context cache stats` command
