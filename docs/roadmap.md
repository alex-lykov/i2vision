# i²-Vision Roadmap

*Last Updated: April 2026*

---

## ✅ Production Ready

| Feature | Description | Test Command |
|---------|-------------|--------------|
| Core Discovery Engine | Code → Vision analysis (38 clusters validated) | `./gradlew :i2vision-cli:run --args="discover /path/to/project"` |
| Parallel Processing | Concurrent cluster analysis (4.5 min) | Observe logs during discovery |
| Architecture Detection | Multi-dimensional signatures per module | Check cache directory structure |
| Quality Metrics | Cohesion, coupling, complexity | Check `components.yaml` |
| Incremental Sync | Changed-file only updates | Modify file, re-run discovery |
| CLI Tool | Full command interface | `./gradlew :i2vision-cli:run --args="--help"` |

**Quick Test:** `./gradlew :i2vision-cli:run --args="discover /path/to/project"`

---

## 🧪 Beta (Active Testing)

| Feature | Description | Expected Stable |
|---------|-------------|-----------------|
| MCP Integration | Claude Desktop, Cursor support | v0.3.0 |
| Instant Context API | HTTP context endpoints | v0.3.0 |
| Proactive Context | Related file suggestions | v0.3.0 |

**Try Beta:** Start MCP server with `./gradlew :i2vision-mcp:run --args="--stdio"`

---

## 📋 Prioritized Next Tasks

### P0 - Before Public Announcement
- [ ] End-to-end MCP test with Claude Desktop
- [ ] Fix any failing unit tests
- [ ] Ensure clean `./gradlew build`

### P1 - First Month After Release
- [ ] LLM-Enhanced Discovery (improve rule verbalization)
- [ ] Preset Management (user-defined presets)
- [ ] Basic HTML Reports (single-page dashboard)

### P2 - Q3 2026
- [ ] Learning Engine (parameter optimization)
- [ ] Contract Remediation (auto-fix suggestions)
- [ ] Cloud Sync (team collaboration)

### P3 - Q4 2026 / Q1 2027
- [ ] IntelliJ Plugin
- [ ] Multi-language Support (Python, TypeScript)
- [ ] SSO / SAML (Enterprise)

---

## 📊 Feature Status Summary

| Status | Count | Features |
|--------|-------|----------|
| ✅ Production Ready | 6 | Core engine, CLI, metrics, sync |
| 🧪 Beta | 3 | MCP, Context API, Proactive |
| 🔬 Alpha | 3 | LLM, Learning, Presets |
| 📋 Planned | 12 | Cloud, Remediation, IDE, Languages |

---

## 🤝 Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md) for development setup.
