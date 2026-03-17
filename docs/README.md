# i2vision Documentation

Welcome to the i2vision documentation, organized using the Diátaxis framework.

## Core Concepts

Understanding the fundamental concepts behind i2vision:

- [VSLFC Layers](concepts/vslfc-layers.md) - Five-layer contract system (Vision, Structure, Logic, Flow, Code)
- [Documentation Contracts](concepts/contracts.md) - Bidirectional validation between definitions and artifacts

## How-To Guides

Step-by-step guides for specific tasks:

- [MCP Tools](guides/mcp-tools.md) - Model Context Protocol toolset reference
- [MCP Integration](guides/mcp-integration.md) - Claude Desktop, Cursor setup
- [Custom Tools](guides/custom-tools.md) - Creating custom MCP tools
- [Deployment](guides/deployment.md) - Deployment guide
- [Presets](guides/presets.md) - Discovery preset configuration

## Reference

Technical reference material:

- [API Reference](reference/api.md) - HTTP endpoints and interfaces
- [Strategies](reference/strategies.md) - Discovery strategy definitions

## Diagrams

Visual documentation:

- [Architecture Detection Flow](diagrams/architecture-detection-flow.sd) - Build system, cluster, and pattern detection
- [CLI Flow](diagrams/cli-flow.sd) - Command parsing and execution
- [Cluster-Based Discovery](diagrams/cluster-based-discovery.sd) - Multi-cluster discovery pipeline
- [Contract Lifecycle](diagrams/contract-lifecycle.sd) - Contract definition and validation flow
- [Discovery Flow](diagrams/discovery-flow.sd) - Unified framework discovery pipeline
- [Full Project Discovery](diagrams/full-project-discovery-flow.sd) - End-to-end discovery with architecture detection
- [Incremental Sync Flow](diagrams/incremental-sync-flow.sd) - File change detection and targeted rediscovery
- [Instant Context Flow](diagrams/instant-context-flow.sd) - Task-aware context optimization
- [MCP Server Flow](diagrams/mcp-server-flow.sd) - MCP tool registration and execution
- [Parallel Discovery Concurrency](diagrams/parallel-discovery-concurrency.sd) - Concurrent cluster processing techniques
- [Quality Metrics Flow](diagrams/quality-metrics-flow.sd) - Cohesion, coupling, and complexity analysis

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
- Migration plans
- Test plans
- Cleanup documentation
- Refactoring strategies
