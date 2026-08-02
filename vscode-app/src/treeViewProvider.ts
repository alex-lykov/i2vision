/**
 * i2-Vision Tree View Provider
 * 
 * Provides tree data for the i2-Vision Explorer view,
 * integrating with file system and i2vision CLI for real-time data.
 */

import * as vscode from 'vscode';
import { CLI as I2VisionCLI, DiscoveryResult, TemplateInfo, ComponentInfo } from './cliIntegrationRefactored';
import { FileSystemIntegration, FileSystemProject, FileSystemModule, SourceFile } from './fileSystemIntegration';

/**
 * Tree item types for the i2-Vision Explorer
 */
export type TreeItemType = 
  | 'root'
  | 'projects'
  | 'project'
  | 'module'
  | 'sourceDir'
  | 'file'
  | 'templates'
  | 'template'
  | 'settings'
  | 'documentation'
  | 'component'
  | 'layer'
  | 'relationship';

/**
 * Custom tree item for i2-Vision Explorer
 */
export class I2VisionTreeItem extends vscode.TreeItem {
  public children: I2VisionTreeItem[] = [];
  public itemType: TreeItemType;
  public metadata?: any;

  constructor(
    label: string,
    itemType: TreeItemType,
    collapsibleState: vscode.TreeItemCollapsibleState = vscode.TreeItemCollapsibleState.None,
    metadata?: any
  ) {
    super(label, collapsibleState);
    this.itemType = itemType;
    this.metadata = metadata;
    this.iconPath = this.getIconForType(itemType);
    
    // Set resource URI for file items to enable context menus
    if (itemType === 'file' && metadata?.path) {
      this.resourceUri = vscode.Uri.file(metadata.path);
    }
  }

  private getIconForType(type: TreeItemType): vscode.ThemeIcon | string {
    const iconMap: Record<TreeItemType, string> = {
      root: 'folder',
      projects: 'folder',
      project: 'folder',
      module: 'folder',
      sourceDir: 'folder',
      file: 'file',
      templates: 'folder',
      template: 'file',
      settings: 'gear',
      documentation: 'book',
      component: 'symbol-class',
      layer: 'symbol-namespace',
      relationship: 'link'
    };

    const iconName = iconMap[type] || 'file';
    return new vscode.ThemeIcon(iconName);
  }
}

/**
 * Tree Data Provider for i2-Vision Explorer
 */
export class I2VisionTreeProvider implements vscode.TreeDataProvider<I2VisionTreeItem> {
  private _onDidChangeTreeData = new vscode.EventEmitter<I2VisionTreeItem | undefined>();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;
  
  private cli: I2VisionCLI;
  private fileSystem: FileSystemIntegration;
  private workspaceRoot: string;
  private discoveryCache: DiscoveryResult | null = null;
  private templatesCache: TemplateInfo[] | null = null;
  private fileSystemCache: FileSystemProject | null = null;
  private useFileSystemScan: boolean = false;

  constructor(workspaceRoot: string, outputChannel?: vscode.OutputChannel) {
    this.workspaceRoot = workspaceRoot;
    this.cli = new I2VisionCLI(workspaceRoot, outputChannel);
    this.fileSystem = new FileSystemIntegration(workspaceRoot, outputChannel);
    
    // Start file watching for real-time updates
    this.fileSystem.startFileWatching();
    
    // Listen to file changes and refresh tree
    this.fileSystem.onFileChange((event) => {
      this.log(`File change detected: ${event.type} - ${event.filePath}`);
      this.refresh();
    });
  }

  /**
   * Get tree item for display
   */
  getTreeItem(element: I2VisionTreeItem): vscode.TreeItem {
    return element;
  }

