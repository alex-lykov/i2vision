/**
 * i2-Vision VSCode Extension
 * 
 * Main extension entry point with Tree View integration,
 * i2vision CLI backend connectivity, and file system operations.
 */

import * as vscode from 'vscode';
import { I2VisionTreeProvider, I2VisionTreeItem } from './treeViewProvider';
import { CLI as I2VisionCLI } from './cliIntegration';
import { FileSystemIntegration, FSUtils } from './fileSystemIntegration';
import { AgentTabManager } from './agent/AgentTabManager';
import { LocalAgentProvider } from './agent/LocalAgentProvider';
import { registerDebugCommands } from './agent/ToolCallDebugger';

/**
 * Extension context
 */
let extensionContext: vscode.ExtensionContext;
let outputChannel: vscode.OutputChannel;
let treeProvider: I2VisionTreeProvider;
let fileSystem: FileSystemIntegration;
let agentManager: AgentTabManager;

/**
 * Activate the extension
 */
export async function activate(context: vscode.ExtensionContext) {
    extensionContext = context;
    
    // Create output channel for logging
    outputChannel = vscode.window.createOutputChannel('i2-Vision');
    context.subscriptions.push(outputChannel);
    
    outputChannel.appendLine('i2-Vision extension activated');
    outputChannel.appendLine(`Workspace: ${vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || 'No workspace'}`);

    // Get workspace root
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    
    if (!workspaceRoot) {
        vscode.window.showWarningMessage('No workspace folder open. Some i2-Vision features may not work.');
    }

    // Initialize file system integration
    fileSystem = new FileSystemIntegration(workspaceRoot, outputChannel);
    
    // Initialize tree provider with CLI and file system integration
    treeProvider = new I2VisionTreeProvider(workspaceRoot, outputChannel);
    
    // Initialize agent provider first, then agent tab manager
    const agentProvider = new LocalAgentProvider(context, outputChannel);
    await agentProvider.initialize();
    
    // Initialize agent tab manager with provider
    agentManager = new AgentTabManager(context, outputChannel, agentProvider);
    
    // Initialize the agent manager - non-blocking
    agentManager.initialize().catch(err => {
        outputChannel.appendLine(`Warning: Agent manager initialization failed: ${err.message}`);
    });
    
    // Register tree view
    const treeView = vscode.window.createTreeView('i2visionTreeView', {
        treeDataProvider: treeProvider,
        showCollapseAll: true
    });
    context.subscriptions.push(treeView);

    // Register commands
    registerCommands(context, workspaceRoot);

    // NON-BLOCKING CLI check - moved to background to prevent extension host hang
    // This was causing "Extension host is unresponsive" errors
    checkCLIAvailability(workspaceRoot).catch(err => {
        outputChannel.appendLine(`CLI check failed (non-blocking): ${err.message}`);
    });

    // Scan workspace on startup (optional)
    scanWorkspaceOnStartup(workspaceRoot);

    // Show welcome message
    vscode.window.showInformationMessage('i2-Vision extension is now active! 🚀');
    
    outputChannel.appendLine('Extension initialization complete');
}

/**
 * Register all extension commands
 */
