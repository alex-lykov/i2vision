# Cleanup Plan

## 🗑️ High Priority Cleanup

### 1. Old/Unused Code Files
- ✅ **`src/main/kotlin/com/alyk/ai/koog/Main.kt`** - Old sketch code, replaced by `launcher/src/main/kotlin/Main.kt`
  - Contains duplicate `CodingAgent` class with old tool execution logic
  - No longer referenced anywhere
  - **Action**: DELETE

### 2. Backup Files
- ✅ **`launcher/src/main/kotlin/gui/components/OllamaMonitorAndControl.kt.backup`**
  - Backup file that shouldn't be in version control
  - **Action**: DELETE

### 3. Empty/Unused Directories
- ⚠️ **`src/` directory** - Only contains old Main.kt, can be removed after deleting Main.kt
- ⚠️ **`agents/` directory** - Check if used
- ⚠️ **`mcp/` directory** - Empty directory
- ⚠️ **`ui/` directory** - Only stub implementations

## 🔧 Medium Priority Cleanup

### 4. Stub Implementations (Keep for now, but document)
These are intentional stubs for future implementation:
- `core/orchestrator/agents/IdeaAgent.kt` - Stub
- `core/orchestrator/agents/ArchitectureAgent.kt` - Stub
- `core/orchestrator/agents/ModuleAgent.kt` - Stub
- `core/orchestrator/agents/TestAgent.kt` - Stub
- `server/` module - All stubs
- `database/` module - All stubs
- `security/` module - All stubs
- `learning/` module - All stubs
- `pipeline/` module - All stubs
- `kotlin/analysis/` module - All stubs
- `ui/` module - All stubs

**Action**: Keep but add clear documentation that these are intentional stubs

### 5. TODO Comments
Many TODO comments throughout codebase:
- **Action**: Review and prioritize, or move to issue tracker

### 6. Unused Imports
- **Action**: Run IDE cleanup or use tool to remove unused imports

## 📝 Low Priority Cleanup

### 7. Documentation Consolidation
- Multiple documentation files that might overlap
- **Action**: Review and consolidate if needed

### 8. Build Artifacts
- `build/` directories in submodules
- **Action**: Already in .gitignore, but can clean manually

## ✅ Cleanup Actions

### Completed ✅
1. ✅ **Deleted** `src/main/kotlin/com/alyk/ai/koog/Main.kt` - Old sketch code no longer needed
2. ✅ **Deleted** `launcher/src/main/kotlin/gui/components/OllamaMonitorAndControl.kt.backup` - Backup file
3. ✅ **Deleted** empty `mcp/` directory - Unused empty directory

### Remaining Actions
1. ⚠️ **`src/` directory** - Directory structure remains but is empty (can be manually removed if desired)
2. Review stub implementations - Keep for now as they're intentional placeholders
3. Consolidate TODO comments - Consider moving to issue tracker

### Future Actions (Requires Review)
1. Review stub implementations - decide which to keep vs remove
2. Consolidate TODO comments into issue tracker
3. Review and consolidate documentation
4. Remove unused module dependencies
