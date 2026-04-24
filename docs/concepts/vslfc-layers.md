# VSLFC Layers Concept

## The Five Layers Model

VSLFC is a five-layer model for understanding code at different levels of abstraction:

```
Vision     ← What we want (requirements, goals, constraints)
Structure  ← How components fit (architecture, modules, dependencies)
Logic      ← What each part does (business rules, state machines, validation)
Flow       ← How parts interact (user flows, sequences, interactions)
Code       ← How it is built (implementation, unit tests, actual code)
```

---

## Each Layer Explained

### Vision Layer

**Purpose:** Understand the system's purpose and requirements

**Contains:**

- Business goals and constraints
- User needs and scenarios
- Quality attributes
- System boundaries

**Generated From:** User documentation, requirements, business context

### Structure Layer

**Purpose:** Understand how components are organized

**Contains:**

- Component definitions
- Module boundaries
- Dependency relationships
- Service contracts
- API definitions

**Generated From:** Code analysis, package structure, class hierarchies

### Logic Layer

**Purpose:** Understand what each component does

**Contains:**

- Business rules
- State machines
- Validation logic
- Decision trees
- Domain constraints

**Generated From:** Code analysis, domain knowledge, business rules

### Flow Layer

**Purpose:** Understand how components interact

**Contains:**

- User workflows
- Sequence diagrams
- API call sequences
- Event flows
- Data transformations

**Generated From:** Code analysis, user scenarios, integration patterns

### Code Layer

**Purpose:** Understand the actual implementation

**Contains:**

- Source code
- Unit tests
- Implementation details
- Technical decisions
- Code metrics

**Source:** Actual source files, always current

---

## Discovery Directions

### Bottom-Up: Code → Vision

**Process:** Code → Flow → Logic → Structure → Vision

**Use When:**

- Analyzing existing codebase
- Understanding what IS
- Discovering undocumented features
- Analyzing legacy systems

**Confidence:** High (based on actual code)

### Top-Down: Vision → Code

**Process:** Vision → Structure → Logic → Flow → Code

**Use When:**

- Planning new features
- Designing new systems
- Defining requirements
- Implementing from specs

**Confidence:** Based on specification completeness

### Bidirectional: Both Directions

**Process:** Discover both and compare

**Use When:**

- Validating architecture
- Checking consistency
- Finding gaps between intent and implementation
- Quality assurance

---

## Artifacts & Storage

### Cache Location

**Semantic Cache:** OS-specific user directory

- **Windows:** `%LOCALAPPDATA%\i2vision\cache\projects\<project-hash>\.semantic-cache\`
- **macOS:** `~/Library/Application Support/i2vision/cache/projects/<project-hash>/.semantic-cache/`
- **Linux:** `~/.i2vision/cache/projects/<project-hash>/.semantic-cache/`

All VSLFC artifacts are stored in the OS user directory for portability across projects.

### Per-Cluster Structure

```
{cache-directory}/.semantic-cache/
├── {cluster-1}/
│   ├── vision/
│   ├── structure/
│   ├── logic/
│   ├── flow/
│   └── code/
└── {cluster-N}/
```

### Format

- **YAML:** Structured, human-readable
- **Markdown:** For documentation and sequences
- **Mermaid:** For diagrams and visualizations

### Versioning

- **Git-based:** Commit snapshots for definitions
- **Timestamped:** Per-discovery markers for artifacts
- **Portable:** Can be exported and versioned separately

### Contract Lifecycle

See **[Contract Lifecycle Flow](../diagrams/html/contract-lifecycle.html)** for a detailed sequence diagram showing the complete
contract lifecycle from definition to validation.

---

## Practical Example

### Analyzing the Orchestrator Module

**Bottom-Up Discovery (Code → Vision):**

1. **CODE:** Read `AgentOrchestrator.kt`, `DiscoveryPipeline.kt`
2. **FLOW:** Extract orchestration sequences, dispatch patterns
3. **LOGIC:** Identify business rules for orchestration
4. **STRUCTURE:** Map component relationships
5. **VISION:** Understand purpose: "Orchestrate agent discovery and synchronization"

**Result:** Understanding of actual implementation

---

## Layer Coverage

Each module can have different coverage:

```
Module A:
  Vision:    ████████░░ (80%)
  Structure: ██████████ (100%)
  Logic:     ████████░░ (80%)
  Flow:      ███░░░░░░░ (30%)
  Code:      ██████████ (100%)

Module B:
  Vision:    ██████░░░░ (60%)
  Structure: ████░░░░░░ (40%)
  Logic:     ███░░░░░░░ (30%)
  Flow:      ░░░░░░░░░░ (0%)
  Code:      ██████████ (100%)
```

Coverage varies based on module maturity and documentation.

---

## Using VSLFC Layers

### In Discovery Pipeline

- Layers are discovered in order
- Each layer builds on previous
- Results stored in OS-specific semantic cache directory

### In Strategy Execution

- Strategy determines which layers to focus on
- Different strategies use different layers
- Can combine layers for richer analysis

### In Documentation

- Each layer can have documentation
- Can map to user docs via contracts
- Provides comprehensive view

---

## Key Principles

1. **Hierarchical understanding** - Each layer builds on the previous
2. **Multiple entry points** - Can start from any layer
3. **Bidirectional flow** - Can go up or down
4. **Coverage tracking** - Know what's documented
5. **Consistency checking** - Validate layers agree

