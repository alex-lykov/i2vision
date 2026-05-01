# Mandatory Test Suite

This document outlines the mandatory test suite for the current release.

---

## 📋 MANDATORY TEST LIST

| #  | Test Name                                     | Domain       | Type        | Reason                                                                     |
|----|-----------------------------------------------|--------------|-------------|----------------------------------------------------------------------------|
| 1  | Self-Discovery Full Project                   | Discovery    | Integration | Validates end-to-end pipeline on real codebase (38 clusters, 100% success) |
| 2  | Parallel Cluster Processing                   | Discovery    | Integration | Ensures concurrent cluster analysis works without race conditions          |
| 3  | Architecture Detection - Gradle Multi-Module  | Architecture | Unit        | Validates correct detection of MODULAR_MONOLITH pattern                    |
| 4  | Architecture Detection - Cluster Count        | Architecture | Unit        | Ensures all 38 modules are detected as separate clusters                   |
| 5  | Flow Discovery - Sequences Generated          | Flow         | Unit        | Validates flow extraction from call graphs (1,585 flows expected)          |
| 6  | Logic Extraction - Business Rules             | Logic        | Unit        | Ensures pattern-based rule extraction works (require, check, validate)     |
| 7  | Structure Building - Components Mapped        | Structure    | Unit        | Validates package-to-component mapping (182 components expected)           |
| 8  | Contract Validation - Layer Consistency       | Contracts    | Integration | Ensures Vision↔Structure↔Logic↔Flow↔Code contracts are validated           |
| 9  | Verbalization - Symbol to Natural Language    | Verbalization| Integration | Validates code symbols are converted to natural language descriptions      |
| 10 | Verbalization - Storage and Retrieval         | Verbalization| Integration | Ensures verbalization results are correctly stored and retrieved           |
| 11 | Verbalization - Multi-Strategy Support        | Verbalization| Unit        | Validates INCREMENTAL, MULTI_PASS, and LEARNING strategies work            |
| 12 | Incremental Sync - File Hash Change Detection | Storage      | Unit        | Validates changed files trigger targeted rediscovery                       |
| 13 | Batch Link Flush - Single Disk Write          | Storage      | Integration | Ensures links are buffered and written once (no concurrent corruption)     |
| 14 | CLI - Intent Parsing                          | CLI          | Unit        | Validates `--intent=full_discovery` resolves correctly                     |
| 15 | CLI - Depth Parameter                         | CLI          | Unit        | Ensures BROWSE/STANDARD/DEEP modes apply correct parameters                |
| 16 | MCP Server - Stdio Transport                  | MCP          | Integration | Validates MCP server starts and accepts JSON-RPC requests                  |
| 17 | MCP Server - Tool Listing                     | MCP          | Unit        | Ensures all registered tools are discoverable                              |
| 18 | Instant Context - File Context Retrieval      | Context      | Integration | Validates context API returns symbols, flows, related files                |
| 19 | Quality Metrics - Cohesion Calculation        | Metrics      | Unit        | Ensures internal/external dependency ratio is correct                      |
| 20 | Quality Metrics - Complexity Scoring          | Metrics      | Unit        | Validates cyclomatic/cognitive complexity calculation                      |
| 21 | Semantic Cache - OS User Directory            | Storage      | Integration | Ensures cache is written to `~/.i2vision/` not project root                |
| 22 | License Headers - All Modules                 | Build        | Unit        | Validates MIT license header in all source files                           |
| 23 | Gradle Build - All Modules                    | Build        | Integration | Ensures `./gradlew build` succeeds on clean checkout                       |

---

## 📊 TEST SUMMARY

| Type            | Count | Purpose                        |
|-----------------|-------|--------------------------------|
| **Unit**        | 11    | Validate individual components |
| **Integration** | 9     | Validate system works together |
| **Total**       | 20    | Minimum viable test suite      |

---

## 🚀 QUICK TEST COMMANDS

```bash
# Run all tests
./gradlew test

# Run specific test suites
./gradlew :i2vision-discover:test --tests "*SelfDiscovery*"
./gradlew :i2vision-architecture:test
./gradlew :i2vision-mcp:test
./gradlew :i2vision-cli:test

# Run integration test
./gradlew :i2vision-cli:run --args="discover --intent=full_discovery"
```

---

## 📋 TEST DOMAIN BREAKDOWN

### Discovery (2 tests)

- Self-Discovery Full Project (Integration)
- Parallel Cluster Processing (Integration)

### Architecture (2 tests)

- Architecture Detection - Gradle Multi-Module (Unit)
- Architecture Detection - Cluster Count (Unit)

### Flow (1 test)

- Flow Discovery - Sequences Generated (Unit)

### Logic (1 test)

- Logic Extraction - Business Rules (Unit)

### Structure (1 test)

- Structure Building - Components Mapped (Unit)

### Contracts (1 test)

- Contract Validation - Layer Consistency (Integration)

### Storage (3 tests)

- Incremental Sync - File Hash Change Detection (Unit)
- Batch Link Flush - Single Disk Write (Integration)
- Semantic Cache - OS User Directory (Integration)

### CLI (2 tests)

- CLI - Intent Parsing (Unit)
- CLI - Depth Parameter (Unit)

### MCP (2 tests)

- MCP Server - Stdio Transport (Integration)
- MCP Server - Tool Listing (Unit)

### Context (1 test)

- Instant Context - File Context Retrieval (Integration)

### Metrics (2 tests)

- Quality Metrics - Cohesion Calculation (Unit)
- Quality Metrics - Complexity Scoring (Unit)

### Build (2 tests)

- License Headers - All Modules (Unit)
- Gradle Build - All Modules (Integration)

---

## ✅ RELEASE CHECKLIST

Before releasing, ensure all 20 mandatory tests pass:

- [ ] All unit tests pass (11 tests)
- [ ] All integration tests pass (9 tests)
- [ ] Gradle build succeeds on clean checkout
- [ ] Self-discovery completes with 100% success rate
- [ ] MCP server starts and responds to requests
- [ ] License headers are present in all source files

---

**Last Updated:** April 2026
