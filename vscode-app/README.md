# i2-Vision VSCode Extension

**Version:** 1.0.0  
**Publisher:** i2vision  
**License:** MIT

A powerful VSCode extension that brings i2-Vision's architecture discovery and analysis capabilities directly into your IDE.

## ✨ Features

### 🌳 Interactive Tree View
- **Project Explorer**: Browse discovered components organized by architecture layers
- **Template Gallery**: Access project templates for quick scaffolding
- **Quick Settings**: Direct access to extension and CLI configuration
- **Documentation Hub**: Links to guides and references

### 🔌 CLI Integration
- **Real-time Discovery**: Connect to i2vision CLI for live codebase analysis
- **Architecture Analysis**: Detect and visualize architecture violations
- **Project Creation**: Scaffold new projects from templates
- **Context Extraction**: Get VSLF context for any file

### 🎯 Smart Commands
- `i2vision.refreshTree` - Refresh discovery data
- `i2vision.createProject` - Create new projects
- `i2vision.openProject` - Open existing projects
- `i2vision.showDiscovery` - View discovery results
- `i2vision.analyzeArchitecture` - Run architecture analysis
- `i2vision.viewDocumentation` - Access documentation

### 📊 Architecture Visualization
- **Layer-based Grouping**: Components organized by architectural layer
- **Dependency Tracking**: Visualize component relationships
- **Violation Detection**: Highlight architecture rule violations
- **Metrics Display**: View complexity, coupling, and cohesion

## 🚀 Quick Start

### Installation

1. **Clone the repository:**
   ```bash
   git clone https://github.com/i2vision/i2-vision.git
   cd i2-vision/vscode-app
   ```

2. **Install dependencies:**
   ```bash
   npm install
   ```

3. **Compile TypeScript:**
   ```bash
   npm run compile
   ```

4. **Launch Extension:**
   - Open `vscode-app` folder in VSCode
   - Press `F5` to launch Extension Development Host
   - Look for the **i2-Vision Explorer** icon in the Activity Bar

### Building the CLI Backend (Optional)

For full functionality with real discovery data:

```bash
# From project root
cd D:/proj/AI/i2-vision

# Build CLI module
./gradlew :i2vision-cli:build

# Install to system
./gradlew :i2vision-cli:installDist

# Add to PATH (Windows)
$env:PATH += ";D:\proj\AI\i2-vision\i2vision-cli\build\install\i2vision-cli\bin"
```

## 📁 Project Structure

```
vscode-app/
├── src/
│   ├── extension.ts              # Extension entry point
│   ├── cliIntegration.ts         # CLI wrapper and interfaces
│   ├── treeViewProvider.ts       # Tree view data provider
│   └── test/
│       ├── suite/
│       │   ├── index.ts          # Test runner
│       │   └── extension.test.ts # Unit tests
│       └── runTest.ts            # Test entry point
├── resources/
│   ├── i2vision-icon.svg         # Extension icon
│   ├── refresh.svg               # Refresh icon
│   └── open.svg                  # Open icon
├── out/                          # Compiled JavaScript
├── .vscode/
│   ├── launch.json               # Debug configurations
│   └── tasks.json                # Build tasks
├── package.json                  # Extension manifest
├── tsconfig.json                 # TypeScript config
├── README.md                     # This file
├── QUICKSTART.md                 # Quick start guide
├── INTEGRATION_GUIDE.md          # CLI integration docs
├── CHANGELOG.md                  # Version history
├── .vscodeignore                 # Package exclusions
└── .gitignore                    # Git ignore rules
```

## 🎨 Tree View Structure

```
i2-Vision Explorer
├── 📁 Projects
│   └── i2-vision v1.0.0
│       ├── 🏗️ presentation (1)
│       │   └── 📦 vscode-app
│       ├── 🏗️ application (2)
│       │   ├── 📦 app
│       │   └── 📄 DiscoveryService
│       ├── 🏗️ infrastructure (2)
│       │   ├── 📦 storage-core
│       │   └── 📦 index-provider
│       └── ⚠️ Violations (0)
├── 📁 Templates
│   ├── 📄 Basic Template (basic)
│   ├── 📄 Advanced Template (advanced)
│   ├── 📄 Enterprise Template (enterprise)
│   └── 📄 Microservice Template (microservice)
├── ⚙️ Settings
│   ├── Extension Settings
│   ├── CLI Configuration
│   └── Architecture Rules
└── 📖 Documentation
    ├── Quick Start Guide
    ├── Architecture Documentation
    ├── API Reference
    └── GitHub Repository
```

## 🔧 Commands

### Available Commands

| Command | Description | How to Access |
|---------|-------------|---------------|
| `i2vision.helloWorld` | Display greeting | Command Palette |
| `i2vision.refreshTree` | Refresh tree view | Tree title bar / Command Palette |
| `i2vision.createProject` | Create new project | Command Palette |
| `i2vision.openProject` | Open project/file | Tree item context menu |
| `i2vision.showDiscovery` | Display discovery results | Command Palette |
| `i2vision.analyzeArchitecture` | Run architecture analysis | Command Palette |
| `i2vision.viewDocumentation` | View documentation | Command Palette |

### Using Commands

**Command Palette:**
1. Press `Ctrl+Shift+P` (Windows/Linux) or `Cmd+Shift+P` (macOS)
2. Type `i2-Vision` to filter commands
3. Select desired command

**Tree View:**
- Click items to expand/collapse
- Right-click for context menu
- Click refresh icon in title bar

## 🧪 Development

### Build Commands

