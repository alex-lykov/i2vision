# Quick Start: Sequence Diagrams

## 🚀 In 3 Steps

### 1. Edit or Create a Diagram

Edit any `.sd` file in `docs/diagrams/`:

```sd
title My New Diagram

participant "User" as User
participant "System" as System

User->System: Request
activate System
System-->User: Response
deactivate System
```

### 2. Generate Link

```powershell
# Single diagram
.\docs\diagrams\convert-diagram-to-link.ps1 docs\diagrams\my-diagram.sd

# OR all diagrams
.\docs\diagrams\convert-all-diagrams.ps1
```

### 3. View in Browser

Open the generated file in `docs/diagrams/links/` and click the link!

---

## 📋 Available Commands

| Command | Description |
|---------|-------------|
| `.\docs\diagrams\validate-diagrams.ps1` | Check syntax of all diagrams |
| `.\docs\diagrams\convert-diagram-to-link.ps1 <file>` | Convert one diagram |
| `.\docs\diagrams\convert-all-diagrams.ps1` | Convert all diagrams |

---

## 📖 Full Documentation

See [README.md](README.md) for:
- Complete DSL syntax reference
- Best practices
- Troubleshooting
- VS Code setup

---

## 🔗 Quick Links

- **[All Diagrams](links/README.md)** - Browse all generated links
- **sequencediagram.org** - https://sequencediagram.org
- **Official Instructions** - https://sequencediagram.org/instructions.html
