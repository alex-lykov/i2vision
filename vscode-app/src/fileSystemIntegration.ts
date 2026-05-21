/**
 * File System Integration for i2-Vision VSCode Extension
 * 
 * Provides file system operations for:
 * - Project discovery from workspace
 * - File reading and writing
 * - Component extraction from source files
 * - File watching for real-time updates
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';

/**
 * Project information discovered from file system
 */
export interface FileSystemProject {
  name: string;
  rootPath: string;
  buildFile: string;
  settingsFile?: string;
  modules: FileSystemModule[];
  sourceFiles: SourceFile[];
  lastModified: Date;
}

/**
 * Module information (Gradle subproject)
 */
export interface FileSystemModule {
  name: string;
  path: string;
  buildFile: string;
  sourceDirectories: string[];
  dependencies: ModuleDependency[];
}

/**
 * Module dependency
 */
export interface ModuleDependency {
  name: string;
  type: 'implementation' | 'api' | 'compileOnly' | 'runtimeOnly' | 'testImplementation';
  configuration?: string;
}

/**
 * Source file information
 */
export interface SourceFile {
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

/**
 * File change event
 */
export interface FileChangeEvent {
  type: 'created' | 'changed' | 'deleted';
  filePath: string;
  timestamp: Date;
}

/**
 * File System Scanner and Manager
 */
export class FileSystemIntegration {
  private workspaceRoot: string;
  private outputChannel: vscode.OutputChannel;
  private fileWatcher?: vscode.FileSystemWatcher;
  private projectCache: FileSystemProject | null = null;
  private _onFileChange = new vscode.EventEmitter<FileChangeEvent>();
  
  readonly onFileChange = this._onFileChange.event;

  constructor(workspaceRoot: string, outputChannel?: vscode.OutputChannel) {
    this.workspaceRoot = workspaceRoot;
    this.outputChannel = outputChannel || vscode.window.createOutputChannel('i2-Vision FS');
  }

  /**
   * Scan workspace for projects
   */
  async scanWorkspace(): Promise<FileSystemProject | null> {
    this.log('Scanning workspace for projects...');

    try {
      // Look for settings.gradle.kts or build.gradle.kts
      const settingsFile = await this.findSettingsFile(this.workspaceRoot);
      
      if (!settingsFile) {
        this.log('No Gradle project found in workspace');
        return null;
      }

      this.log(`Found settings file: ${settingsFile}`);

      // Parse settings file to get modules
      const modules = await this.parseSettingsFile(settingsFile);
      
      // Scan each module for source files
      const sourceFiles: SourceFile[] = [];
      for (const module of modules) {
        const moduleFiles = await this.scanModuleSources(module);
        sourceFiles.push(...moduleFiles);
      }

      const project: FileSystemProject = {
        name: path.basename(path.dirname(settingsFile)),
        rootPath: path.dirname(settingsFile),
        buildFile: await this.findBuildFile(this.workspaceRoot),
        settingsFile,
        modules,
        sourceFiles,
        lastModified: new Date()
      };

      this.projectCache = project;
      this.log(`Project scanned: ${project.name}, ${modules.length} modules, ${sourceFiles.length} source files`);
      
      return project;
    } catch (error: any) {
      this.log(`Error scanning workspace: ${error.message}`);
      return null;
    }
  }

  /**
   * Find settings.gradle.kts file
   */
  private async findSettingsFile(dir: string, maxDepth: number = 3): Promise<string | null> {
    const fs = require('fs');
    const path = require('path');
    
    let currentDir = dir;
    let depth = 0;

    while (depth < maxDepth) {
      const settingsKts = path.join(currentDir, 'settings.gradle.kts');
      const settingsGroovy = path.join(currentDir, 'settings.gradle');

      if (fs.existsSync(settingsKts)) {
        return settingsKts;
      }
      if (fs.existsSync(settingsGroovy)) {
        return settingsGroovy;
      }

      const parentDir = path.dirname(currentDir);
      if (parentDir === currentDir) {
        break;
      }
      currentDir = parentDir;
      depth++;
    }

    return null;
  }

