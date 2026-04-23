# i2vision-cli

## Purpose

i2vision-cli provides the command-line interface for i2vision, enabling users to run discovery analysis, manage VSLFC structure, validate contracts, and get instant context for code.

## Architecture

The CLI is built using Clikt and provides commands for all major i2vision operations:

### Core Commands

**InitCommand** (`commands/InitCommand.kt`)
- Initialize VSLFC directory structure in a project
- Validate existing VSLFC structure
- Force re-initialization with `--force` flag

**DiscoverCommand** (`commands/DiscoverCommand.kt`)
- Run discovery analysis on projects
- Intent-driven discovery (recommended approach)
- Cluster-based parallel processing
- Support for legacy depth-based discovery

**ContractCommand** (`commands/ContractCommand.kt`)
- Contract validation and management
- List, validate, and create contracts
- Output in text, JSON, or YAML formats

**PresetCommand** (`commands/PresetCommand.kt`)
- Preset management for discovery configurations
- Pre-built presets for different project types
- Custom preset creation and management

**ContextCommand** (`commands/ContextCommand.kt`)
- Get instant context for files and directories
- Task-specific context analysis (debug, refactor, etc.)
- Multi-file context aggregation
- Cache management (stats, clean, invalidate)

## Usage

### Initialize VSLFC Structure

```bash
./gradlew :i2vision-cli:run --args="init --path=/path/to/project"
```

Options:
- `-p, --path` - Project path (default: current directory)
- `-f, --force` - Force re-initialization
- `-v, --validate` - Validate existing structure instead of initializing

### Run Discovery

```bash
# Full discovery (default)
./gradlew :i2vision-cli:run --args="discover /path/to/project"

# Intent-driven discovery (recommended)
./gradlew :i2vision-cli:run --args="discover --intent=full_discovery /path/to/project"
./gradlew :i2vision-cli:run --args="discover --intent=refactoring_analysis /path/to/project"
./gradlew :i2vision-cli:run --args="discover --intent=quick_overview /path/to/project"

# Cluster-focused discovery
./gradlew :i2vision-cli:run --args="discover --cluster=core-module /path/to/project"
```

Options:
- `--intent` - Discovery intent (full_discovery, refactoring_analysis, quick_overview, architecture_audit, flow_mapping, documentation_generation)
- `--preset` - Preset name (future: kotlin-agent, spring-boot, conservative, permissive)
- `--cluster` - Cluster ID for focused discovery
- `--depth` - [DEPRECATED] Use --intent instead (BROWSE, STANDARD, DEEP)
- `-o, --output` - Output directory for artifacts
- `--json` - Output results as JSON
- `--yaml` - Output results as YAML

### Get Instant Context

```bash
# Single file context (basic - symbols + complexity)
./gradlew :i2vision-cli:run --args="context file --path=AuthService.kt --project=/path/to/project"

# Task-specific context
./gradlew :i2vision-cli:run --args="context file --path=AuthService.kt --task=debug --project=/path/to/project"

# Multiple files context
./gradlew :i2vision-cli:run --args="context files file1.kt file2.kt file3.kt --project=/path/to/project"

# Enhanced context (requires discovery cache - flows, rules, components)
./gradlew :i2vision-cli:run --args="context enhanced --path=AuthService.kt --project=/path/to/project"

# Cache management
./gradlew :i2vision-cli:run --args="context cache stats --project=/path/to/project"
./gradlew :i2vision-cli:run --args="context cache clean --project=/path/to/project"
./gradlew :i2vision-cli:run --args="context cache invalidate --pattern=*.kt --project=/path/to/project"
```

Context subcommands:
- `file` - Get basic context for a specific file (symbols, complexity, suggestions)
- `files` - Get basic context for multiple files
- `enhanced` - Get enhanced context (requires discovery cache - flows, rules, components)
- `cache` - Cache management (stats, clean, invalidate)

**Context types:**
- **Basic context**: Works immediately without discovery
- **Enhanced context**: Requires discovery to be run first

Task types: `debug`, `refactor`, `add feature`, `fix bug`, `optimize`, `discovery` (default)

### Contract Management

```bash
# Validate a contract
./gradlew :i2vision-cli:run --args="contract validate --contract=/path/to/contract.yaml"

# List contracts
./gradlew :i2vision-cli:run --args="contract list --contract-dir=.vision-ai/.contracts"

# Create a contract
./gradlew :i2vision-cli:run --args="contract create"
```

### Preset Management

```bash
# List available presets
./gradlew :i2vision-cli:run --args="preset list"

# Use a preset for discovery
./gradlew :i2vision-cli:run --args="discover --preset=kotlin-agent /path/to/project"
```

## Output

The CLI provides structured output for all operations:

- **Discovery**: Artifacts written to semantic cache, summary statistics displayed
- **Context**: Symbols, related files, task suggestions, artifacts, complexity metrics
- **Contracts**: Validation results with detailed error messages
- **Cache**: Statistics including total entries, hit rate, and size

## Dependencies

- Clikt: Command-line interface framework
- i2vision-discover: Discovery engine
- i2vision-instant: Instant context provider
- storage-core: Semantic cache management
- vslfc-core: VSLFC data structures
- SLF4J: Logging
- Kotlin Coroutines: Async operations

## Integration

The CLI serves as the primary user-facing interface for i2vision, integrating with:
- **i2vision-discover**: Running discovery pipelines
- **i2vision-instant**: Providing instant context
- **storage-core**: Managing semantic cache
- **i2vision-architecture**: Architecture detection
