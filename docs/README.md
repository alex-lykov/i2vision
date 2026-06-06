# i2vision Documentation

Welcome to the i2vision documentation, organized using the Diátaxis framework.

## Core Concepts

Understanding the fundamental concepts behind i2vision:

- [VSLFC Layers](concepts/vslfc-layers.md) - Five-layer contract system (Vision, Structure, Logic, Flow, Code)
- [Documentation Contracts](concepts/contracts.md) - Bidirectional validation between definitions and artifacts

## How-To Guides

Step-by-step guides for specific tasks:

### LLM Providers & Configuration

- [Ollama Integration](guides/ollama-integration.md) - **Primary provider** (local + cloud models)
- [DeepSeek Integration](guides/deepseek-integration.md) - Direct DeepSeek API (advanced)
- [VSCode Provider Selection](guides/vscode-provider-model-selection.md) - VSCode extension UI guide

### MCP & Tools

- [MCP Tools](guides/mcp-tools.md) - Model Context Protocol toolset reference
- [MCP Integration](guides/mcp-integration.md) - Claude Desktop, Cursor setup
- [Custom Tools](guides/custom-tools.md) - Creating custom MCP tools

### Deployment & Configuration

- [Deployment](guides/deployment.md) - Deployment guide
- [Presets](guides/presets.md) - Discovery preset configuration
- [Agent Implementation](guides/agent-implementation.md) - Agent framework guide

## Reference

Technical reference material:

- [API Reference](reference/api.md) - HTTP endpoints and interfaces
- [Strategies](reference/strategies.md) - Discovery strategy definitions

## Diagrams

Visual documentation (click to view in browser):

- **[Architecture Detection Flow](diagrams/links/architecture-detection-flow.md)** - Build system, cluster, and pattern detection
- **[CLI Flow](diagrams/links/cli-flow.md)** - Command parsing and execution
- **[Cluster-Based Discovery](diagrams/links/cluster-based-discovery.md)** - Multi-cluster discovery pipeline
- **[Contract Lifecycle](diagrams/links/contract-lifecycle.md)** - Contract definition and validation flow
- **[Discovery Flow](diagrams/links/discovery-flow.md)** - Unified framework discovery pipeline
- **[Full Project Discovery](diagrams/links/full-project-discovery-flow.md)** - End-to-end discovery with architecture detection
- **[Incremental Sync Flow](diagrams/links/incremental-sync-flow.md)** - File change detection and targeted rediscovery
- **[Instant Context Flow](diagrams/links/instant-context-flow.md)** - Task-aware context optimization
- **[MCP Server Flow](diagrams/links/mcp-server-flow.md)** - MCP tool registration and execution
- **[Parallel Discovery Concurrency](diagrams/links/parallel-discovery-concurrency.md)** - Concurrent cluster processing techniques
- **[Quality Metrics Flow](diagrams/links/quality-metrics-flow.md)** - Cohesion, coupling, and complexity analysis

## Module Documentation

Individual module documentation:

- [architecture-types](../architecture-types/) - Multi-dimensional architecture detection
- [vslfc-core](../vslfc-core/) - VSLFC data models and contracts
- [i2vision-architecture](../i2vision-architecture/) - Architecture detection engine
- [llm-client](../llm-client/) - Unified LLM client abstraction
- [conf-agent-core](../conf-agent-core/) - YAML-configurable agent framework
- [storage-core](../storage-core/) - Storage abstraction layer
- [intent-parser](../intent-parser/) - Intent resolution engine
- [discovery-api](../discovery-api/) - Discovery interfaces
- [i2vision-discover](../i2vision-discover/) - Discovery pipeline implementation
- [i2vision-cli](../i2vision-cli/) - Command-line interface
- [i2vision-instant](../i2vision-instant/) - Instant context API
- [i2vision-mcp](../i2vision-mcp/) - MCP server implementation

---

## Internal Documentation

Developer-facing documentation is located in [`../dev-docs/`](../dev-docs/):

- Test plans
- Cleanup documentation
- Refactoring strategies