  /**
   * Find build.gradle.kts file
   */
  private async findBuildFile(dir: string): Promise<string> {
    const fs = require('fs');
    const path = require('path');
    
    const buildKts = path.join(dir, 'build.gradle.kts');
    const buildGroovy = path.join(dir, 'build.gradle');

    if (fs.existsSync(buildKts)) {
      return buildKts;
    }
    if (fs.existsSync(buildGroovy)) {
      return buildGroovy;
    }

    return '';
  }

  /**
   * Parse settings.gradle.kts to extract modules
   */
  private async parseSettingsFile(settingsFile: string): Promise<FileSystemModule[]> {
    const fs = require('fs');
    const path = require('path');

    const content = fs.readFileSync(settingsFile, 'utf-8');
    const modules: FileSystemModule[] = [];

    // Extract module includes using regex
    // Matches: include("module-name") or include("parent:child")
    const includeRegex = /include\s*\(\s*["']([^"']+)["']\s*\)/g;
    let match;

    while ((match = includeRegex.exec(content)) !== null) {
      const modulePath = match[1];
      const moduleName = modulePath.split(':').pop() || modulePath;
      const moduleDir = path.join(path.dirname(settingsFile), ...modulePath.split(':'));

      const buildFile = await this.findBuildFile(moduleDir);
      const sourceDirs = await this.findSourceDirectories(moduleDir);
      const dependencies = buildFile ? await this.parseBuildFile(buildFile) : [];

      modules.push({
        name: moduleName,
        path: moduleDir,
        buildFile,
        sourceDirectories: sourceDirs,
        dependencies
      });
    }

    return modules;
  }

  /**
   * Find source directories in a module
   */
  private async findSourceDirectories(modulePath: string): Promise<string[]> {
    const fs = require('fs');
    const path = require('path');

    const sourceDirs: string[] = [];
    const commonDirs = [
      'src/main/kotlin',
      'src/main/java',
      'src/test/kotlin',
      'src/test/java',
      'src/commonMain/kotlin',
      'src/jvmMain/kotlin'
    ];

    for (const dir of commonDirs) {
      const fullPath = path.join(modulePath, dir);
      if (fs.existsSync(fullPath)) {
        sourceDirs.push(fullPath);
      }
    }

    return sourceDirs;
  }

  /**
   * Parse build.gradle.kts to extract dependencies
   */
  private async parseBuildFile(buildFile: string): Promise<ModuleDependency[]> {
    const fs = require('fs');
    const dependencies: ModuleDependency[] = [];

    try {
      const content = fs.readFileSync(buildFile, 'utf-8');

      // Extract dependencies
      // Matches: implementation("group:artifact:version") or api(project(":module"))
      const depRegex = /(implementation|api|compileOnly|runtimeOnly|testImplementation)\s*\(\s*["']([^"']+)["']\s*\)/g;
      let match;

      while ((match = depRegex.exec(content)) !== null) {
        const type = match[1] as ModuleDependency['type'];
        const depString = match[2];

        // Check if it's a project dependency
        if (depString.startsWith('project(')) {
          const projectMatch = depString.match(/project\s*\(\s*["']:?([^"']+)["']\s*\)/);
          if (projectMatch) {
            dependencies.push({
              name: projectMatch[1],
              type,
              configuration: 'project'
            });
          }
        } else {
          // Regular dependency
          dependencies.push({
            name: depString,
            type
          });
        }
      }
    } catch (error: any) {
      this.log(`Error parsing build file: ${error.message}`);
    }

    return dependencies;
  }

  /**
   * Scan module for source files
   */
  private async scanModuleSources(module: FileSystemModule): Promise<SourceFile[]> {
    const fs = require('fs');
    const path = require('path');
    const sourceFiles: SourceFile[] = [];

    for (const sourceDir of module.sourceDirectories) {
      if (fs.existsSync(sourceDir)) {
        const files = await this.scanDirectory(sourceDir, sourceDir);
        sourceFiles.push(...files);
      }
    }

    return sourceFiles;
  }

