# Archived Documentation

This directory contains historical documentation that is no longer current but is preserved for reference.

## Why Documents Are Archived

Documents are moved here when they:

1. **Describe removed features** - Functionality that no longer exists
2. **Contain outdated architecture** - Superseded by new implementations
3. **Are module-specific** - Belong to other modules (e.g., i2vision-mcp)
4. **Are historical plans** - Completed features with implementation notes
5. **Are duplicates** - Consolidated into other documents

## Archived Documents

### MCP Server Documentation (Moved from guides/)

These documents describe the Kotlin MCP server implementation, not the VSCode extension:

- **presets.md** - Kotlin MCP server preset configuration
- **custom-tools.md** - Kotlin MCP server tool registration
- **mcp-integration.md** - MCP server implementation details
- **mcp-tools.md** - MCP tool handler implementation

**Action**: These belong in the `i2vision-mcp` module documentation.

### Historical Implementation Plans

Completed features with historical implementation notes:

- **deepseek-stream-fix-plan.md** - DeepSeek streaming fix (completed)
- **fix_mode_implementation.md** - Fix mode feature (completed)
- **progress-thinking-stream-render.md** - Thinking stream rendering (completed)
- **refactor-plan.md** - Superseded by `implementation-plan-tool-call-aggregation.md`

**Action**: Keep for historical reference if needed, otherwise delete.

### Outdated Architecture

Documents describing old architecture:

- **SETTINGS_GUIDE.md** - Old SettingsPanel architecture (superseded by MVVM)
  - See: `migrations/settings-mvvm.md` for migration guide
  - See: `guides/settings-mvvm.md` for current architecture

**Action**: Keep until MVVM migration is complete, then archive or delete.

## Current Documentation

For current documentation, see:

- **[README.md](../README.md)** - Documentation index
- **[Architecture](../architecture/)** - System architecture
- **[Concepts](../concepts/)** - Key concepts
- **[Guides](../guides/)** - User guides
- **[Reference](../reference/)** - API reference
- **[Migrations](../migrations/)** - Migration guides
- **[Tests](../tests/)** - Testing documentation

## Restoration

If you need to restore an archived document:

1. Verify the content is still relevant
2. Update for current architecture
3. Move back to appropriate directory
4. Update links and references

## Deprecation Policy

Documents are deprecated when:

- They describe functionality removed > 3 months ago
- They conflict with current implementation
- They cause confusion for users/developers

Deprecated documents are:
1. Moved to `archive/`
2. Marked with deprecation notice at top
3. Linked from this README
4. Deleted after 6 months if no restoration request

## Questions

If you have questions about archived documents or believe something was archived in error, please:

1. Check current documentation first
2. Review the deprecation reason above
3. Open an issue to discuss restoration