function registerCommands(context: vscode.ExtensionContext, workspaceRoot: string) {
    // Hello World command
    const helloWorldCmd = vscode.commands.registerCommand('i2vision.helloWorld', () => {
        vscode.window.showInformationMessage('Hello from i2-Vision! 👋');
        outputChannel.appendLine('Hello World command executed');
    });
    context.subscriptions.push(helloWorldCmd);

    // Refresh tree command
    const refreshTreeCmd = vscode.commands.registerCommand('i2vision.refreshTree', () => {
        outputChannel.appendLine('Refreshing tree view...');
        treeProvider.refresh();
        vscode.window.showInformationMessage('i2-Vision tree refreshed');
    });
    context.subscriptions.push(refreshTreeCmd);

    // Create project command
    const createProjectCmd = vscode.commands.registerCommand('i2vision.createProject', async () => {
        await handleCreateProject(workspaceRoot);
    });
    context.subscriptions.push(createProjectCmd);

    // Initialize VSLFC structure command (i2vision.init)
    const initCmd = vscode.commands.registerCommand('i2vision.init', async () => {
        await handleInitializeProject(workspaceRoot);
    });
    context.subscriptions.push(initCmd);

    // Open project command
    const openProjectCmd = vscode.commands.registerCommand('i2vision.openProject', async (item?: I2VisionTreeItem) => {
        await handleOpenProject(item);
    });
    context.subscriptions.push(openProjectCmd);

    // Open file command (for tree items)
    const openFileCmd = vscode.commands.registerCommand('i2vision.openFile', async (filePath: string) => {
        await handleOpenFile(filePath);
    });
    context.subscriptions.push(openFileCmd);

    // Show discovery results command
    const showDiscoveryCmd = vscode.commands.registerCommand('i2vision.showDiscovery', async () => {
        await showDiscoveryResults(workspaceRoot);
    });
    context.subscriptions.push(showDiscoveryCmd);

    // Analyze architecture command
    const analyzeCmd = vscode.commands.registerCommand('i2vision.analyzeArchitecture', async () => {
        await analyzeArchitecture(workspaceRoot);
    });
    context.subscriptions.push(analyzeCmd);

    // View documentation command
    const viewDocsCmd = vscode.commands.registerCommand('i2vision.viewDocumentation', async () => {
        await viewDocumentation();
    });
    context.subscriptions.push(viewDocsCmd);

    // Toggle file system scan command
    const toggleFSCmd = vscode.commands.registerCommand('i2vision.toggleFileSystemScan', () => {
        treeProvider.toggleFileSystemScan();
    });
    context.subscriptions.push(toggleFSCmd);

    // Scan workspace command
    const scanWSCmd = vscode.commands.registerCommand('i2vision.scanWorkspace', async () => {
        await scanWorkspace(workspaceRoot);
    });
    context.subscriptions.push(scanWSCmd);

    // Show file info command
    const showFileInfoCmd = vscode.commands.registerCommand('i2vision.showFileInfo', async (item?: I2VisionTreeItem) => {
        await showFileInfo(item);
    });
    context.subscriptions.push(showFileInfoCmd);

    // Open containing folder command
    const openFolderCmd = vscode.commands.registerCommand('i2vision.openContainingFolder', async (item?: I2VisionTreeItem) => {
        await openContainingFolder(item);
    });
    context.subscriptions.push(openFolderCmd);

    // === AGENT COMMANDS ===
    
    // Coding Agent command
    const codingAgentCmd = vscode.commands.registerCommand('i2vision.newCodingAgent', async () => {
        try {
            const tabId = await agentManager.createTab('code');
            vscode.window.showInformationMessage('Coding Agent tab created 🤖');
            outputChannel.appendLine(`Created coding agent tab: ${tabId}`);
        } catch (error: any) {
            vscode.window.showErrorMessage(`Failed to create Coding Agent: ${error.message}`);
            outputChannel.appendLine(`Error creating coding agent: ${error.message}`);
        }
    });
    context.subscriptions.push(codingAgentCmd);

    // Vision Agent command
    const visionAgentCmd = vscode.commands.registerCommand('i2vision.newVisionAgent', async () => {
        try {
            const tabId = await agentManager.createTab('vision');
            vscode.window.showInformationMessage('Vision Agent tab created 👁️');
            outputChannel.appendLine(`Created vision agent tab: ${tabId}`);
        } catch (error: any) {
            vscode.window.showErrorMessage(`Failed to create Vision Agent: ${error.message}`);
        }
    });
    context.subscriptions.push(visionAgentCmd);

    // Structure Agent command
    const structureAgentCmd = vscode.commands.registerCommand('i2vision.newStructureAgent', async () => {
        try {
            const tabId = await agentManager.createTab('structure');
            vscode.window.showInformationMessage('Structure Agent tab created 🏗️');
            outputChannel.appendLine(`Created structure agent tab: ${tabId}`);
        } catch (error: any) {
            vscode.window.showErrorMessage(`Failed to create Structure Agent: ${error.message}`);
        }
    });
    context.subscriptions.push(structureAgentCmd);

    // Logic Agent command
    const logicAgentCmd = vscode.commands.registerCommand('i2vision.newLogicAgent', async () => {
        try {
            const tabId = await agentManager.createTab('logic');
            vscode.window.showInformationMessage('Logic Agent tab created 🧠');
            outputChannel.appendLine(`Created logic agent tab: ${tabId}`);
        } catch (error: any) {
            vscode.window.showErrorMessage(`Failed to create Logic Agent: ${error.message}`);
        }
    });
    context.subscriptions.push(logicAgentCmd);

    // Flow Agent command
    const flowAgentCmd = vscode.commands.registerCommand('i2vision.newFlowAgent', async () => {
        try {
            const tabId = await agentManager.createTab('flow');
            vscode.window.showInformationMessage('Flow Agent tab created 🌊');
            outputChannel.appendLine(`Created flow agent tab: ${tabId}`);
        } catch (error: any) {
            vscode.window.showErrorMessage(`Failed to create Flow Agent: ${error.message}`);
        }
    });
    context.subscriptions.push(flowAgentCmd);

    // === DEBUGGING COMMANDS ===
    
    // Register tool call debugging commands
    registerDebugCommands(context, outputChannel, agentManager);

    // Reload agent config command (clears cache)
    const reloadConfigCmd = vscode.commands.registerCommand('i2vision.reloadAgentConfig', async () => {
        outputChannel.appendLine('🔄 Clearing agent config cache...');
        agentManager.clearConfigCache();
        outputChannel.appendLine('✅ Config cache cleared. Next agent creation will reload from disk.');
        vscode.window.showInformationMessage('Agent config cache cleared. Create a new agent to reload config.');
    });
    context.subscriptions.push(reloadConfigCmd);
}