  /**
   * Get children for a tree element
   */
  async getChildren(element?: I2VisionTreeItem): Promise<I2VisionTreeItem[]> {
    if (!element) {
      // Root level - show main categories
      return this.getRootItems();
    }

    // Return cached children if available
    if (element.children && element.children.length > 0) {
      return element.children;
    }

    // Load children based on item type
    switch (element.itemType) {
      case 'projects':
        return this.getProjects(element);
      case 'project':
        return this.getProjectDetails(element);
      case 'module':
        return this.getModuleDetails(element);
      case 'sourceDir':
        return this.getSourceDirFiles(element);
      case 'templates':
        return this.getTemplates(element);
      case 'template':
        return this.getTemplateDetails(element);
      case 'settings':
        return this.getSettingsItems();
      case 'documentation':
        return this.getDocumentationItems();
      default:
        return [];
    }
  }

  /**
   * Get root level items
   */
  private getRootItems(): I2VisionTreeItem[] {
    const items: I2VisionTreeItem[] = [
      new I2VisionTreeItem(
        'Projects',
        'projects',
        vscode.TreeItemCollapsibleState.Collapsed
      ),
      new I2VisionTreeItem(
        'Templates',
        'templates',
        vscode.TreeItemCollapsibleState.Collapsed
      ),
      new I2VisionTreeItem(
        'Settings',
        'settings',
        vscode.TreeItemCollapsibleState.None
      ),
      new I2VisionTreeItem(
        'Documentation',
        'documentation',
        vscode.TreeItemCollapsibleState.None
      )
    ];

    return items;
  }

  /**
   * Get projects from file system or discovery
   */
  private async getProjects(element: I2VisionTreeItem): Promise<I2VisionTreeItem[]> {
    try {
      // Try file system scan first if enabled
      if (this.useFileSystemScan) {
        const fsProject = await this.fileSystem.scanWorkspace();
        if (fsProject) {
          this.fileSystemCache = fsProject;
          return this.buildFileSystemTree(fsProject);
        }
      }

      // Fall back to CLI discovery
      const discovery = await this.cli.runDiscovery();
      this.discoveryCache = discovery;

      // Group by layers or show components
      const projectItems: I2VisionTreeItem[] = [];

      // Add project root
      const projectItem = new I2VisionTreeItem(
        discovery.projectName,
        'project',
        vscode.TreeItemCollapsibleState.Expanded,
        { version: discovery.version }
      );
      projectItem.description = `v${discovery.version}`;
      projectItem.tooltip = `Project: ${discovery.projectName}\nVersion: ${discovery.version}\nComponents: ${discovery.components.length}`;
      
      // Add layer groups
      if (discovery.layers && discovery.layers.length > 0) {
        for (const layer of discovery.layers) {
          const layerItem = new I2VisionTreeItem(
            `${layer.name} (${layer.components.length})`,
            'layer',
            vscode.TreeItemCollapsibleState.Collapsed,
            { layer: layer }
          );
          layerItem.description = `Level ${layer.level}`;
          layerItem.tooltip = `Layer: ${layer.name}\nLevel: ${layer.level}\nComponents: ${layer.components.join(', ')}`;
          
          // Add components in this layer
          layerItem.children = discovery.components
            .filter(c => c.layer === layer.name)
            .map(c => this.createComponentItem(c));
          
          projectItems.push(layerItem);
        }
      } else {
        // No layers, show all components
        projectItem.children = discovery.components.map(c => this.createComponentItem(c));
      }

      projectItems.unshift(projectItem);

      // Add violations if any
      if (discovery.violations && discovery.violations.length > 0) {
        const violationsItem = new I2VisionTreeItem(
          `⚠️ Violations (${discovery.violations.length})`,
          'component',
          vscode.TreeItemCollapsibleState.Collapsed
        );
        violationsItem.tooltip = 'Architecture violations detected';
        violationsItem.children = discovery.violations.map(v => {
          const violationItem = new I2VisionTreeItem(
            v.message,
            'component',
            vscode.TreeItemCollapsibleState.None
          );
          violationItem.description = v.severity.toUpperCase();
          violationItem.tooltip = `Source: ${v.source}\nTarget: ${v.target}\nRule: ${v.rule}`;
          
          // Color code by severity
          if (v.severity === 'error') {
            violationItem.resourceUri = vscode.Uri.parse('violation:error');
          } else if (v.severity === 'warning') {
            violationItem.resourceUri = vscode.Uri.parse('violation:warning');
          }
          
          return violationItem;
        });
        projectItems.push(violationsItem);
      }

      return projectItems;
    } catch (error: any) {
      vscode.window.showErrorMessage(`Failed to load projects: ${error.message}`);
      return [];
    }
  }

