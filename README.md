# i²-Vision (i2Vision)

**i² = Insight × Intelligence**
**vision = high-quality semantic context for both human developers and LLMs**

![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-7F52FF?logo=kotlin)
![Platform](https://img.shields.io/badge/Platform-JVM%2021-ED8B00?logo=openjdk)
![License](https://img.shields.io/badge/License-MIT-blue)
![MCP](https://img.shields.io/badge/MCP-Protocol%20Ready-4B32C3)
![Build](https://img.shields.io/badge/Build-Passing-success)

> ⚠️ **Development Status:** i²-Vision is in **active development**.
> The discovery engine is production-tested on 38 clusters.
> Development engine and LLM enhancements are on the roadmap.
> **Use for learning, testing, and evaluation—production use at your own discretion.**

---

## 🎯 What is i²-Vision?

**i²-Vision bridges the gap between your codebase and LLMs—while keeping humans in the loop.**

You're using Claude, Cursor, or Continue.dev to understand and modify code. But LLMs lack **deep project context**—they see files in isolation, missing architectural patterns, cross-module dependencies, and business rules scattered across your codebase.

**i²-Vision solves this by:**

1. **🔍 Discovery:** Analyzes your entire codebase through VSLFC layers (Vision → Structure → Logic → Flow → Code), extracting architecture, flows, business rules, and components.

2. **📋 Contracts:** Validates that layers remain consistent—does the code actually implement what the requirements promise?

3. **⚡ Instant Context:** Provides LLMs with immediate, task-aware context through MCP. When you ask "How does authentication work?", the LLM gets the complete picture: related components, call sequences, business rules, and architectural constraints.

**The same semantic context serves both you and your AI assistants.**

---

## 📊 Development Status

i²-Vision is under **active development**.

### ✅ What's Working (Production-Ready)
- VSLFC discovery pipeline (Code → Vision)
- Contract validation
- Architecture detection
- CLI tool
- Self-tested on 38 clusters / 1,585 flows

### 🧪 What's in Beta
- MCP integration (Claude Desktop / Cursor)
- Instant context API
- Incremental sync

### 📋 What's Planned (Not Yet Implemented)
- Development engine (Vision → Code scaffolding)
- LLM-enhanced discovery
- Contract remediation (auto-fix suggestions)
- Team cloud sync

### ⚠️ Important Notes
- This repository contains the **complete source code** for transparency
- Some planned features exist as **design documents and tests only**
- The system successfully analyzes its own codebase (proof of concept)
- **Production use is at your own risk** during this development phase

[Full Roadmap →](docs/roadmap.md)

---

## 🚀 Quick Start

### MCP Integration (Claude Desktop / Cursor) - Recommended

```json
{
  "mcpServers": {
    "i2vision": {
      "command": "java",
      "args": ["-jar", "i2vision-mcp/build/libs/i2vision-mcp.jar", "--stdio"]
    }
  }
}
```

**What you get:** Instant context for any file, proactive related-file suggestions, quality metrics, and architectural insights—all directly in your LLM workflow.

**Available MCP Tools:**
- `discover_project` - Full VSLFC discovery
- `get_instant_context` - Context for any file/directory
- `get_quality_metrics` - Cohesion, coupling, complexity
- `validate_contract` - Validate layer contracts
- `suggest_refactoring` - Get architectural improvements

### CLI Usage

```bash
# Full project discovery
./gradlew :i2vision-cli:run --args="discover --intent=full_discovery"

# Module analysis
./gradlew :i2vision-cli:run --args="discover --intent=refactoring_analysis --module=i2vision-discover"

# Quality metrics
./gradlew :i2vision-cli:run --args="quality --module=i2vision-discover"
```

---

## 🎯 Core Concepts

### VSLFC: Five-Layer Contract System

```
Vision ←→ Structure ←→ Logic ←→ Flow ←→ Code
   ↓         ↓          ↓       ↓       ↓
Implements  Defines  Exercises  Calls  DependsOn
```

| Layer | What It Captures | Example |
|-------|-----------------|---------|
| **Vision** | Requirements, goals | "System must support OAuth2" |
| **Structure** | Components, modules | `AuthService`, `OAuth2Client` |
| **Logic** | Business rules | `require(token.isNotBlank())` |
| **Flow** | Sequences, API calls | `authenticate() → validate() → issueToken()` |
| **Code** | Implementation | `AuthService.kt:45` |

### Instant Context for LLMs

When an LLM requests context for a file, i2vision provides:

```yaml
File: AuthService.kt
├── Vision: "REQ-AUTH-001: OAuth2 authentication" ✅ Implemented
├── Structure: Component 'AuthService' (cohesion: 0.85)
├── Logic: 3 business rules (token validation, expiry check)
├── Flow: Called by LoginFlow, calls TokenValidator
├── Related: OAuth2Client.kt, SecurityConfig.kt, TokenValidator.kt
└── Quality: Complexity 12 (medium), Test coverage: 65%
```

**The LLM now understands not just the code, but its role in the architecture.**

### Bidirectional Discovery

```
Bottom-Up (Existing Code):   Code → Flow → Logic → Structure → Vision
Top-Down (Greenfield):       Vision → Structure → Logic → Flow → Code
```

**Contracts are the invariant.** Whether discovering existing code or scaffolding new features, contracts validate that layers remain consistent.

---

## 🎯 Why i2vision?

| Without i2vision | With i2vision |
|------------------|---------------|
| LLM sees isolated files | LLM understands architectural context |
| Manual code exploration | Automated discovery of flows & rules |
| Docs drift from code | Contracts validate consistency |
| "Where is this used?" | Instant cross-reference |
| "What does this module do?" | Component mapping with cohesion metrics |

---

## 🏗️ Architecture

### Module Ecosystem (12 Public Modules)

```
┌─────────────────────────────────────────────────────────────────┐
│                    MCP CLIENTS                                   │
│  Claude Desktop │ Cursor │ Continue │ Zed │ Custom              │
└─────────────────────────────────────────────────────────────────┘
                              ↓ MCP Protocol
┌─────────────────────────────────────────────────────────────────┐
│                    i2vision-mcp (MIT)                            │
│  • JSON-RPC 2.0 Server  • Stdio/HTTP Transport                  │
│  • Discovery Tools     • Context Tools      • Contract Tools    │
│  • Quality Metrics     • Related Files                          │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    i2vision-instant (MIT)                        │
│  • Instant Context API  • Proactive Context                     │
│  • Task-Aware Context   • Strategy Suggestions                  │
│  • Cache Management                                             │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    i2vision-discover (MIT)                       │
│  • Discovery Pipeline   • Flow Discovery                        │
│  • Logic Extraction     • Structure Building                    │
│  • Contract Validation  • Artifact Writing                      │
│  • Parallel Processing  • Incremental Sync                      │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    FOUNDATION MODULES (MIT)                      │
│  vslfc-core │ i2vision-architecture │ architecture-types        │
│  intent-parser │ storage-core │ conf-agent-core │ llm-client    │
└─────────────────────────────────────────────────────────────────┘
```

### Module List

| Module | Purpose | License |
|--------|---------|---------|
| `vslfc-core` | VSLFC models and contract primitives | MIT |
| `i2vision-architecture` | Multi-dimensional architecture detection | MIT |
| `architecture-types` | Pattern definitions and signatures | MIT |
| `intent-parser` | Intent resolution and preset management | MIT |
| `storage-core` | Semantic cache with incremental detection | MIT |
| `conf-agent-core` | YAML-configurable LLM agent framework | MIT |
| `llm-client` | Unified LLM client (OpenAI/Anthropic/Ollama) | MIT |
| `discovery-api` | Discovery interfaces | MIT |
| `i2vision-discover` | Discovery engine | MIT |
| `i2vision-cli` | CLI entry point | MIT |
| `i2vision-instant` | Instant context provider | MIT |
| `i2vision-mcp` | MCP server and tools | MIT |

---

## ✅ Proven with Self-Discovery

*i2vision analyzed its own codebase during development:*

| Metric | Value |
|--------|-------|
| Clusters Discovered | 38 |
| Success Rate | 100% |
| Flows Extracted | 1,585 |
| Business Rules | 2,200 |
| Components | 182 |
| Total Duration | 4.5 minutes |
| Processing | Parallel (38 clusters concurrent) |

*This was at a specific development stage—a real-world validation that the discovery engine works on complex, multi-module Kotlin projects.*

---

## 🔧 Key Features

### ✅ Contract-Based Validation
- **Bidirectional contracts** between all VSLFC layers
- **Automatic drift detection** when code changes
- **Gap analysis** finds missing implementations
- **Validation reports** for violated contracts

### ✅ Architecture Detection
- **Multi-dimensional signatures** per module
- **Cluster detection** (build modules → directory fallback)
- **Pattern recognition** (Hexagonal, Layered, Agent, Pipeline)
- **Confidence scoring** for detected patterns

### ✅ Quality Metrics
- **Cohesion & Coupling** - Internal vs external dependencies
- **Complexity Analysis** - Cyclomatic, cognitive complexity
- **Component Mapping** - Package structure analysis
- **Cross-Module Dependencies** - Import-based coupling

### ✅ Parallel Processing
- **38 clusters in 4.5 minutes**
- **Shared immutable context** across clusters
- **Batch link flushing** for concurrency safety
- **Cluster-level locking** for artifact writes

### ✅ Incremental Sync
- **File hash tracking** for change detection
- **Targeted rediscovery** of changed files only
- **Config snapshot** for design-time changes
- **Stale detection** for outdated artifacts

---

## 📚 Documentation

| Document | Description |
|----------|-------------|
| [VSLFC Layers](docs/concepts/vslfc-layers.md) | Five-layer contract system |
| [Contract System](docs/concepts/contracts.md) | Bidirectional validation |
| [Architecture Detection](docs/concepts/architecture.md) | Multi-dimensional signatures |
| [Project Structure](docs/concepts/project-structure.md) | VSLFC project layout |
| [MCP Integration](docs/guides/MCP_INTEGRATION.md) | Claude Desktop, Cursor setup |
| [MCP Tools](docs/guides/mcp-tools.md) | MCP toolset reference |
| [Instant Context](docs/guides/instant-context.md) | Task-aware context optimization |
| [Custom Tools](docs/guides/CUSTOM_TOOLS.md) | Creating custom MCP tools |
| [Deployment](docs/guides/DEPLOYMENT.md) | Deployment guide |
| [API Reference](docs/reference/api.md) | HTTP endpoints |
| [Strategies](docs/reference/strategies.md) | Discovery strategy definitions |
| [Diagrams](docs/diagrams/) | Sequence diagrams and architecture visualizations |

[Full Documentation →](docs/README.md)

---

## 🤝 Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](./CONTRIBUTING.md) before submitting a PR.

**Note:** We use a lightweight CLA to preserve future sustainability options. It takes **one click** when you submit your first PR.

---

## ⚖️ Disclaimer

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

**Specifically for i²-Vision:**
- Analysis results may contain inaccuracies or false positives
- Contract validation is heuristic-based, not formally verified
- LLM-enhanced features (when available) may produce hallucinations
- Always review suggestions before applying them to production codebases
- The tool analyzes source code but does not execute it; security validation
  remains your responsibility

---

## 📄 License

i²-Vision is licensed under the **MIT License**.

### ✅ You can:
- Use i²-Vision for **any purpose** (personal, commercial, internal)
- **Modify** the source code
- **Distribute** the original or modified version
- **Embed** i²-Vision in proprietary products
- Use analysis **outputs** (JSON, YAML, reports) in any product

### ⚠️ Requirements:
- Keep the copyright notice and license text
- The software is provided "as is", without warranty

### 💎 Commercial Support
MIT is free forever. If you need:
- **Priority support** or SLAs
- **Custom development** or integration
- **Enterprise features** (coming soon)

[Contact us →](mailto:enterprise@i2vision.dev)

---

**TL;DR: It's free. Do what you want. Attribution appreciated.**

---

## 📦 Built With

| Technology | Purpose |
|------------|---------|
| [Kotlin](https://kotlinlang.org/) | Primary language |
| [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Async & parallel processing |
| [Koog Agents](https://github.com/koog/koog-agents) | AI agent framework |
| [conf-agent-core](../conf-agent-core/) | YAML-configurable agent framework |
| [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) | YAML parsing |
| [MCP Protocol](https://modelcontextprotocol.io/) | LLM integration |

---

**i2vision turns code understanding from a one-time scan into a living, verifiable contract system that evolves with your codebase.**

⭐ **Star this repo if you find it useful!**
