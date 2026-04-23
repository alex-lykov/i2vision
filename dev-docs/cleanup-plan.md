# i2vision Cleanup Plan

## Status: COMPLETED

### Branches

- `legacy` - Full project state preserved for future refactoring
- `main` - Public release (cleaned)

---

## Modules - Keep in Public Repo

| Module                  | Purpose                  | License        |
|-------------------------|--------------------------|----------------|
| `vslfc-core`            | VSLFC models & contracts | MIT            |
| `llm-client`            | Unified LLM client       | MIT            |
| `conf-agent-core`       | YAML-configurable agents | MIT            |
| `storage-core`          | Semantic cache           | MIT            |
| `intent-parser`         | Intent resolution        | MIT            |
| `i2vision-architecture` | Architecture detection   | MIT            |
| `architecture-types`    | Pattern definitions      | MIT            |
| `discovery-api`         | Discovery interfaces     | MIT            |
| `i2vision-discover`     | Discovery engine         | MIT+Commercial |
| `i2vision-cli`          | CLI entry                | MIT+Commercial |
| `i2vision-instant`      | Instant context          | MIT+Commercial |
| `i2vision-mcp`          | MCP server               | MIT+Commercial |
| `docs`                  | Documentation            | -              |
| `scripts`               | Utility scripts          | -              |

---

## Modules - Removed from Public (Preserved in Legacy Branch)

| Module                 | Reason                                   | Status              |
|------------------------|------------------------------------------|---------------------|
| `.gradle`              | Build cache                              | Removed             |
| `.idea`                | IDE files                                | Removed             |
| `.kotlin`              | Kotlin cache                             | Removed             |
| `.vision-ai`           | Local config                             | Removed             |
| `.vscode`              | IDE files                                | Removed             |
| `buildSrc`             | Build helpers                            | Removed             |
| `configurable-agent`   | Legacy (extracted to conf-agent-core)    | Removed             |
| `context`              | WIP                                      | Preserved in legacy |
| `contracts`            | Legacy (moved to vslfc-core)             | Removed             |
| `discovery-engine`     | Legacy (moved to i2vision-discover)      | Removed             |
| `discovery-validation` | Test harness                             | Preserved in legacy |
| `index-provider`       | Legacy                                   | Preserved in legacy |
| `learning`             | Premium feature                          | Preserved in legacy |
| `link-service`         | Legacy                                   | Preserved in legacy |
| `models`               | Legacy (moved to vslfc-core, llm-client) | Removed             |
| `pipeline`             | WIP                                      | Preserved in legacy |
| `server`               | Legacy (moved to i2vision-instant)       | Removed             |
| `switching`            | WIP                                      | Preserved in legacy |
| `task-executor`        | Legacy                                   | Preserved in legacy |

---

## Stale Module References Removed from settings.gradle.kts

The following modules were referenced in settings.gradle.kts but did not exist on disk:

- `core:orchestrator`, `core:session`, `core:coroutines`, `core:config`
- `database`, `ui`, `security`
- `kotlin:analysis`
- `launcher`, `agents`

---

## .gitignore Updates

Added entries for production readiness:

- Logs (*.log, logs/)
- Secrets (secrets.properties, *.secret, *.key, .env)
- Temporary files (*.tmp, *.swp, *~)
- Local config (local.properties, gradle-local.properties)
- Test outputs (test-output/, reports/)
- OS files (Thumbs.db, desktop.ini)

---

## Completed Actions

- [x] Removed legacy modules from main branch
- [x] Updated `settings.gradle.kts` with only public modules
- [x] Removed stale module references from settings.gradle.kts
- [x] Updated `.gitignore` with production-ready entries
- [x] Created `docs/cleanup-plan.md`

---

## Verification

Run the following to verify clean state:

```powershell
# List remaining modules
Get-ChildItem -Directory | Where-Object {
    $_.Name -notin @(".git", ".github")
} | Select-Object Name

# Expected output:
# architecture-types
# conf-agent-core
# discovery-api
# docs
# gradle
# i2vision-architecture
# i2vision-cli
# i2vision-discover
# i2vision-instant
# i2vision-mcp
# intent-parser
# llm-client
# scripts
# storage-core
# vslfc-core
```

---

## Next Steps

1. Commit changes to main branch
2. Push to GitHub
3. Create release tag for public version