```bash
# Compile TypeScript
npm run compile

# Watch for changes
npm run watch

# Run linter
npm run lint

# Run tests
npm test

# Package extension
vsce package
```

### Debugging

1. **Open vscode-app in VSCode**
2. **Press F5** to launch Extension Development Host
3. **Set breakpoints** in TypeScript files
4. **Debug** in the new VSCode window

### Testing

```bash
# Run all tests
npm test

# Test specific suite
npm test -- --grep "CLI Integration"
```

**Test Coverage:**
- CLI integration methods
- Tree provider functionality
- Command registration
- Data structure validation

## 📊 Architecture

### Component Diagram

```
┌─────────────────────────────────────────────────┐
│           VSCode Extension Host                 │
├─────────────────────────────────────────────────┤
│  extension.ts                                   │
│  - Command registration                         │
│  - Event handling                               │
│  - Output channel                               │
├─────────────────────────────────────────────────┤
│  treeViewProvider.ts                            │
│  - TreeDataProvider implementation              │
│  - Dynamic tree building                        │
│  - Caching layer                                │
├─────────────────────────────────────────────────┤
│  cliIntegration.ts                              │
│  - CLI wrapper                                  │
│  - Mock data fallback                           │
│  - Interface definitions                        │
└─────────────────────────────────────────────────┘
                        │
                        │ CLI commands (JSON)
                        ▼
┌─────────────────────────────────────────────────┐
│         i2vision CLI Backend                    │
│  - Code discovery                               │
│  - Architecture analysis                        │
│  - Template management                          │
└─────────────────────────────────────────────────┘
```

### Data Flow

1. **User Action** → Extension Command
2. **Command** → CLI Integration
3. **CLI** → Execute i2vision-cli
4. **Backend** → Return JSON
5. **CLI** → Parse to TypeScript interfaces
6. **Tree Provider** → Build tree items
7. **VSCode** → Render tree view

## 🔌 API Reference

### I2VisionCLI Class

```typescript
class I2VisionCLI {
  constructor(workspaceRoot: string, outputChannel?: OutputChannel)
  
  // Discovery
  runDiscovery(): Promise<DiscoveryResult>
  
  // Context
  getContext(filePath: string): Promise<VSLFContext>
  
  // Templates
  listTemplates(): Promise<TemplateInfo[]>
  createProject(template: string, name: string, variables: Record<string, string>): Promise<boolean>
  
  // Analysis
  analyzeViolations(): Promise<Violation[]>
  
  // Utility
  isAvailable(): Promise<boolean>
}
```

### I2VisionTreeProvider Class

```typescript
class I2VisionTreeProvider implements TreeDataProvider<I2VisionTreeItem> {
  constructor(workspaceRoot: string, outputChannel?: OutputChannel)
  
  // TreeDataProvider methods
  getTreeItem(element: I2VisionTreeItem): TreeItem
  getChildren(element?: I2VisionTreeItem): Promise<I2VisionTreeItem[]>
  
  // Custom methods
  refresh(): void
  getDiscoveryCache(): DiscoveryResult | null
  getTemplatesCache(): TemplateInfo[] | null
}
```

## 📦 Dependencies

### Runtime
- **VSCode API**: ^1.90.0
- **Node.js**: 18+
- **TypeScript**: 5.4+

### Development
- **@types/vscode**: ^1.90.0
- **@types/node**: ^20.0.0
- **@types/mocha**: ^10.0.0
- **eslint**: ^8.57.0
- **@vscode/test-electron**: ^2.3.9

## 🎯 Use Cases

### 1. Explore Project Architecture
```
1. Open i2-Vision Explorer
2. Click "Projects" folder
3. Expand layers to see components
4. Click components to open files
```

### 2. Create New Project
```
1. Press Ctrl+Shift+P
2. Run "i2-Vision: Create New Project"
3. Select template
4. Enter project name and variables
5. Project is created and opened
```

### 3. Analyze Architecture
```
1. Press Ctrl+Shift+P
2. Run "i2-Vision: Analyze Architecture"
3. View violations in Output panel
4. Fix violations in code
5. Re-run analysis to verify
```

### 4. Browse Templates
```
1. Open i2-Vision Explorer
2. Click "Templates" folder
3. Expand templates to see variables
4. Select template for project creation
```

## ⚠️ Known Limitations

1. **Mock Data**: Without CLI backend, only mock data is shown
2. **File Opening**: Requires workspace folder to be open
3. **Real-time Updates**: Manual refresh required for changes
4. **LSP Features**: Not yet implemented (planned)

## 🚧 Roadmap

### v1.1.0 (Next)
- [ ] Webview architecture diagrams
- [ ] Status bar integration
- [ ] File system integration improvements

### v1.2.0
- [ ] LSP integration for semantic navigation
- [ ] CodeLens for layer information
- [ ] Hover tooltips with VSLF context

### v2.0.0
- [ ] AI chat panel with codebase context
- [ ] Real-time background analysis
- [ ] Quick fixes for violations

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests: `npm test`
5. Submit a pull request

## 📄 License

MIT License - see [LICENSE](../LICENSE) file for details.

## 🆘 Support

- **Documentation**: See [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md)
- **Quick Start**: See [QUICKSTART.md](QUICKSTART.md)
- **Issues**: Open an issue on GitHub
- **Discussions**: GitHub Discussions tab

## 🙏 Acknowledgments

- Built with [VSCode Extension API](https://code.visualstudio.com/api)
- Powered by [i2vision CLI](../i2vision-cli)
- Icons from [VSCode Codicons](https://github.com/microsoft/vscode-codicons)

---

**Enjoy exploring your architecture! 🚀**