  /**
   * Build tree from file system scan
   */
  private buildFileSystemTree(project: FileSystemProject): I2VisionTreeItem[] {
    const projectItems: I2VisionTreeItem[] = [];

    const projectItem = new I2VisionTreeItem(
      project.name,
      'project',
      vscode.TreeItemCollapsibleState.Expanded,
      { project: project }
    );
    projectItem.description = `${project.modules.length} modules`;
    projectItem.tooltip = `Project: ${project.name}\nRoot: ${project.rootPath}\nModules: ${project.modules.length}\nSource Files: ${project.sourceFiles.length}`;

    // Add modules
    for (const module of project.modules) {
      const moduleItem = new I2VisionTreeItem(
        module.name,
        'module',
        vscode.TreeItemCollapsibleState.Collapsed,
        { module: module }
      );
      moduleItem.description = `${module.sourceDirectories.length} source dirs`;
      moduleItem.tooltip = `Module: ${module.name}\nPath: ${module.path}\nDependencies: ${module.dependencies.length}`;

      // Add source directories
      for (const sourceDir of module.sourceDirectories) {
        const dirName = sourceDir.replace(module.path + '/', '');
        const sourceDirItem = new I2VisionTreeItem(
          dirName,
          'sourceDir',
          vscode.TreeItemCollapsibleState.Collapsed,
          { sourceDir: sourceDir, module: module }
        );
        sourceDirItem.description = 'source';
        
        // Add files in this directory
        sourceDirItem.children = project.sourceFiles
          .filter(f => f.path.startsWith(sourceDir))
          .map(f => this.createFileItem(f));
        
        moduleItem.children.push(sourceDirItem);
      }

      projectItem.children.push(moduleItem);
    }

    projectItems.push(projectItem);
    return projectItems;
  }

  /**
   * Create tree item for a component
   */
  private createComponentItem(component: ComponentInfo): I2VisionTreeItem {
    const item = new I2VisionTreeItem(
      component.name,
      'component',
      component.dependencies && component.dependencies.length > 0
        ? vscode.TreeItemCollapsibleState.Collapsed
        : vscode.TreeItemCollapsibleState.None,
      { component: component }
    );

    item.description = component.type;
    item.tooltip = `Component: ${component.name}\nType: ${component.type}\nPath: ${component.path}\nLayer: ${component.layer || 'N/A'}`;
    
    // Add command to open file
    if (component.path) {
      item.command = {
        command: 'i2vision.openFile',
        title: 'Open File',
        arguments: [component.path]
      };
    }

    // Add dependencies as children
    if (component.dependencies && component.dependencies.length > 0) {
      item.children = component.dependencies.map(dep => {
        const depItem = new I2VisionTreeItem(
          dep,
          'relationship',
          vscode.TreeItemCollapsibleState.None
        );
        depItem.description = 'dependency';
        depItem.iconPath = new vscode.ThemeIcon('link');
        return depItem;
      });
    }

    return item;
  }

  /**
   * Create tree item for a source file
   */
  private createFileItem(file: SourceFile): I2VisionTreeItem {
    const item = new I2VisionTreeItem(
      file.relativePath.split('/').pop() || file.relativePath,
      'file',
      vscode.TreeItemCollapsibleState.None,
      { file: file }
    );

    // Set file icon based on type
    const iconMap: Record<SourceFile['type'], string> = {
      kotlin: 'symbol-method',
      java: 'symbol-method',
      xml: 'code',
      gradle: 'settings-gear',
      other: 'file'
    };
    item.iconPath = new vscode.ThemeIcon(iconMap[file.type] || 'file');

    item.description = file.type;
    item.tooltip = `File: ${file.relativePath}\nType: ${file.type}\nSize: ${this.formatFileSize(file.size)}\nModified: ${file.lastModified.toLocaleString()}`;
    
    if (file.className) {
      item.tooltip += `\nClass: ${file.className}`;
    }
    if (file.packageName) {
      item.tooltip += `\nPackage: ${file.packageName}`;
    }
    if (file.layer) {
      item.tooltip += `\nLayer: ${file.layer}`;
    }

    // Add command to open file
    item.command = {
      command: 'i2vision.openFile',
      title: 'Open File',
      arguments: [file.path]
    };

    return item;
  }

