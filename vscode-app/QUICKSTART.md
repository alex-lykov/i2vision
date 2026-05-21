# i2-Vision VSCode Extension - Quick Start Guide

## 🚀 Getting Started in 3 Steps

### Step 1: Install Dependencies
```bash
cd vscode-app
npm install
```

### Step 2: Compile TypeScript
```bash
npm run compile
```

### Step 3: Launch Extension Development Host
1. Open the `vscode-app` folder in VSCode
2. Press `F5` (or go to Run → Start Debugging)
3. A new VSCode window will open with the extension loaded
4. Look for the **i2-Vision Explorer** icon in the Activity Bar (left sidebar)

## 📁 Extension Structure

```
vscode-app/
├── src/
│   ├── extension.ts          # Main entry point
│   ├── treeViewProvider.ts   # Tree view data provider
│   └── test/                 # Test suite
│       ├── suite/
│       │   ├── index.ts      # Test runner
│       │   └── extension.test.ts  # Unit tests
│       └── runTest.ts        # Test entry point
├── resources/
│   ├── i2vision-icon.svg     # Extension icon
│   └── refresh.svg           # Refresh icon
├── out/                      # Compiled JavaScript (auto-generated)
├── .vscode/
│   ├── launch.json           # Debug configurations
│   └── tasks.json            # Build tasks
├── package.json              # Extension manifest
├── tsconfig.json             # TypeScript configuration
├── README.md                 # Full documentation
├── CHANGELOG.md              # Version history
├── .vscodeignore             # Files to exclude from package
└── .gitignore                # Git ignore rules
```

## 🎯 Available Commands

| Command | Description | How to Access |
|---------|-------------|---------------|
| `i2vision.helloWorld` | Display Hello World message | Command Palette |
| `i2vision.createProject` | Create new project | Command Palette |
| `i2vision.openProject` | Open existing project | Tree item context menu |
| `i2vision.refreshTree` | Refresh tree view | Tree view title bar |

## 🌳 Tree View Structure

```
i2-Vision Explorer
├── 📁 Projects
│   ├── 📄 My First Project
│   ├── 📄 Demo Application
│   └── 📄 Test Project
├── 📁 Templates
│   ├── 📄 Basic Template
│   ├── 📄 Advanced Template
│   └── 📄 Enterprise Template
├── ⚙️ Settings
└── 📖 Documentation
```

## 🛠 Development Commands

```bash
# Compile TypeScript
npm run compile

# Watch for changes (auto-compile)
npm run watch

# Run linter
npm run lint

# Run tests
npm test

# Package extension
vsce package
```

## 🐛 Debugging

### Launch Configurations
- **Run Extension**: Launches the extension in a new VSCode window
- **Extension Tests**: Runs the test suite

### Setting Breakpoints
1. Click in the gutter next to a line number in TypeScript files
2. Press `F5` to start debugging
3. The extension will pause at breakpoints

## ✅ Testing

### Unit Tests
```bash
npm test
```

Tests verify:
- Command registration
- Tree view provider
- Extension activation

### Manual Testing
1. Launch extension with `F5`
2. Click the i2-Vision icon in Activity Bar
3. Verify tree view appears with sample data
4. Click refresh button to reload tree
5. Right-click items for context menu

## 📦 Packaging for Distribution

```bash
# Install vsce globally
npm install -g vsce

# Create .vsix package
vsce package

# Install in VSCode
# Go to Extensions → ⋯ → Install from VSIX...
```

## 🔧 Configuration

### package.json Contributions
- **Views Container**: Activity bar icon
- **Views**: Tree view in sidebar
- **Commands**: Available actions
- **Menus**: Context menu items

### TypeScript Configuration
- Target: ES2022
- Module: CommonJS
- Strict mode: Enabled
- Source maps: Enabled

## 📝 Next Steps

1. **Connect to Backend**: Integrate with Kotlin application API
2. **Add Project Creation**: Implement actual project creation logic
3. **File System Integration**: Read real projects from disk
4. **Add More Commands**: Expand functionality
5. **Improve UI**: Add more icons and visual feedback

## 🆘 Troubleshooting

### Extension Not Appearing
- Check Activation Events in package.json
- Verify extension is enabled in Extensions view

### Tree View Empty
- Check TreeDataProvider implementation
- Verify getChildren() returns items
- Try clicking refresh button

### Compilation Errors
- Run `npm install` to ensure dependencies are installed
- Check TypeScript version compatibility
- Review tsconfig.json settings

### Tests Failing
- Ensure extension is compiled (`npm run compile`)
- Check test file paths in index.ts
- Verify VSCode version compatibility

## 📚 Resources

- [VSCode Extension API](https://code.visualstudio.com/api)
- [Tree View API](https://code.visualstudio.com/api/extension-guides/tree-view)
- [TypeScript Handbook](https://www.typescriptlang.org/docs/)

## 🎉 Success!

You now have a working VSCode extension with:
- ✅ Tree view in Activity Bar
- ✅ Custom data provider
- ✅ Commands and menus
- ✅ Test framework
- ✅ Debug configuration
- ✅ Build pipeline

Happy coding! 🚀
