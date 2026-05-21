# File System Integration Guide

## 🎯 Overview

The i2-Vision extension now includes **comprehensive file system integration** that enables real-time project scanning, file watching, and direct file operations from the VSCode interface.

---

## ✨ New Features

### 1. **Real File System Scanning**
- Scans Gradle/Kotlin projects directly from disk
- Extracts module information from `settings.gradle.kts`
- Parses `build.gradle.kts` for dependencies
- Identifies source files and their metadata

### 2. **File Watching**
- Real-time detection of file changes
- Automatic tree view refresh on file modifications
- Support for create, change, and delete events
- Configurable file patterns (`.kt`, `.java`, `.gradle`, `.xml`)

### 3. **Direct File Operations**
- Open files from tree view
- Read/write file contents
- Check file existence
- Get file statistics (size, modification date)

### 4. **Enhanced Tree View**
- Toggle between CLI discovery and file system scan modes
- View actual project structure from disk
- Navigate to source files directly
- See file metadata (package, class, layer)

---

## 📁 Architecture

### Component Diagram

```
┌─────────────────────────────────────────────────┐
│           VSCode Extension Host                 │
├─────────────────────────────────────────────────┤
│  extension.ts                                   │
│  - Command handlers                             │
│  - File system integration                      │
│  - Tree provider initialization                 │
├─────────────────────────────────────────────────┤
│  treeViewProvider.ts                            │
│  - Dual mode: CLI or File System                │
│  - File system cache                            │
│  - Tree building from FS data                   │
├─────────────────────────────────────────────────┤
│  fileSystemIntegration.ts                       │
│  - FileSystemIntegration class                  │
│  - FSUtils utilities                            │
│  - File watcher                                 │
│  - Project scanner                              │
└─────────────────────────────────────────────────┘
```

### Data Flow

```
User Action → Command → FileSystemIntegration
                              ↓
                    Scan Workspace
                              ↓
                    Parse settings.gradle.kts
                              ↓
                    Extract modules
                              ↓
                    Scan source directories
                              ↓
                    Extract metadata (package, class, layer)
                              ↓
                    Build FileSystemProject
                              ↓
                    Update Tree View
                              ↓
                    Start File Watching
```

---

## 🔧 Components

### 1. FileSystemIntegration Class

**Location:** `src/fileSystemIntegration.ts`  
**Size:** ~600 lines

#### Key Methods

```typescript
class FileSystemIntegration {
  // Scanning
  scanWorkspace(): Promise<FileSystemProject | null>
  
  // File Operations
  readFile(filePath: string): Promise<string | null>
  writeFile(filePath: string, content: string): Promise<boolean>
  fileExists(filePath: string): Promise<boolean>
  getFileStats(filePath: string): Promise<...>
  
  // File Watching
  startFileWatching(): void
  stopFileWatching(): void
  
  // Cache Management
  getProjectCache(): FileSystemProject | null
  clearCache(): void
}
```

#### Interfaces

```typescript
interface FileSystemProject {
  name: string;
  rootPath: string;
  buildFile: string;
  settingsFile?: string;
  modules: FileSystemModule[];
  sourceFiles: SourceFile[];
  lastModified: Date;
}

interface FileSystemModule {
  name: string;
  path: string;
  buildFile: string;
  sourceDirectories: string[];
  dependencies: ModuleDependency[];
}

interface SourceFile {
  path: string;
  relativePath: string;
  type: 'kotlin' | 'java' | 'xml' | 'gradle' | 'other';
  size: number;
  lastModified: Date;
  packageName?: string;
  className?: string;
  componentType?: 'class' | 'interface' | 'object' | 'enum' | 'function';
  layer?: string;
}
```

### 2. Enhanced TreeViewProvider

**Location:** `src/treeViewProvider.ts`  
**Updates:** Added file system integration

#### New Methods

```typescript
class I2VisionTreeProvider {
  // File system mode toggle
  toggleFileSystemScan(): void
  isFileSystemScanEnabled(): boolean
  
  // Cache access
  getFileSystemCache(): FileSystemProject | null
  
  // Tree building
  buildFileSystemTree(project: FileSystemProject): I2VisionTreeItem[]
  createFileItem(file: SourceFile): I2VisionTreeItem
}
```

