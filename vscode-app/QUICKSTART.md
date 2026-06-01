# Quick Start: Using i2vision.init

## ✅ Command is Now Available

The `i2vision.init` command has been successfully added to the extension.

## How to Use

### Step 1: Reload VSCode Extension

After the extension rebuilds, you need to reload VSCode:

1. Press `Ctrl+Shift+P` (Windows/Linux) or `Cmd+Shift+P` (macOS)
2. Type: `Developer: Reload Window`
3. Press Enter

### Step 2: Run i2vision.init

**Method 1: Command Palette**
```
1. Press Ctrl+Shift+P
2. Type: "i2vision.init"
3. Select: "i2vision.init: Initialize VSLFC Structure (i2vision.init)"
4. Press Enter
```

**Method 2: Search by Title**
```
1. Press Ctrl+Shift+P
2. Type: "Initialize VSLFC"
3. Select the command
4. Press Enter
```

### Step 3: Verify

After running the command, check your project root:

```
your-project/
└── .vision-ai/          ← Should be created
    ├── .version
    ├── vision/
    ├── code/
    ├── logic/
    ├── structure/
    ├── flow/
    ├── data/
    ├── api/
    └── config/
        └── cli.yaml
```

## Troubleshooting

### Command Still Not Showing

If `i2vision.init` doesn't appear:

1. **Check extension is running**:
   - Look for "i2-Vision" in the Activity Bar (left sidebar)
   - Check Output panel → "i2-Vision" channel

2. **Verify package.json**:
   ```bash
   # In vscode-app directory
   type package.json | findstr "i2vision.init"
   ```
   Should show:
   - `"onCommand:i2vision.init"` in activationEvents
   - `"command": "i2vision.init"` in contributes.commands

3. **Rebuild extension**:
   ```bash
   cd D:\proj\AI\i2-vision\vscode-app
   npm run compile
   ```

4. **Check compiled output**:
   ```bash
   type out\extension.js | findstr "i2vision.init"
   ```

### Extension Not Activating

If the extension doesn't activate:

1. Open a project folder in VSCode
2. Check Output panel → Select "i2-Vision" from dropdown
3. Look for: "i2-Vision extension is now active"

### Permission Errors

If you see permission errors:

1. Make sure you have write access to the project directory
2. Run VSCode as Administrator (Windows) if needed
3. Check the Output channel for specific error messages

## What the Command Does

When you run `i2vision.init`:

1. ✅ Creates `.vision-ai` directory in project root
2. ✅ Creates 7 layer directories (vision, code, logic, structure, flow, data, api)
3. ✅ Generates agent config templates for each layer
4. ✅ Generates contract templates for each layer
5. ✅ Creates control-plane directories (config, clusters, overrides, etc.)
6. ✅ Creates CLI configuration file (`.vision-ai/config/cli.yaml`)
7. ✅ Creates requirements directory for vision layer
8. ✅ Writes version file (`.vision-ai/.version`)

## Next Steps

After initialization:

1. **Configure CLI**: Edit `.vision-ai/config/cli.yaml` with your CLI JAR path
2. **Customize Agents**: Edit layer-specific agent configs
3. **Define Contracts**: Update contract files for your project needs
4. **Add Requirements**: Create requirement files in `.vision-ai/vision/requirements/`

## Related Documentation

- 📄 [DOC-7: How to Run i2vision.init - User Guide](../backlog/docs/DOC-7.md)
- 📄 [RolloutManager Implementation](../storage-core/src/main/kotlin/com/i2vision/storage/impl/RolloutManager.kt)
