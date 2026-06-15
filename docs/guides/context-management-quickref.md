# Context Management Quick Reference

## Quick Start

**File:** `.vision-ai/code-agent.yaml`

```yaml
context:
  default:
    eager:
      currentFile: true      # Load open file symbols
      projectMetadata: false # Skip project metadata
      gitStatus: false       # Skip git status
      gitDiff: false         # Skip git diff
      relatedFiles: false    # Skip related files
      directoryStructure: false
    lazy:
      discovery: true        # Enable discovery tools
      fullContext: true
      contractValidation: true
```

---

## Task Detection Keywords

| Task | Keywords |
|------|----------|
| `refactor` | refactor, rename, extract, move, restructure |
| `debug` | debug, fix, bug, error, crash, fail, exception |
| `explore` | explain, what, how, explore, find, show, describe |
| `create` | write, create, add, implement, build, generate, new |
| `test` | test, spec, unit, integration, coverage |

---

## Common Configurations

### Minimal (Fastest)
```yaml
context:
  default:
    eager:
      currentFile: true
```

### Debug-Focused
```yaml
context:
  default:
    eager:
      currentFile: true
      gitDiff: true
```

### Refactoring
```yaml
context:
  default:
    eager:
      currentFile: true
  tasks:
    refactor:
      eager:
        relatedFiles: true
```

### Project Exploration
```yaml
context:
  default:
    eager:
      projectMetadata: true
      directoryStructure: true
```

---

## Performance Impact

| Field | Load Time | Token Cost |
|-------|-----------|------------|
| `currentFile` | ~50ms | ~500 |
| `relatedFiles` | ~200ms | ~2000 |
| `projectMetadata` | ~100ms | ~300 |
| `gitStatus` | ~50ms | ~200 |
| `gitDiff` | ~100ms | ~1000 |
| `directoryStructure` | ~50ms | ~400 |

---

## Monitoring Logs

```
[AgentBridge] Task type detected: "refactor"
[AgentBridge] Context profile loaded: eager=3, lazy=3
[AgentBridge] Eager context loaded: currentFile=true, project=false, git=false
```

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| Context not loading | Check `currentFile: true` in YAML |
| Too slow | Disable `relatedFiles`, `gitDiff` |
| Missing tools | Check `lazy.discovery: true` |
| Wrong task detected | Use explicit prefix: "debug: fix this" |

---

## Full Schema

```yaml
context:
  default:
    eager:
      currentFile?: boolean
      projectMetadata?: boolean
      gitStatus?: boolean
      gitDiff?: boolean
      relatedFiles?: boolean
      directoryStructure?: boolean
    lazy:
      discovery?: boolean
      fullContext?: boolean
      contractValidation?: boolean
  tasks?:
    refactor?:
      eager?: Partial<eager>
    debug?:
      eager?: Partial<eager>
    explore?:
      eager?: Partial<eager>
    create?:
      eager?: Partial<eager>
    test?:
      eager?: Partial<eager>
```

---

**Full Documentation:** [Context Management Guide](./context-management.md)