  /**
   * Get project details
   */
  private async getProjectDetails(element: I2VisionTreeItem): Promise<I2VisionTreeItem[]> {
    // If we have file system cache, show modules
    if (this.fileSystemCache) {
      return this.fileSystemCache.modules.map(module => {
        const moduleItem = new I2VisionTreeItem(
          module.name,
          'module',
          vscode.TreeItemCollapsibleState.Collapsed,
          { module: module }
        );
        moduleItem.description = `${module.sourceDirectories.length} source dirs`;
        return moduleItem;
      });
    }
    
    // Already expanded in getProjects, return empty
    return [];
  }

  /**
   * Get module details
   */
  private async getModuleDetails(element: I2VisionTreeItem): Promise<I2VisionTreeItem[]> {
    if (element.children && element.children.length > 0) {
      return element.children;
    }

    const module = element.metadata?.module as FileSystemModule;
    if (!module) {
      return [];
    }

    const items: I2VisionTreeItem[] = [];

    // Add source directories
    for (const sourceDir of module.sourceDirectories) {
      const dirName = sourceDir.split('/').pop() || sourceDir;
      const sourceDirItem = new I2VisionTreeItem(
        dirName,
        'sourceDir',
        vscode.TreeItemCollapsibleState.Collapsed,
        { sourceDir: sourceDir, module: module }
      );
      sourceDirItem.description = 'source';
      
      // Files will be loaded when expanded
      items.push(sourceDirItem);
    }

    return items;
  }

  /**
   * Get files in source directory
   */
  private async getSourceDirFiles(element: I2VisionTreeItem): Promise<I2VisionTreeItem[]> {
    const sourceDir = element.metadata?.sourceDir as string;
    const module = element.metadata?.module as FileSystemModule;

    if (!sourceDir || !this.fileSystemCache) {
      return [];
    }

    // Filter files in this directory
    const files = this.fileSystemCache.sourceFiles.filter(f => {
      const fileDir = f.path.substring(0, f.path.lastIndexOf('/'));
      return fileDir === sourceDir;
    });

    return files.map(f => this.createFileItem(f));
  }

  /**
   * Get templates from CLI
   */
  private async getTemplates(element: I2VisionTreeItem): Promise<I2VisionTreeItem[]> {
    try {
      const templates = await this.cli.listTemplates();
      this.templatesCache = templates;

      return templates.map(t => {
        const item = new I2VisionTreeItem(
          t.name,
          'template',
          vscode.TreeItemCollapsibleState.Collapsed,
          { template: t }
        );
        
        item.description = t.category;
        item.tooltip = `Template: ${t.name}\nCategory: ${t.category}\nDescription: ${t.description}\nFiles: ${t.files.length}`;
        item.iconPath = new vscode.ThemeIcon('file-code');
        
        // Add template variables as children
        item.children = t.variables.map(v => {
          const varItem = new I2VisionTreeItem(
            `${v.name}${v.required ? ' *' : ''}`,
            'component',
            vscode.TreeItemCollapsibleState.None
          );
          varItem.description = v.defaultValue || 'required';
          varItem.tooltip = v.description;
          return varItem;
        });

        return item;
      });
    } catch (error: any) {
      vscode.window.showErrorMessage(`Failed to load templates: ${error.message}`);
      return [];
    }
  }

  /**
   * Get template details
   */
  private async getTemplateDetails(element: I2VisionTreeItem): Promise<I2VisionTreeItem[]> {
    // Already expanded in getTemplates, return empty
    return [];
  }

