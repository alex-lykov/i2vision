# Sequence Diagram Workflow Guide

## Overview

This directory contains sequence diagrams in **`.sd` format** - a plain-text DSL for [sequencediagram.org](https://sequencediagram.org).

Since `.sd` is not a standard file type with IDE preview support, we use a **link generation workflow** that creates interactive diagram URLs.

---

## 📁 Directory Structure

```
docs/diagrams/
├── *.sd                          # Source diagram files (edit these)
├── convert-diagram-to-link.ps1   # Single diagram converter
├── convert-all-diagrams.ps1      # Batch converter (all diagrams)
├── validate-diagrams.ps1         # Syntax validation script
└── links/
    ├── README.md                 # Index of all diagrams
    └── *.md                      # Generated markdown links (auto-generated)
```

---

## 🔄 Workflow

### 1. Create/Edit Diagram

Edit or create a `.sd` file:

```sd
title My Diagram

participant "User" as User
participant "System" as System

User->System: Request
activate System
System-->User: Response
deactivate System
```

**Available diagrams:**
- `cli-flow.sd` - CLI command execution flow
- `discovery-flow.sd` - Discovery pipeline
- `mcp-server-flow.sd` - MCP server architecture
- `contract-lifecycle.sd` - Contract validation lifecycle
- `instant-context-flow.sd` - Instant context API flow
- `quality-metrics-flow.sd` - Quality metrics calculation
- `incremental-sync-flow.sd` - Incremental sync process
- `cluster-based-discovery.sd` - Cluster discovery
- `architecture-detection-flow.sd` - Architecture detection
- `full-project-discovery-flow.sd` - End-to-end discovery
- `parallel-discovery-concurrency.sd` - Parallel processing

---

### 2. Generate Link File

#### Option A: Single Diagram

```powershell
# From project root
.\docs\diagrams\convert-diagram-to-link.ps1 docs\diagrams\cli-flow.sd
```

#### Option B: All Diagrams (Batch)

```powershell
# From project root
.\docs\diagrams\convert-all-diagrams.ps1
```

This generates/updates all `.md` link files in `docs/diagrams/links/`.

---

### 3. View Diagram

Click the generated link in `docs/diagrams/links/*.md` to open the interactive diagram in your browser.

**Example:** `docs/diagrams/links/cli-flow.md` contains:
```markdown
[CLI Flow](https://sequencediagram.org/index.html?presentationMode=readOnly&shrinkToFit=true#initialData=...)
```

---

### 4. Reference in Documentation

Add the link to `docs/README.md`:

```markdown
## Diagrams

- **[CLI Flow](diagrams/links/cli-flow.md)** - Command execution flow
```

---

## 🛠️ Scripts

### `convert-diagram-to-link.ps1`

Converts a single `.sd` file to a markdown link.

**Usage:**
```powershell
.\docs\diagrams\convert-diagram-to-link.ps1 <path-to-diagram.sd>
```

**What it does:**
1. Reads the `.sd` file content
2. Extracts the title from the first line
3. URL-encodes the diagram content
4. Generates a sequencediagram.org URL with `presentationMode=readOnly&shrinkToFit=true`
5. Writes a markdown link to `docs/diagrams/links/<diagram-name>.md`

---

### `convert-all-diagrams.ps1`

Batch converts all `.sd` files in the diagrams directory.

**Usage:**
```powershell
.\docs\diagrams\convert-all-diagrams.ps1
```

**Output:**
```
Converting: architecture-detection-flow.sd → Created link file: docs/diagrams/links/architecture-detection-flow.md
Converting: cli-context-flow.sd → Created link file: docs/diagrams/links/cli-context-flow.md
...
Converted 12 diagrams
```

---

### `validate-diagrams.ps1`

Validates diagram syntax before conversion.

**Usage:**
```powershell
.\docs\diagrams\validate-diagrams.ps1
```

**Checks:**
- ✅ Title is present
- ✅ At least one participant defined
- ✅ Balanced fragment tags (alt/opt/loop/end)
- ✅ Valid message syntax
- ✅ No empty files

**Output:**
```
Validating: cli-flow.sd ... OK
Validating: discovery-flow.sd ... OK
Validating: broken-diagram.sd ... ERROR: Unbalanced alt/end (3 alt, 2 end)
```

---

## 📝 DSL Syntax Reference

### Basic Elements

```sd
title Diagram Title

participant "Display Name" as Alias
actor "Actor Name" as Actor
database "Database" as DB

Alias->Actor: Message text
Actor-->Alias: Response text
Alias->Alias: Self message

activate Actor
deactivate Actor

note over Alias, Actor: Note text
note right of Alias: Right note
note left of Actor: Left note
```

### Fragments

```sd
alt Condition
    A->B: True branch
else
    A->B: False branch
end

opt Optional
    A->B: Optional message
end

loop i < 10
    A->B: Repeated message
end

par Parallel 1
    A->B: Message 1
else Parallel 2
    C->D: Message 2
end
```

### Styling

```sd
participant "Styled" as P #lightblue #darkblue;2;dashed
note over P: Note #yellow #orange;4

lifelinestyle #gray;1;dashed
messagestyle #green;2
notestyle <wordwrap:30>

style myWarning #white #red;2;dashed,**<color:#red>
note right of A ##myWarning: Warning note
```

### Advanced

```sd
autonumber 1
A->B: Message 1
B->C: Message 2

entryspacing 2
A->B: Spaced message

participantspacing 50
participant A
participant B
participant C

fontfamily mono
```

---

## 💡 Tips & Best Practices

### 1. Keep Diagrams Focused
- Each diagram should show **one workflow** or **one component interaction**
- Limit to 5-10 participants for readability
- Use fragments to group related interactions

### 2. Use Descriptive Titles
```sd
// Good
title CLI Command Execution Flow

// Avoid
title Flow 1
```

### 3. Add Section Notes
```sd
note over User, System: === Phase 1: Initialization ===
...
note over User, System: === Phase 2: Processing ===
```

### 4. Consistent Naming
- Use **PascalCase** for participant aliases: `User`, `CommandParser`, `DiscoveryPipeline`
- Use **descriptive display names**: `"Command Parser"`, `"Discovery Pipeline"`

### 5. Version Control
- `.sd` files are plain text - perfect for Git
- Commit `.sd` files, **not** the generated `.md` link files (optional)
- Consider adding `docs/diagrams/links/*.md` to `.gitignore` if you want to auto-generate on build

---

## 🔧 VS Code Setup (Optional)

While there's no native preview, you can improve the editing experience:

### 1. Syntax Highlighting

Create `.vscode/settings.json`:
```json
{
  "files.associations": {
    "*.sd": "plaintext"
  }
}
```

Or install a **PlantUML** extension and use similar syntax highlighting.

### 2. Snippets

Create `.vscode/snippets/sd-snippets.json`:
```json
{
  "Participant": {
    "prefix": "part",
    "body": "participant \"${1:Name}\" as ${2:Alias}"
  },
  "Message": {
    "prefix": "msg",
    "body": "${1:Source}->${2:Target}: ${3:Message}"
  },
  "Alt Fragment": {
    "prefix": "alt",
    "body": "alt ${1:Condition}\n\t$0\nelse\n\t\nend"
  },
  "Note": {
    "prefix": "note",
    "body": "note over ${1:A}, ${2:B}: ${3:Note text}"
  },
  "Activate": {
    "prefix": "act",
    "body": "activate ${1:Participant}"
  }
}
```

---

## 🚨 Common Issues

### Issue: URL Too Long

**Symptom:** Diagram doesn't load, browser shows error

**Cause:** sequencediagram.org URL exceeds browser limit (~2000-8000 characters)

**Solution:**
- Simplify the diagram
- Split into multiple diagrams
- Use abbreviations in participant names

---

### Issue: Special Characters Not Escaped

**Symptom:** Diagram shows garbled text or doesn't load

**Cause:** Special characters in diagram content not properly URL-encoded

**Solution:** The conversion script handles this automatically. If editing URLs manually, use proper URL encoding.

---

### Issue: Link File Out of Date

**Symptom:** Diagram shows old version after editing `.sd` file

**Solution:** Re-run the conversion script:
```powershell
.\docs\diagrams\convert-diagram-to-link.ps1 docs\diagrams\your-diagram.sd
```

---

## 🔗 Resources

- **Official Instructions:** https://sequencediagram.org/instructions.html
- **Color Names:** https://www.w3schools.com/colors/colors_names.asp
- **FontAwesome Icons:** https://fontawesome.com/icons
- **Material Design Icons:** https://pictogrammers.com/library/mdi/

---

## 📋 Checklist for New Diagrams

- [ ] Create `.sd` file with descriptive name
- [ ] Add `title` on first line
- [ ] Define all participants
- [ ] Add section notes for phases
- [ ] Validate syntax with `validate-diagrams.ps1`
- [ ] Run `convert-diagram-to-link.ps1` to generate link
- [ ] Test the link in browser
- [ ] Add to `docs/diagrams/links/README.md`
- [ ] Reference in main `docs/README.md`