#### Dual Mode Operation

```typescript
async getProjects(element: I2VisionTreeItem): Promise<I2VisionTreeItem[]> {
  // Try file system scan first if enabled
  if (this.useFileSystemScan) {
    const fsProject = await this.fileSystem.scanWorkspace();
    if (fsProject) {
      return this.buildFileSystemTree(fsProject);
    }
  }
  
  // Fall back to CLI discovery
  const discovery = await this.cli.runDiscovery();
  return this.buildDiscoveryTree(discovery);
}
```

### 3. Updated Extension Entry Point

**Location:** `src/extension.ts`  
**New Commands:**

| Command | Description |
|---------|-------------|
| `i2vision.toggleFileSystemScan` | Toggle between CLI and FS modes |
| `i2vision.scanWorkspace` | Manual workspace scan |
| `i2vision.showFileInfo` | Display file metadata |
| `i2vision.openContainingFolder` | Reveal file in OS file explorer |

---

## 🚀 Usage

### Enable File System Scanning

#### Method 1: Tree View Settings
1. Open i2-Vision Explorer
2. Click on **Settings** folder
3. Click **File System Scan**
4. Tree will refresh and show actual project structure

#### Method 2: Command Palette
```bash
Ctrl+Shift+P → i2-Vision: Toggle File System Scan
```

#### Method 3: Manual Scan
```bash
Ctrl+Shift+P → i2-Vision: Scan Workspace
```

### View File Information

#### From Tree View
1. Right-click on any file or component
2. Select **Show File Information**
3. Webview panel opens with metadata

#### Information Displayed
- File path and type
- File size
- Last modified date
- Class/interface name (if applicable)
- Package name
- Architecture layer
- Dependencies

### Open Files

#### Direct Open
- Click on file item in tree view
- File opens in editor

#### Context Menu
1. Right-click on file
2. Select **Open Project** (opens file)

#### Reveal in OS
1. Right-click on file
2. Select **Open Containing Folder**
3. File explorer opens with file selected

---

## 📊 File System Scan Details

### What Gets Scanned

#### 1. Project Root
- `settings.gradle.kts` or `settings.gradle`
- `build.gradle.kts` or `build.gradle`
- Project name and version

#### 2. Modules
- All included modules from settings file
- Module paths and build files
- Module dependencies

#### 3. Source Directories
```
src/main/kotlin
src/main/java
src/test/kotlin
src/test/java
src/commonMain/kotlin
src/jvmMain/kotlin
```

#### 4. Source Files
- Kotlin files (`.kt`, `.kts`)
- Java files (`.java`)
- XML files (`.xml`)
- Gradle files (`.gradle`, `.kts`)

#### 5. Metadata Extraction
- **Package name** from `package` declaration
- **Class name** from `class/interface/object/enum` declaration
- **Component type** (class, interface, object, enum, function)
- **Layer** from path analysis:
  - `presentation` - contains `/presentation/`, `/ui/`, `/gui/`
  - `application` - contains `/application/`, `/core/`
  - `infrastructure` - contains `/infrastructure/`, `/storage/`, `/index/`
  - `domain` - contains `/domain/`

### Parsing Logic

#### Settings File Parser
```kotlin
// settings.gradle.kts
include("architecture-types")
include("vslfc-core")
include("vscode-app")
include("app:core")
```

Extracted:
- Module names: `architecture-types`, `vslfc-core`, `vscode-app`, `core`
- Module paths: Full paths based on project root

#### Build File Parser
```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":storage-core"))
    api("com.example:library:1.0.0")
    testImplementation("junit:junit:4.13")
}
```

Extracted:
- Project dependencies: `storage-core`
- External dependencies: `com.example:library:1.0.0`
- Test dependencies: `junit:junit:4.13`

#### Source File Parser
```kotlin
// DiscoveryService.kt
package com.i2vision.discovery

class DiscoveryService {
    // ...
}
```

Extracted:
- Package: `com.i2vision.discovery`
- Class: `DiscoveryService`
- Type: `class`
- Layer: `application` (from path)

---

## 🔍 File Watching

### Configuration