  /**
   * Get settings items
   */
  private getSettingsItems(): I2VisionTreeItem[] {
    const items: I2VisionTreeItem[] = [
      new I2VisionTreeItem(
        'Extension Settings',
        'settings',
        vscode.TreeItemCollapsibleState.None
      ),
      new I2VisionTreeItem(
        'CLI Configuration',
        'settings',
        vscode.TreeItemCollapsibleState.None
      ),
      new I2VisionTreeItem(
        'Architecture Rules',
        'settings',
        vscode.TreeItemCollapsibleState.None
      ),
      new I2VisionTreeItem(
        'File System Scan',
        'settings',
        vscode.TreeItemCollapsibleState.None
      )
    ];

    // Add commands
    items[0].command = {
      command: 'workbench.action.openSettings',
      title: 'Open Settings',
      arguments: [`@ext:i2vision.i2-vision-vscode`]
    };

    items[3].command = {
      command: 'i2vision.toggleFileSystemScan',
      title: 'Toggle File System Scan'
    };

    return items;
  }

  /**
   * Get documentation items
   */
  private getDocumentationItems(): I2VisionTreeItem[] {
    const items: I2VisionTreeItem[] = [
      new I2VisionTreeItem(
        'Quick Start Guide',
        'documentation',
        vscode.TreeItemCollapsibleState.None
      ),
      new I2VisionTreeItem(
        'Architecture Documentation',
        'documentation',
        vscode.TreeItemCollapsibleState.None
      ),
      new I2VisionTreeItem(
        'API Reference',
        'documentation',
        vscode.TreeItemCollapsibleState.None
      ),
      new I2VisionTreeItem(
        'GitHub Repository',
        'documentation',
        vscode.TreeItemCollapsibleState.None
      )
    ];

    // Add commands to open documentation
    items[0].command = {
      command: 'vscode.open',
      title: 'Open Quick Start',
      arguments: [vscode.Uri.file(`${this.workspaceRoot}/vscode-app/QUICKSTART.md`)]
    };

    items[3].command = {
      command: 'vscode.open',
      title: 'Open GitHub',
      arguments: [vscode.Uri.parse('https://github.com/i2vision/i2-vision')]
    };

    return items;
  }

  /**
   * Refresh the tree view
   */
  refresh(): void {
    this.discoveryCache = null;
    this.templatesCache = null;
    this.fileSystemCache = null;
    this._onDidChangeTreeData.fire(undefined);
  }

  /**
   * Toggle file system scanning mode
   */
  toggleFileSystemScan(): void {
    this.useFileSystemScan = !this.useFileSystemScan;
    this.log(`File system scan mode: ${this.useFileSystemScan ? 'enabled' : 'disabled'}`);
    this.refresh();
    vscode.window.showInformationMessage(
      `File system scan ${this.useFileSystemScan ? 'enabled' : 'disabled'}`
    );
  }

  /**
   * Get cached discovery result
   */
  getDiscoveryCache(): DiscoveryResult | null {
    return this.discoveryCache;
  }

  /**
   * Get cached templates
   */
  getTemplatesCache(): TemplateInfo[] | null {
    return this.templatesCache;
  }

  /**
   * Get cached file system project
   */
  getFileSystemCache(): FileSystemProject | null {
    return this.fileSystemCache;
  }

  /**
   * Check if file system scan is enabled
   */
  isFileSystemScanEnabled(): boolean {
    return this.useFileSystemScan;
  }

  /**
   * Format file size
   */
  private formatFileSize(bytes: number): string {
    if (bytes < 1024) {
      return `${bytes} B`;
    } else if (bytes < 1024 * 1024) {
      return `${(bytes / 1024).toFixed(1)} KB`;
    } else {
      return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    }
  }

  /**
   * Log message
   */
  private log(message: string): void {
    // Output channel is managed by CLI and FileSystem
    console.log(`[TreeProvider] ${message}`);
  }

  /**
   * Dispose resources
   */
  dispose(): void {
    this.fileSystem.dispose();
    this._onDidChangeTreeData.dispose();
  }
}
