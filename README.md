# i²-Vision (i2Vision)

**i² = Insight × Intelligence**
**vision = high-quality semantic context for both human developers and LLMs**

![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-blueviolet) 
![Platform](https://img.shields.io/badge/Platform-JVM%20%7C%20Multi--Module-brightgreen) 
![License](https://img.shields.io/badge/License-MIT-green) 
![MCP](https://img.shields.io/badge/MCP-2024--11--05-blue)

---

> ⚠️ **Development Status:** i²-Vision is in active development. 
> The discovery engine is production-tested on 18 clusters. 
> Development engine and LLM enhancements are on the roadmap.
> The verbalization engine achieves 99.9% quality rate across 7,700+ symbols. LLM-enhanced features are on the roadmap. 
> Use for learning, testing, and evaluation—production use at your own discretion.

---

## 🎯 What is i²-Vision?

i²-Vision bridges the gap between your codebase and LLMs—while keeping humans in the loop.

You're using Claude, Cursor, or Continue.dev to understand and modify code. But LLMs lack deep project context—they see files in isolation, missing architectural patterns, cross-module dependencies, and business rules scattered across your codebase.

**i²-Vision solves this by:**

- **🔍 Discovery:** Analyzes your entire codebase through VSLFC layers (Vision → Structure → Logic → Flow → Code), extracting architecture, flows, business rules, and components.

- **📋 Contracts:** Validates that layers remain consistent—does the code actually implement what the requirements promise?

- **💬 Verbalization:** Transforms code symbols into meaningful architectural descriptions using intelligent strategies (INCREMENTAL, MULTI_PASS, LEARNING) automatically selected based on **Cluster Context Neediness Score (CNS)**. Achieves 99.9% quality rate with intelligent camelCase fallback and Kotlin-aware terminology.

- **⚡ Instant Context:** Provides LLMs with immediate, task-aware context through MCP. When you ask "How does authentication work?", the LLM gets the complete picture: related components, call sequences, business rules, and architectural constraints.

The same semantic context serves both you and your AI assistants.

---

## 🎯 Core Concepts

### VSLFC: Five-Layer Contract System

```
Vision ←→ Structure ←→ Logic ←→ Flow ←→ Code
   ↓         ↓          ↓       ↓       ↓
Implements  Defines  Exercises  Calls  DependsOn
```

| Layer | What It Captures | Example |
| :--- | :--- | :--- |
| **Vision** | Requirements, goals, constraints | "System must support OAuth2 with JWT rotation" |
| **Structure** | Components, modules, dependencies | `AuthService`, `OAuth2Client`, `TokenValidator` |
| **Logic** | Business rules, invariants | `require(token.isNotBlank())`, `check(token.expiry > now)` |
| **Flow** | Sequences, API calls, interactions | `authenticate() → validate() → issueToken()` |
| **Code** | Implementation details | `AuthService.kt:45`, `suspend fun authenticate(...)` |

### Instant Context for LLMs

When an LLM requests context for a file, i2vision provides:

```
File: AuthService.kt
├── Vision: "REQ-AUTH-001: OAuth2 authentication" ✅ Implemented
├── Structure: Component 'AuthService' (cohesion: 0.85)
├── Logic: 3 business rules (token validation, expiry check)
├── Flow: Called by LoginFlow, calls TokenValidator → TokenService → Redis
├── Related: OAuth2Client.kt, SecurityConfig.kt, TokenValidator.kt
└── Quality: Complexity 12 (medium), Test coverage: 65%
```

The LLM now understands not just the code, but its role in the architecture.

### Bidirectional Discovery

```
Bottom-Up (Existing Code):   Code → Flow → Logic → Structure → Vision
Top-Down (Greenfield):       Vision → Structure → Logic → Flow → Code
```

Contracts are the invariant. Whether discovering existing code or scaffolding new features, contracts validate that layers remain consistent.

---

## 💬 Verbalization

Transforms code symbols into architectural descriptions with context-aware strategy selection. **Self-test verified: 99.9% quality rate across 7,733 symbols.**

| Strategy | CNS Range | Use Case | Latency (100 symbols) |
| :--- | :--- | :--- | :--- |
| **INCREMENTAL** | 0–30 | Hash-based, low need | < 50ms |
| **MULTI_PASS** | 31–60 | Context refinement | < 200ms |
| **LEARNING** | 61–100 | LLM + feedback loop | < 5s |

**CNS Formula:** `SymbolAmbiguity(35) + StructuralComplexity(25) + ArchitecturalSensitivity(20) + FeedbackDiscrepancy(20)`

All 5 VSLFC layers have dedicated verbalizers with Kotlin-aware terminology (`suspend`, `data class`, `sealed class`, `inline class`, etc.) and Spring framework support (`@RestController`, `@Service`, `@Repository`, `@Component`).

**Intelligent fallback:** When no pattern matches, camelCase-to-words conversion with 20+ action verb mappings produces meaningful descriptions (e.g., `extractModulePath` → `"Extracts module path"`, `isValidUser` → `"Checks valid user"`).

→ [Full Verbalization Documentation](verbalization-core/README.md)

---

## 🎯 Why i2vision?

| Without i2vision | With i2vision |
| :--- | :--- |
| LLM sees isolated files | LLM understands architectural context |
| Manual code exploration | Automated discovery of flows & rules |
| Docs drift from code | Contracts validate consistency |
| "Where is this used?" | Instant cross-reference |
| "What does this module do?" | Component mapping with cohesion metrics |
| Generic symbol descriptions | Context-aware architectural descriptions |

---

## 🏗️ Architecture

### Module Ecosystem (14 Public Modules)

```
┌─────────────────────────────────────────────────────────────────┐
│                    MCP CLIENTS                                  │
│  Claude Desktop │ Cursor │ Continue │ Zed │ Custom              │
└─────────────────────────────────────────────────────────────────┘
                              ↓ MCP Protocol
┌─────────────────────────────────────────────────────────────────┐
│                    i2vision-mcp (MIT)                           │
│  • JSON-RPC 2.0 Server  • Stdio/HTTP Transport                  │
│  • Discovery Tools     • Context Tools      • Contract Tools    │
│  • Quality Metrics     • Related Files                          │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    i2vision-instant (MIT)                       │
│  • Instant Context API  • Proactive Context                     │
│  • Task-Aware Context   • Strategy Suggestions                  │
│  • Cache Management     • <100ms response time                  │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    i2vision-discover (MIT)                      │
│  • Discovery Pipeline   • Flow Discovery                        │
│  • Logic Extraction     • Structure Building                    │
│  • Contract Validation  • Artifact Writing                      │
│  • Parallel Processing  • Incremental Sync                      │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    FOUNDATION MODULES (MIT)                     │
│  vslfc-core │ i2vision-architecture │ architecture-types        │
│  intent-parser │ storage-core │ conf-agent-core │ llm-client    │
│  verbalization-core │ discovery-api │ index-provider            │
└─────────────────────────────────────────────────────────────────┘
```

### Module List

| Module | Purpose | License |
| :--- | :--- | :--- |
| `vslfc-core` | VSLFC models and contract primitives | MIT |
| `i2vision-architecture` | Multi-dimensional architecture detection | MIT |
| `architecture-types` | Pattern definitions and signatures | MIT |
| `intent-parser` | Intent resolution and preset management | MIT |
| `storage-core` | Semantic cache with incremental detection | MIT |
| `conf-agent-core` | YAML-configurable LLM agent framework | MIT |
| `llm-client` | Unified LLM client (OpenAI/Anthropic/Ollama) | MIT |
| `verbalization-core` | Multi-layer verbalization with CNS strategies | MIT |
| `discovery-api` | Discovery interfaces | MIT |
| `index-provider` | Code indexing and symbol extraction | MIT |
| `i2vision-discover` | Discovery engine | MIT |
| `i2vision-cli` | CLI entry point | MIT |
| `i2vision-instant` | Instant context provider | MIT |
| `i2vision-mcp` | MCP server and tools | MIT |

---

## 🚀 Quick Start: Discover + Instant Context

### ⚡ Try It on i2vision Itself (2 minutes)

Don't have a project handy? Run self-discovery—i2vision analyzing i2vision:

```bash
# Clone and build
git clone https://github.com/alex-lykov/i2vision && cd i2vision
./gradlew build

# Run self-discovery (analyzes i2vision with i2vision)
./gradlew :discovery-validation:run -Pargs="--self-test --report-verbalization"
```

**What happens:** i2vision analyzes its own 18 modules—detecting architecture patterns, flows, business rules, and verbalizing 7,700+ symbols with a 99.9% quality rate. An 8-phase self-test validates strategy routing, layer completeness, description quality, cross-layer enrichment, performance benchmarks, cache effectiveness, and regression detection.

**Example output:**

```
=== i2vision Self-Discovery Test ===
Project: D:\proj\AI\i2-vision
Deployment Pattern: MODULAR_MONOLITH
Clusters detected: 18
  - verbalization-core (596 files)
  - i2vision-discover (291 files)
  - i2vision-instant (273 files)
  ...

--- Discovery Results Summary ---
Total Duration: 197s
Total Clusters: 18/18 (100%)
Total Artifacts: 234

--- Verbalization Analysis ---
Extracted 7733 symbols for verbalization
✅ 7733 verbalizations generated

--- Verbalization Self-Test ---
Phase 1: Strategy routing validation... PASS
Phase 2: Layer completeness check... PASS (5/5 layers)
Phase 3: Quality assessment... PASS (0.01% anti-pattern rate)
Phase 4: Cross-layer enrichment verification... PASS (100% enrichment)
Phase 6: Cache effectiveness... PASS
Phase 8: Regression detection... PASS

Quality Summary: 7733 symbols, 1 anti-patterns (0.01%), 7 suspects (0.09%)
Phases Passed: 6/8
```

→ [View full self-discovery log](.vision-ai/logs/)

### Then Try It on Your Project

```bash
# Analyze your own project with intent-driven discovery
./gradlew :i2vision-cli:run --args="discover --intent=full_discovery /path/to/your/project"
```

**Available intents:**

| Intent | Purpose |
| :--- | :--- |
| `full_discovery` | Complete analysis of all VSLFC layers |
| `refactoring_analysis` | Focus on refactoring opportunities |
| `quick_overview` | Fast scan for exploration |
| `architecture_audit` | Audit architecture and dependencies |
| `flow_mapping` | Map flows and interactions |
| `documentation_generation` | Generate documentation |

### Get Instant Context

Use the CLI context command to get instant context for files and directories:

```bash
# Get context for a specific file (basic context - symbols + complexity)
./gradlew :i2vision-cli:run --args="context file --path=i2vision-instant/src/main/kotlin/com/i2vision/instant/context/ContextProvider.kt --task=discovery --project=."

# Get context with task-specific analysis
./gradlew :i2vision-cli:run --args="context file --path=<file-path> --task=refactor --project=<project-root>"

# Get context for multiple files
./gradlew :i2vision-cli:run --args="context files <file1> <file2> <file3> --project=<project-root>"

# Get enhanced context (requires discovery cache - flows, rules, components)
./gradlew :i2vision-cli:run --args="context enhanced --path=i2vision-instant/src/main/kotlin/com/i2vision/instant/context/ContextProvider.kt --project=."

# Enhanced context for a different file
./gradlew :i2vision-cli:run --args="context enhanced --path=<file-path> --project=<project-root>"

# Cache management
./gradlew :i2vision-cli:run --args="context cache stats --project=."
./gradlew :i2vision-cli:run --args="context cache clean --project=."
./gradlew :i2vision-cli:run --args="context cache invalidate --pattern=*.kt --project=."
```

**Real example** - ContextProvider.kt (heart of instant context feature):

```
=== Enhanced Context ===
File: i2vision-instant/src/main/kotlin/com/i2vision/instant/context/ContextProvider.kt
[ENHANCED] Discovery cache active

Symbols: 153
  - class ContextProvider (line 31)
  - val projectRoot (line 32)
  - val cacheStore (line 34)
  - val indexProvider (line 38)
  - val semanticCacheRoot (line 41)
  - val artifactLoader (line 42)
  - val fileAnalyzer (line 43)
  - val cacheManager (line 44)
  - fun getContext (line 53)
  - val absolutePath (line 56)
  - val indexSymbols (line 62)
  - val symbols (line 63)
  - val relatedFiles (line 66)
  - val module (line 69)
  - val artifacts (line 70)
  - val taskContext (line 77)
  ... and 133 more

Complexity Score: 5
Cyclomatic Complexity: 5
Cognitive Complexity: 6
Maintainability Index: 16

Strategy suggestions:
  - Find missing context: High
  - Identify structure: Medium
  - Generate documentation: Low context confidence
  - Kotlin code analysis: medium

Cache Statistics:
  Total entries: 1,775
  Valid entries: 1,775
  Expired entries: 0
```

**What you get:** Instant context including symbols, related files, task-specific suggestions, artifacts, complexity
metrics, and strategy suggestions.

**Context types:**

- **Basic context** (`context file` / `context files`): Works immediately - symbols, complexity, suggestions
- **Enhanced context** (`context enhanced`): Requires discovery - adds flows, business rules, components

**Available tasks:** `debug`, `refactor`, `add feature`, `fix bug`, `optimize`, `discovery` (default)

**Cache subcommands:**
- `stats` - Show cache statistics (total entries, expired entries, valid entries)
- `clean` - Remove expired cache entries
- `invalidate --pattern=<glob>` - Invalidate cache entries matching pattern

---

### Test with MCP (Beta)

The MCP integration is currently in beta. Build the MCP server first:

```bash
./gradlew :i2vision-mcp:shadowJar
```

Configure Claude Desktop:

```json
{
  "mcpServers": {
    "i2vision": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/i2vision/i2vision-mcp/build/libs/i2vision-mcp-1.0.0-all.jar",
        "--stdio"
      ]
    }
  }
}
```

Then ask Claude: *"Show me the architecture of UserService"* — it gets full context instantly.

---

## 📊 What You Get

| After Discovery | With Instant Context |
| :--- | :--- |
| Full project analysis (18 clusters, 1,500+ flows validated) | File-specific context in <100ms |
| Architecture patterns detected per module | Related files and dependencies |
| Cohesion, coupling, complexity metrics | Business rules and call sequences |
| Contract validation between layers | Ready for LLM prompt injection |
| 7,700+ verbalized symbols at 99.9% quality | Architectural descriptions with cross-references |

→ [Full Documentation](#) | [CLI Reference](#) | [MCP Tools](#)

---

## 📋 TARGET PROJECT `.gitignore`

```gitignore
# i²-Vision - only the generated cache stays local
.semantic-cache/
```

| Action | Why |
| :--- | :--- |
| Track `.vision-ai/` | Design-time contracts, intents, presets—team shares these |
| Ignore `.semantic-cache/` | Generated artifacts per developer (and now in OS user home) |
| Run `i2vision discover` | Populates cache locally |
| Run `i2vision init` once | Creates `.vision-ai/` structure (commit this) |

**One line in `.gitignore`. That's it.**

---

## ✅ Proven with Self-Discovery

i2vision analyzes its own codebase during development:

| Metric | Value |
| :--- | :--- |
| Clusters Discovered | 18 |
| Success Rate | 100% |
| Symbols Verbalized | 7,733 |
| Verbalization Quality Rate | 99.9% |
| Anti-Pattern Rate | 0.01% |
| Enrichment Rate (MULTI_PASS) | 100% |
| Strategy Routing Accuracy | 100% (15,486/15,486) |
| Layers with Valid Output | 5/5 |
| Total Duration | ~3 minutes |

**Latest run:** 2026-05-20 | **Log:** `.vision-ai/logs/discovery-log-*.txt`

*This is a real-world validation that the discovery engine and verbalization system work on complex, multi-module Kotlin projects.*


---

## 🔧 Key Features

### ✅ Contract-Based Validation
- Bidirectional contracts between all VSLFC layers
- Automatic drift detection when code changes
- Gap analysis finds missing implementations
- Validation reports for violated contracts

### ✅ Architecture Detection
- Multi-dimensional signatures per module
- Cluster detection (build modules → directory fallback)
- Pattern recognition (Hexagonal, Layered, Agent, Pipeline)
- Confidence scoring for detected patterns

### ✅ Verbalization Engine
- Three strategies: INCREMENTAL, MULTI_PASS, LEARNING
- CNS-driven automatic strategy selection
- Intelligent camelCase fallback with 20+ verb mappings
- Kotlin-aware terminology (suspend, data class, sealed class)
- Spring framework support (@RestController, @Service, etc.)
- 99.9% quality rate verified by self-test

### ✅ Quality Metrics
- Cohesion & Coupling — Internal vs external dependencies
- Complexity Analysis — Cyclomatic, cognitive complexity
- Component Mapping — Package structure analysis
- Cross-Module Dependencies — Import-based coupling
- Verbalization Quality — Anti-pattern and suspect detection

### ✅ Self-Test Framework
- 8-phase automated quality validation
- CNS routing validation across all symbols
- Cross-layer enrichment verification
- Performance benchmark tracking
- Regression detection with baseline comparison

### ✅ Parallel Processing
- 18 clusters in ~3 minutes
- Shared immutable context across clusters
- Batch link flushing for concurrency safety
- Cluster-level locking for artifact writes

### ✅ Incremental Sync
- Two-tier hash tracking (local + context)
- Targeted rediscovery of changed files only
- Force full verbalization mode for testing
- Config snapshot for design-time changes

---

## 📊 Development Status

### ✅ What's Working (Production-Ready)
- VSLFC discovery pipeline (Code → Vision)
- Contract validation
- Architecture detection
- CLI tool
- Verbalization engine (99.9% quality rate)
- Self-test framework (6/8 phases automated)
- Self-tested on 18 clusters / 7,733 symbols

### 🧪 What's in Beta
- MCP integration (Claude Desktop / Cursor)
- Instant context API
- Incremental sync

### 📋 What's Planned (Not Yet Implemented)
- Development engine (Vision → Code scaffolding)
- LLM-enhanced discovery
- Contract remediation (auto-fix suggestions)
- Team cloud sync

### 🔴 Known Limitations
- **Vision Layer:** Currently extracts requirements from project-level documentation (README.md), producing the same requirements for all modules. Per-module documentation import is planned.
- **Code Layer:** Contains discovery summaries only. Full code artifact analysis is planned.
- **LEARNING Strategy:** Requires LLM configuration (API key). Falls back to MULTI_PASS gracefully when unavailable.

→ [Full Roadmap](#)

---

## 📚 Documentation

| Document | Description |
| :--- | :--- |
| [VSLFC Layers](#) | Five-layer contract system |
| [Contract System](#) | Bidirectional validation |
| [Architecture Detection](#) | Multi-dimensional signatures |
| [Project Structure](docs/concepts/project-structure.md) | VSLFC project layout                              |
| [Verbalization Engine](verbalization-core/README.md) | Multi-layer verbalization with CNS strategies |
| [Self-Test Framework](discovery-validation/README.md) | 8-phase quality validation |
| [MCP Integration](#) | Claude Desktop, Cursor setup |
| [Instant Context](#) | Task-aware context optimization |
| [CLI Reference](#) | Command-line interface |
| [API Reference](#) | HTTP endpoints |
| [Diagrams](docs/diagrams/) | Sequence diagrams and architecture visualizations |

[Full Documentation →](docs/README.md)

---

## 🤝 Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a PR.

---

## ⚖️ Disclaimer

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND. Specifically for i²-Vision:

- Analysis results may contain inaccuracies or false positives
- Contract validation is heuristic-based, not formally verified
- LLM-enhanced features (when available) may produce hallucinations
- Always review suggestions before applying them to production codebases
- The tool analyzes source code but does not execute it; security validation remains your responsibility

---

## 📄 License

i²-Vision is licensed under the **MIT License**.

✅ Use for any purpose (personal, commercial, internal) • Modify the source code • Distribute original or modified versions • Embed in proprietary products • Use analysis outputs in any product

⚠️ Keep the copyright notice and license text • Software provided "as is", without warranty

---

## 📦 Built With

| Technology                                                        | Purpose                           |
|-------------------------------------------------------------------|-----------------------------------|
| [Kotlin](https://kotlinlang.org/)                                 | Primary language                  |
| [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Async & parallel processing       |
| [Koog Agents](https://github.com/koog/koog-agents)                | AI agent framework                |
| [conf-agent-core](../conf-agent-core/)                            | YAML-configurable agent framework |
| [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml)            | YAML parsing                      |
| [MCP Protocol](https://modelcontextprotocol.io/)                  | LLM integration                   |

---

**i²vision turns code understanding from a one-time scan into a living, verifiable contract system that evolves with your codebase.**

⭐ **Star this repo if you find it useful!**