/**
 * Scan workspace on startup
 */
async function scanWorkspaceOnStartup(workspaceRoot: string) {
    outputChannel.appendLine('Performing initial workspace scan...');
    
    try {
        const project = await fileSystem.scanWorkspace();
        if (project) {
            outputChannel.appendLine(`Workspace scanned: ${project.name}`);
            outputChannel.appendLine(`  Modules: ${project.modules.length}`);
            outputChannel.appendLine(`  Source files: ${project.sourceFiles.length}`);
        }
    } catch (error: any) {
        outputChannel.appendLine(`Workspace scan error: ${error.message}`);
    }
}

/**
 * Handle create project command
 */
async function handleCreateProject(workspaceRoot: string) {
    outputChannel.appendLine('Create project command invoked');

    const cli = new I2VisionCLI(workspaceRoot, outputChannel);
    
    // Get available templates
    const templates = await cli.listTemplates();
    
    if (templates.length === 0) {
        vscode.window.showErrorMessage('No templates available');
        return;
    }

    // Show template selection quick pick
    const templateItems = templates.map(t => ({
        label: t.name,
        description: t.category,
        detail: t.description,
        template: t
    }));

    const selected = await vscode.window.showQuickPick(templateItems, {
        placeHolder: 'Select a project template',
        matchOnDescription: true,
        matchOnDetail: true
    });

    if (!selected) {
        return;
    }

    // Get project name
    const projectName = await vscode.window.showInputBox({
        prompt: 'Enter project name',
        placeHolder: 'my-project',
        validateInput: value => {
            if (!value || value.trim().length === 0) {
                return 'Project name is required';
            }
            if (!/^[a-zA-Z][a-zA-Z0-9_-]*$/.test(value)) {
                return 'Project name must start with a letter and contain only letters, numbers, underscores, and hyphens';
            }
            return null;
        }
    });

    if (!projectName) {
        return;
    }

    // Get template variables
    const variables: Record<string, string> = {};
    for (const variable of selected.template.variables) {
        if (variable.required || variable.defaultValue) {
            const value = await vscode.window.showInputBox({
                prompt: `${variable.description}`,
                placeHolder: variable.defaultValue,
                value: variable.defaultValue,
                ignoreFocusOut: true
            });
            
            if (variable.required && !value) {
                vscode.window.showErrorMessage(`${variable.name} is required`);
                return;
            }
            
            if (value) {
                variables[variable.name] = value;
            }
        }
    }

    // Create the project
    const success = await cli.createProject(selected.template.name, projectName, variables);
    
    if (success) {
        vscode.window.showInformationMessage(`Project '${projectName}' created successfully!`);
        treeProvider.refresh();
    }
}

/**
 * Handle initialize project command (i2vision.init)
 * Initializes the VSLFC structure in the current project using RolloutManager
 */