**Watched Patterns:**
```
**/*.{kt,kts,java,gradle,xml}
```

**Events Tracked:**
- File created
- File changed
- File deleted

### Automatic Refresh

When a file change is detected:
1. Event fired from `FileSystemIntegration`
2. Tree provider receives event
3. Tree view refreshes automatically
4. User sees updated structure

### Manual Control

```typescript
// Start watching
fileSystem.startFileWatching();

// Stop watching
fileSystem.stopFileWatching();

// Check if watching
const isWatching = fileSystem.isWatching();
```

---

## 📈 Performance

### Caching Strategy

```typescript
// Project cache
private projectCache: FileSystemProject | null = null;

// Only scan if cache is empty or invalidated
async scanWorkspace(): Promise<FileSystemProject | null> {
  if (this.projectCache) {
    return this.projectCache;
  }
  // Perform scan...
}

// Clear cache on refresh
clearCache(): void {
  this.projectCache = null;
}
```

### Optimization Techniques

1. **Lazy Loading**: Children loaded on-demand when tree items expanded
2. **Directory Skipping**: Excludes `build`, `.gradle`, `node_modules`, `.git`, `out`
3. **File Type Filtering**: Only processes relevant file types
4. **Event Debouncing**: File change events can be debounced to prevent excessive refreshes

### Memory Management

```typescript
// Dispose resources on extension deactivation
dispose(): void {
  this.stopFileWatching();
  this._onFileChange.dispose();
}
```

---

## 🧪 Testing

### Manual Testing Checklist

#### 1. File System Scanning
- [ ] Open workspace with Gradle project
- [ ] Run `i2-Vision: Scan Workspace`
- [ ] Verify modules are detected
- [ ] Verify source files are listed
- [ ] Check file metadata accuracy

#### 2. File Watching
- [ ] Enable file system scan mode
- [ ] Create new Kotlin file
- [ ] Verify tree updates automatically
- [ ] Modify existing file
- [ ] Verify tree refreshes
- [ ] Delete file
- [ ] Verify file removed from tree

#### 3. File Operations
- [ ] Click on file in tree
- [ ] Verify file opens in editor
- [ ] Right-click → Show File Information
- [ ] Verify metadata displayed correctly
- [ ] Right-click → Open Containing Folder
- [ ] Verify file explorer opens

#### 4. Toggle Mode
- [ ] Switch to file system scan mode
- [ ] Verify tree shows actual files
- [ ] Switch back to CLI mode
- [ ] Verify tree shows discovery data
- [ ] Verify both modes work correctly

### Expected Results

**File System Scan Output:**
```
[12:34:56] FS: Scanning workspace for projects...
[12:34:57] FS: Found settings file: D:/proj/AI/i2-vision/settings.gradle.kts
[12:34:58] FS: Project scanned: i2-vision, 15 modules, 234 source files
```

**Tree View Structure:**
```
i2-Vision Projects
├── i2-vision (15 modules)
│   ├── architecture-types
│   │   └── src/main/kotlin
│   │       └── ArchitectureType.kt
│   ├── vslfc-core
│   │   └── src/main/kotlin
│   │       └── VSLFContext.kt
│   ├── vscode-app
│   │   └── src
│   │       ├── extension.ts
│   │       ├── treeViewProvider.ts
│   │       └── fileSystemIntegration.ts
│   └── ...
```

---

## 🛠️ Troubleshooting

### Issue: No Modules Detected

**Symptoms:** Tree shows empty projects folder

**Solutions:**
1. Verify `settings.gradle.kts` exists in workspace root
2. Check file contains `include(...)` statements
3. Run `i2-Vision: Scan Workspace` command
4. Check output channel for errors

### Issue: Files Not Opening

**Symptoms:** Click on file, nothing happens

**Solutions:**
1. Verify file path is correct
2. Check file exists on disk
3. Ensure workspace folder is open
4. Check output channel for error messages

### Issue: Tree Not Refreshing

**Symptoms:** File changes not reflected in tree

**Solutions:**
1. Verify file system scan mode is enabled
2. Check file matches watched patterns
3. Manually refresh tree (click refresh icon)
4. Restart extension development host

