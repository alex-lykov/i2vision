# Sequence Diagram Links

Auto-generated interactive diagram links. Click any link to view the diagram in your browser.

## 📋 All Diagrams

### Architecture & Design

| Diagram | Description |
|---------|-------------|
| **[Architecture Detection Flow](architecture-detection-flow.md)** | How the system detects project architecture |
| **[Cluster-Based Discovery](cluster-based-discovery.md)** | Cluster-based module discovery strategy |
| **[MCP Server Flow](mcp-server-flow.md)** | MCP server request handling architecture |

### CLI & User Interaction

| Diagram | Description |
|---------|-------------|
| **[CLI Flow](cli-flow.md)** | End-to-end CLI command execution |
| **[CLI Context Flow](cli-context-flow.md)** | Context loading and resolution in CLI |
| **[Instant Context Flow](instant-context-flow.md)** | Instant context API for real-time queries |

### Discovery Pipeline

| Diagram | Description |
|---------|-------------|
| **[Discovery Flow](discovery-flow.md)** | Core discovery pipeline execution |
| **[Full Project Discovery Flow](full-project-discovery-flow.md)** | Complete project discovery workflow |
| **[Parallel Discovery Concurrency](parallel-discovery-concurrency.md)** | Parallel processing and concurrency model |

### Verbalization

| Diagram | Description |
|---------|-------------|
| **[Verbalization Flow](verbalization-flow.md)** | Code-to-natural-language transformation with three strategies |

### Contract & Quality

| Diagram | Description |
|---------|-------------|
| **[Contract Lifecycle](contract-lifecycle.md)** | Contract validation and lifecycle |
| **[Quality Metrics Flow](quality-metrics-flow.md)** | Quality metrics calculation pipeline |

### Synchronization

| Diagram | Description |
|---------|-------------|
| **[Incremental Sync Flow](incremental-sync-flow.md)** | Incremental synchronization process |

---

## 🔄 Regenerate Links

After editing any `.sd` file, regenerate the links:

```powershell
# From project root
.\docs\diagrams\convert-all-diagrams.ps1
```

Or convert a single diagram:

```powershell
.\docs\diagrams\convert-diagram-to-link.ps1 docs\diagrams\<diagram-name>.sd
```

---

## ✅ Validation

Before converting, validate diagram syntax:

```powershell
.\docs\diagrams\validate-diagrams.ps1
```

---

**Last Generated:** Auto-updated on each conversion run