async function handleInitializeProject(workspaceRoot: string) {
    outputChannel.appendLine('Initialize project command invoked (i2vision.init)');
    
    if (!workspaceRoot) {
        vscode.window.showErrorMessage('No workspace folder open. Cannot initialize project.');
        return;
    }
    
    try {
        // Call the storage-core RolloutManager via CLI or directly
        // For now, we'll use a simple approach: create the .vision-ai structure directly
        
        const visionAiDir = require('path').join(workspaceRoot, '.vision-ai');
        const fs = require('fs');
        
        // Check if already initialized
        if (fs.existsSync(visionAiDir)) {
            const confirm = await vscode.window.showWarningMessage(
                'This project already has a .vision-ai directory. Do you want to re-initialize?',
                { modal: true },
                'Yes', 'No'
            );
            
            if (confirm !== 'Yes') {
                return;
            }
        }
        
        outputChannel.appendLine(`Initializing VSLFC structure in: ${workspaceRoot}`);
        vscode.window.showInformationMessage('Initializing VSLFC structure...');
        
        // Create .vision-ai root
        fs.mkdirSync(visionAiDir, { recursive: true });
        
        // Create layer directories
        const layers = ['vision', 'code', 'logic', 'structure', 'flow', 'data', 'api'];
        for (const layer of layers) {
            const layerDir = require('path').join(visionAiDir, layer);
            fs.mkdirSync(layerDir, { recursive: true });
            
            // Create agent config
            const agentConfig = require('path').join(layerDir, `${layer}-agent.yaml`);
            if (!fs.existsSync(agentConfig)) {
                fs.writeFileSync(agentConfig, `# Agent Configuration for ${layer} layer\nlayer: ${layer}\n`);
            }
            
            // Create contract file
            const contractFile = require('path').join(layerDir, 'contract.yaml');
            if (!fs.existsSync(contractFile)) {
                fs.writeFileSync(contractFile, `# Contract for ${layer} layer\nversion: "2.0"\nlayer: ${layer}\n`);
            }
        }
        
        // Create config directory with CLI config
        const configDir = require('path').join(visionAiDir, 'config');
        fs.mkdirSync(configDir, { recursive: true });
        
        const cliConfigFile = require('path').join(configDir, 'cli.yaml');
        if (!fs.existsSync(cliConfigFile)) {
            fs.writeFileSync(cliConfigFile, `# CLI Configuration for i2-Vision
cli:
  path: ""
  enabled: true
  autoDetect: false
  minVersion: "1.0.0"
`);
        }
        
        // Create other control-plane directories
        const dirs = ['clusters', 'overrides', 'cross-module', 'learning', 'project'];
        for (const dir of dirs) {
            fs.mkdirSync(require('path').join(visionAiDir, dir), { recursive: true });
        }
        
        // Write version file
        const versionFile = require('path').join(visionAiDir, '.version');
        fs.writeFileSync(versionFile, '2.0.0');
        
        // Create requirements directory for vision layer
        const requirementsDir = require('path').join(visionAiDir, 'vision', 'requirements');
        fs.mkdirSync(requirementsDir, { recursive: true });
        
        outputChannel.appendLine('VSLFC structure initialized successfully');
        vscode.window.showInformationMessage('✓ VSLFC structure initialized! Check .vision-ai directory.');
        
        // Refresh tree view
        treeProvider.refresh();
        
    } catch (error: any) {
        outputChannel.appendLine(`Initialization failed: ${error.message}`);
        vscode.window.showErrorMessage(`Failed to initialize project: ${error.message}`);
    }
}

/**
 * Handle open project command
 */
async function handleOpenProject(item?: I2VisionTreeItem) {
    outputChannel.appendLine('Open project command invoked');

    if (item && item.metadata?.component) {
        const componentPath = item.metadata.component.path;
        outputChannel.appendLine(`Opening project: ${componentPath}`);
        
        try {
            const uri = vscode.Uri.file(componentPath);
            await vscode.commands.executeCommand('vscode.openFolder', uri, { forceNewWindow: false });
        } catch (error: any) {
            outputChannel.appendLine(`Error opening project: ${error.message}`);
            vscode.window.showErrorMessage(`Could not open project: ${error.message}`);
        }
    } else {
        // Open folder picker
        const uris = await vscode.window.showOpenDialog({
            canSelectFolders: true,
            canSelectFiles: false,
            canSelectMany: false,
            openLabel: 'Open Project'
        });
        
        if (uris && uris.length > 0) {
            await vscode.commands.executeCommand('vscode.openFolder', uris[0], { forceNewWindow: false });
        }
    }
}

/**
 * Handle open file command
 */
async function handleOpenFile(filePath: string) {
    outputChannel.appendLine(`Opening file: ${filePath}`);

    try {
        const uri = vscode.Uri.file(filePath);
        const doc = await vscode.workspace.openTextDocument(uri);
        await vscode.window.showTextDocument(doc, { preview: false });
        outputChannel.appendLine(`File opened: ${filePath}`);
    } catch (error: any) {
        outputChannel.appendLine(`Error opening file: ${error.message}`);
        vscode.window.showErrorMessage(`Could not open file: ${error.message}`);
    }
}

/**
 * Show discovery results
 */