  /**
   * Recursively scan directory for source files
   */
  private async scanDirectory(dir: string, baseDir: string): Promise<SourceFile[]> {
    const fs = require('fs');
    const path = require('path');
    const files: SourceFile[] = [];

    try {
      const entries = fs.readdirSync(dir, { withFileTypes: true });

      for (const entry of entries) {
        const fullPath = path.join(dir, entry.name);

        if (entry.isDirectory()) {
          // Skip common non-source directories
          if (['build', '.gradle', 'node_modules', '.git', 'out'].includes(entry.name)) {
            continue;
          }
          const subFiles = await this.scanDirectory(fullPath, baseDir);
          files.push(...subFiles);
        } else if (entry.isFile()) {
          const ext = path.extname(entry.name).toLowerCase();
          const fileType = this.getFileType(ext);

          if (fileType !== 'other' || ext === '.kts' || ext === '.gradle') {
            const stats = fs.statSync(fullPath);
            const relativePath = path.relative(baseDir, fullPath);
            
            let sourceFile: SourceFile = {
              path: fullPath,
              relativePath,
              type: fileType,
              size: stats.size,
              lastModified: stats.mtime
            };

            // Extract metadata for Kotlin/Java files
            if (fileType === 'kotlin' || fileType === 'java') {
              const metadata = await this.extractSourceMetadata(fullPath, fileType);
              sourceFile = { ...sourceFile, ...metadata };
            }

            files.push(sourceFile);
          }
        }
      }
    } catch (error: any) {
      this.log(`Error scanning directory ${dir}: ${error.message}`);
    }

    return files;
  }

  /**
   * Get file type from extension
   */
  private getFileType(ext: string): SourceFile['type'] {
    const typeMap: Record<string, SourceFile['type']> = {
      '.kt': 'kotlin',
      '.kts': 'kotlin',
      '.java': 'java',
      '.xml': 'xml',
      '.gradle': 'gradle'
    };
    return typeMap[ext] || 'other';
  }

  /**
   * Extract metadata from source file
   */
  private async extractSourceMetadata(filePath: string, type: 'kotlin' | 'java'): Promise<Partial<SourceFile>> {
    const fs = require('fs');
    const path = require('path');

    try {
      const content = fs.readFileSync(filePath, 'utf-8');
      const relativePath = path.relative(this.workspaceRoot, filePath);

      // Determine layer from path
      let layer: string | undefined;
      if (relativePath.includes('/presentation/') || relativePath.includes('/ui/') || relativePath.includes('/gui/')) {
        layer = 'presentation';
      } else if (relativePath.includes('/application/') || relativePath.includes('/core/')) {
        layer = 'application';
      } else if (relativePath.includes('/infrastructure/') || relativePath.includes('/storage/') || relativePath.includes('/index/')) {
        layer = 'infrastructure';
      } else if (relativePath.includes('/domain/')) {
        layer = 'domain';
      }

      // Extract package name
      const packageRegex = /package\s+([a-zA-Z0-9_.]+)/;
      const packageMatch = content.match(packageRegex);
      const packageName = packageMatch ? packageMatch[1] : undefined;

      // Extract class/interface/object name
      const classRegex = /(class|interface|object|enum class)\s+(\w+)/;
      const classMatch = content.match(classRegex);
      const className = classMatch ? classMatch[2] : undefined;
      const componentType = classMatch ? classMatch[1].replace(' class', '') as SourceFile['componentType'] : undefined;

      return {
        packageName,
        className,
        componentType,
        layer
      };
    } catch (error: any) {
      this.log(`Error extracting metadata from ${filePath}: ${error.message}`);
      return {};
    }
  }

  /**
   * Read file content
   */
  async readFile(filePath: string): Promise<string | null> {
    const fs = require('fs');

    try {
      if (fs.existsSync(filePath)) {
        const content = fs.readFileSync(filePath, 'utf-8');
        this.log(`Read file: ${filePath} (${content.length} bytes)`);
        return content;
      }
      this.log(`File not found: ${filePath}`);
      return null;
    } catch (error: any) {
      this.log(`Error reading file ${filePath}: ${error.message}`);
      return null;
    }
  }

  /**
   * Write file content
   */
  async writeFile(filePath: string, content: string): Promise<boolean> {
    const fs = require('fs');
    const path = require('path');

    try {
      // Ensure directory exists
      const dir = path.dirname(filePath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }

      fs.writeFileSync(filePath, content, 'utf-8');
      this.log(`Wrote file: ${filePath} (${content.length} bytes)`);
      return true;
    } catch (error: any) {
      this.log(`Error writing file ${filePath}: ${error.message}`);
      return false;
    }
  }