### Issue: Incorrect Layer Detection

**Symptoms:** Files assigned to wrong architecture layer

**Solutions:**
1. Verify file path contains layer indicators
2. Check path patterns in `extractSourceMetadata()`
3. Add custom layer detection rules if needed

---

## 📝 Examples

### Example 1: Scan Workspace

```typescript
// Command: i2-Vision: Scan Workspace
// Output:
[12:34:56] FS: Scanning workspace for projects...
[12:34:57] FS: Found settings file: D:/proj/AI/i2-vision/settings.gradle.kts
[12:34:58] FS: Project scanned: i2-vision
[12:34:58] FS:   Modules: 15
[12:34:58] FS:   Source files: 234
```

### Example 2: Toggle File System Scan

```typescript
// Command: i2-Vision: Toggle File System Scan
// Result:
// Tree switches from CLI discovery to actual file system structure
// Information message: "File system scan enabled"
```

### Example 3: View File Info

```typescript
// Right-click on file → Show File Information
// Webview displays:

**File Information**

**Path:** src/extension.ts
**Type:** kotlin
**Size:** 21.1 KB
**Modified:** 1/1/2026 12:00:00 PM
**Class:** Extension
**Package:** com.i2vision.extension
**Layer:** presentation
```

### Example 4: File Change Detection

```typescript
// User creates new file: src/NewService.kt
// Output channel:
[12:35:00] FS: File created: D:/proj/AI/i2-vision/src/NewService.kt
[12:35:00] TreeProvider: File change detected: created
// Tree automatically refreshes and shows new file
```

---

## 🎯 Integration with CLI

### Hybrid Mode

The extension supports **hybrid operation**:

1. **CLI Mode** (default):
   - Uses `i2vision-cli` for discovery
   - Shows architecture analysis
   - Displays violations
   - Template browsing

2. **File System Mode**:
   - Scans actual files from disk
   - Shows real project structure
   - Enables file operations
   - Real-time updates

3. **Switching Modes**:
   ```typescript
   // Toggle via command
   treeProvider.toggleFileSystemScan();
   
   // Check current mode
   const isFSMode = treeProvider.isFileSystemScanEnabled();
   ```

### Fallback Strategy

```typescript
async getProjects(): Promise<I2VisionTreeItem[]> {
  // Try file system first if enabled
  if (this.useFileSystemScan) {
    const fsProject = await this.fileSystem.scanWorkspace();
    if (fsProject) {
      return this.buildFileSystemTree(fsProject);
    }
  }
  
  // Fall back to CLI discovery
  const discovery = await this.cli.runDiscovery();
  return this.buildDiscoveryTree(discovery);
}
```

---

## 📊 Comparison: CLI vs File System

| Feature | CLI Mode | File System Mode |
|---------|----------|------------------|
| **Data Source** | i2vision-cli backend | Direct file scanning |
| **Project Structure** | Abstracted components | Actual files |
| **Real-time Updates** | Manual refresh | Automatic (file watcher) |
| **File Operations** | Limited | Full support |
| **Architecture Analysis** | ✅ Full | ❌ Limited |
| **Violations** | ✅ Displayed | ❌ Not shown |
| **Templates** | ✅ Available | ❌ Not available |
| **Dependencies** | Analyzed | Parsed from build files |
| **Performance** | Fast (cached) | Moderate (scanning) |

---

## 🚀 Next Steps

### Immediate Enhancements
1. **Incremental Scanning**: Only scan changed files
2. **Parallel Processing**: Scan modules concurrently
3. **Smart Caching**: Cache invalidation based on timestamps

### Short-Term Features
1. **File Search**: Quick open files by name
2. **Symbol Navigation**: Go to class/function definition
3. **Code Lens**: Show layer info in editor

### Long-Term Vision
1. **LSP Integration**: Full language server protocol support
2. **Real-time Analysis**: Background architecture checking
3. **Refactoring Tools**: Move class, extract module

---

## 📚 Related Documentation

- [README.md](README.md) - Extension overview
- [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md) - CLI integration
- [QUICKSTART.md](QUICKSTART.md) - Getting started
- [IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md) - Implementation details

---

**Happy exploring your file system! 🚀**