async function showDiscoveryResults(workspaceRoot: string) {
    outputChannel.appendLine('Showing discovery results...');

    const cli = new I2VisionCLI(workspaceRoot, outputChannel);
    
    vscode.window.withProgress(
        {
            location: vscode.ProgressLocation.Notification,
            title: 'Running discovery...',
            cancellable: true
        },
        async (progress, token) => {
            try {
                progress.report({ message: 'Analyzing project structure...' });
                
                const result = await cli.runDiscovery();
                
                progress.report({ message: 'Preparing results...' });
                
                // Create a webview panel to display results
                const panel = vscode.window.createWebviewPanel(
                    'i2visionDiscovery',
                    'i2-Vision Discovery Results',
                    vscode.ViewColumn.One,
                    { enableScripts: true }
                );

                const html = `
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Discovery Results</title>
    <style>
        body { font-family: var(--vscode-font-family); padding: 20px; }
        h1 { color: var(--vscode-foreground); }
        h2 { color: var(--vscode-descriptionForeground); margin-top: 20px; }
        .component { background: var(--vscode-editor-background); padding: 10px; margin: 5px 0; border-radius: 4px; }
        .layer { border-left: 3px solid var(--vscode-button-background); padding-left: 10px; margin: 10px 0; }
        .violation-error { border-left: 3px solid var(--vscode-errorForeground); }
        .violation-warning { border-left: 3px solid var(--vscode-warningForeground); }
        .metrics { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin: 10px 0; }
        .metric { background: var(--vscode-editor-background); padding: 10px; text-align: center; border-radius: 4px; }
        .metric-value { font-size: 24px; font-weight: bold; color: var(--vscode-button-foreground); }
        .metric-label { font-size: 12px; color: var(--vscode-descriptionForeground); }
    </style>
</head>
<body>
    <h1>🔍 Discovery Results: ${result.projectName}</h1>
    
    <div class="metrics">
        <div class="metric">
            <div class="metric-value">${result.components.length}</div>
            <div class="metric-label">Components</div>
        </div>
        <div class="metric">
            <div class="metric-value">${result.relationships.length}</div>
            <div class="metric-label">Relationships</div>
        </div>
        <div class="metric">
            <div class="metric-value">${result.layers.length}</div>
            <div class="metric-label">Layers</div>
        </div>
    </div>

    <h2>📦 Components</h2>
    ${result.components.map(c => `
        <div class="component">
            <strong>${c.name}</strong> (${c.type})<br>
            <small>Path: ${c.path}</small><br>
            ${c.layer ? `<small>Layer: ${c.layer}</small>` : ''}
        </div>
    `).join('')}

    <h2>🏗️ Architecture Layers</h2>
    ${result.layers.map(l => `
        <div class="layer">
            <strong>${l.name}</strong> (Level ${l.level})<br>
            <small>Components: ${l.components.join(', ')}</small>
        </div>
    `).join('')}

    ${result.violations && result.violations.length > 0 ? `
        <h2>⚠️ Architecture Violations</h2>
        ${result.violations.map(v => `
            <div class="violation-${v.severity}">
                <strong>${v.severity.toUpperCase()}</strong>: ${v.message}<br>
                <small>${v.source} → ${v.target}</small><br>
                <small>Rule: ${v.rule}</small>
            </div>
        `).join('')}
    ` : ''}
</body>
</html>
                `;

                panel.webview.html = html;
                
                vscode.window.showInformationMessage('Discovery results displayed');
            } catch (error: any) {
                vscode.window.showErrorMessage(`Discovery failed: ${error.message}`);
                outputChannel.appendLine(`Discovery error: ${error.message}`);
            }
        }
    );
}

/**
 * Analyze architecture
 */
async function analyzeArchitecture(workspaceRoot: string) {
    outputChannel.appendLine('Analyzing architecture...');

    const cli = new I2VisionCLI(workspaceRoot, outputChannel);
    
    vscode.window.withProgress(
        {
            location: vscode.ProgressLocation.Notification,
            title: 'Analyzing architecture...',
            cancellable: true
        },
        async (progress, token) => {
            try {
                progress.report({ message: 'Checking for violations...' });
                
                const violations = await cli.analyzeViolations();
                
                if (violations.length === 0) {
                    vscode.window.showInformationMessage('✅ No architecture violations found!');
                } else {
                    const errorCount = violations.filter(v => v.severity === 'error').length;
                    const warningCount = violations.filter(v => v.severity === 'warning').length;
                    
                    const message = `Found ${violations.length} violations (${errorCount} errors, ${warningCount} warnings)`;
                    vscode.window.showWarningMessage(message, 'View Details').then(selection => {
                        if (selection === 'View Details') {
                            showDiscoveryResults(workspaceRoot);
                        }
                    });
                }
                
                outputChannel.appendLine(`Analysis complete: ${violations.length} violations`);
            } catch (error: any) {
                vscode.window.showErrorMessage(`Analysis failed: ${error.message}`);
                outputChannel.appendLine(`Analysis error: ${error.message}`);
            }
        }
    );
}