  /**
   * Check if file exists
   */
  async fileExists(filePath: string): Promise<boolean> {
    const fs = require('fs');
    return fs.existsSync(filePath);
  }

  /**
   * Get file stats
   */
  async getFileStats(filePath: string): Promise<{ size: number; lastModified: Date } | null> {
    const fs = require('fs');

    try {
      if (fs.existsSync(filePath)) {
        const stats = fs.statSync(filePath);
        return {
          size: stats.size,
          lastModified: stats.mtime
        };
      }
      return null;
    } catch (error: any) {
      this.log(`Error getting stats for ${filePath}: ${error.message}`);
      return null;
    }
  }

  /**
   * Start watching files for changes
   */
  startFileWatching(): void {
    if (this.fileWatcher) {
      this.fileWatcher.dispose();
    }

    // Watch for Kotlin, Java, and Gradle files
    const pattern = '**/*.{kt,kts,java,gradle,xml}';
    
    this.fileWatcher = vscode.workspace.createFileSystemWatcher(
      new vscode.RelativePattern(this.workspaceRoot, pattern)
    );

    this.fileWatcher.onDidCreate((uri) => {
      this.log(`File created: ${uri.fsPath}`);
      this._onFileChange.fire({
        type: 'created',
        filePath: uri.fsPath,
        timestamp: new Date()
      });
    });

    this.fileWatcher.onDidChange((uri) => {
      this.log(`File changed: ${uri.fsPath}`);
      this._onFileChange.fire({
        type: 'changed',
        filePath: uri.fsPath,
        timestamp: new Date()
      });
    });

    this.fileWatcher.onDidDelete((uri) => {
      this.log(`File deleted: ${uri.fsPath}`);
      this._onFileChange.fire({
        type: 'deleted',
        filePath: uri.fsPath,
        timestamp: new Date()
      });
    });

    this.log(`Started watching files: ${pattern}`);
  }

  /**
   * Stop file watching
   */
  stopFileWatching(): void {
    if (this.fileWatcher) {
      this.fileWatcher.dispose();
      this.fileWatcher = undefined;
      this.log('Stopped file watching');
    }
  }

  /**
   * Get cached project
   */
  getProjectCache(): FileSystemProject | null {
    return this.projectCache;
  }

  /**
   * Clear project cache
   */
  clearCache(): void {
    this.projectCache = null;
    this.log('Project cache cleared');
  }

  /**
   * Log message to output channel
   */
  private log(message: string): void {
    const timestamp = new Date().toISOString().split('T')[1].split('.')[0];
    this.outputChannel.appendLine(`[${timestamp}] FS: ${message}`);
  }

  /**
   * Dispose resources
   */
  dispose(): void {
    this.stopFileWatching();
    this._onFileChange.dispose();
  }
}

/**
 * File System Utilities
 */
export class FSUtils {
  /**
   * Ensure directory exists
   */
  static ensureDir(dirPath: string): boolean {
    const fs = require('fs');
    try {
      if (!fs.existsSync(dirPath)) {
        fs.mkdirSync(dirPath, { recursive: true });
      }
      return true;
    } catch {
      return false;
    }
  }

  /**
   * Get relative path
   */
  static relativePath(from: string, to: string): string {
    const path = require('path');
    return path.relative(from, to);
  }

  /**
   * Join paths
   */
  static joinPath(...paths: string[]): string {
    const path = require('path');
    return path.join(...paths);
  }

  /**
   * Check if path is within workspace
   */
  static isWithinWorkspace(filePath: string, workspaceRoot: string): boolean {
    const path = require('path');
    const relative = path.relative(workspaceRoot, filePath);
    return !relative.startsWith('..') && !path.isAbsolute(relative);
  }

  /**
   * Get file extension
   */
  static getExtension(filePath: string): string {
    const path = require('path');
    return path.extname(filePath).toLowerCase();
  }

  /**
   * Get file name without extension
   */
  static getFileNameWithoutExtension(filePath: string): string {
    const path = require('path');
    return path.basename(filePath, path.extname(filePath));
  }
}