/**
 * View documentation
 */
async function viewDocumentation() {
    outputChannel.appendLine('Opening documentation...');

    const docUri = vscode.Uri.parse('https://github.com/i2-vision/i2-vision/blob/main/README.md');
    await vscode.env.openExternal(docUri);
}

/**
 * Check CLI availability (non-blocking)
 */
async function checkCLIAvailability(workspaceRoot: string) {
    outputChannel.appendLine('Checking CLI availability...');

    const cli = new I2VisionCLI(workspaceRoot, outputChannel);
    
    try {
        const isAvailable = await cli.isAvailable();
        
        if (isAvailable) {
            outputChannel.appendLine('CLI is available');
        } else {
            outputChannel.appendLine('CLI is not available');
            vscode.window.showWarningMessage(
                'i2vision CLI not found. Some features may be limited.',
                'View Setup Guide'
            ).then(selection => {
                if (selection === 'View Setup Guide') {
                    viewDocumentation();
                }
            });
        }
    } catch (error: any) {
        outputChannel.appendLine(`CLI check error: ${error.message}`);
    }
}

/**
 * Scan workspace
 */
async function scanWorkspace(workspaceRoot: string) {
    outputChannel.appendLine('Manual workspace scan initiated...');

    vscode.window.withProgress(
        {
            location: vscode.ProgressLocation.Notification,
            title: 'Scanning workspace...',
            cancellable: false
        },
        async (progress) => {
            try {
                progress.report({ message: 'Analyzing project structure...' });
                const project = await fileSystem.scanWorkspace();
                
                if (project) {
                    progress.report({ message: `Found ${project.modules.length} modules` });
                    treeProvider.refresh();
                    vscode.window.showInformationMessage(
                        `Workspace scanned: ${project.modules.length} modules, ${project.sourceFiles.length} files`
                    );
                }
            } catch (error: any) {
                vscode.window.showErrorMessage(`Scan failed: ${error.message}`);
            }
        }
    );
}

/**
 * Show file info
 */
async function showFileInfo(item?: I2VisionTreeItem) {
    outputChannel.appendLine('Showing file info...');

    if (!item) {
        vscode.window.showInformationMessage('Select a file in the tree view first.');
        return;
    }

    const metadata = item.metadata?.file || item.metadata?.component;
    
    if (!metadata) {
        vscode.window.showInformationMessage('No file information available.');
        return;
    }

    const info = [
        `**Name:** ${metadata.name}`,
        `**Path:** ${metadata.path}`,
        `**Type:** ${metadata.type || 'File'}`,
        item.metadata?.file ? `**Size:** ${formatFileSize(item.metadata.file.size || 0)}` : '',
        item.metadata?.file ? `**Modified:** ${new Date(item.metadata.file.modified || 0).toLocaleString()}` : ''
    ].filter(Boolean).join('\n\n');

    vscode.window.showInformationMessage(info);
}

/**
 * Open containing folder
 */
async function openContainingFolder(item?: I2VisionTreeItem) {
    outputChannel.appendLine('Opening containing folder...');

    if (!item || !item.metadata) {
        vscode.window.showInformationMessage('Select an item in the tree view first.');
        return;
    }

    const path = item.metadata.file?.path || item.metadata.component?.path;
    
    if (!path) {
        vscode.window.showErrorMessage('No path available for this item.');
        return;
    }

    try {
        await vscode.commands.executeCommand('revealFileInOS', vscode.Uri.file(path));
        outputChannel.appendLine(`Opened folder containing: ${path}`);
    } catch (error: any) {
        outputChannel.appendLine(`Error opening folder: ${error.message}`);
        vscode.window.showErrorMessage(`Could not open folder: ${error.message}`);
    }
}

/**
 * Format file size
 */
function formatFileSize(bytes: number): string {
    if (bytes === 0) return '0 B';
    
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

/**
 * Deactivate the extension
 */
export function deactivate() {
    outputChannel.appendLine('i2-Vision extension deactivated');
}
