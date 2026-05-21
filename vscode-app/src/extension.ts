/**
 * i2-Vision VSCode Extension
 * 
 * Main extension entry point with Tree View integration,
 * i2vision CLI backend connectivity, and file system operations.
 */

import * as vscode from 'vscode';
import { I2VisionTreeProvider, I2VisionTreeItem } from './treeViewProvider';
import { I2VisionCLI } from './cliIntegration';
import { FileSystemIntegration, FSUtils } from './fileSystemIntegration';
import { AgentTabManager } from './agent/AgentTabManager';

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
export function activate(context: vscode.ExtensionContext) {
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
    
    // Initialize agent tab manager
    agentManager = new AgentTabManager(context, outputChannel);
    
    // Register tree view
    const treeView = vscode.window.createTreeView('i2visionTreeView', {
        treeDataProvider: treeProvider,
        showCollapseAll: true
    });
    context.subscriptions.push(treeView);

    // Register commands
    registerCommands(context, workspaceRoot);

    // Check CLI availability
    checkCLIAvailability(workspaceRoot);

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

    outputChannel.appendLine(`Selected template: ${selected.label}`);

    // Get project name
    const projectName = await vscode.window.showInputBox({
        prompt: 'Enter project name',
        placeHolder: 'My Project',
        validateInput: (value) => {
            if (!value || value.trim().length === 0) {
                return 'Project name is required';
            }
            return null;
        }
    });

    if (!projectName) {
        return;
    }

    // Collect template variables
    const variables: Record<string, string> = {
        projectName: projectName
    };

    for (const variable of selected.template.variables) {
        if (variable.required || variable.defaultValue) {
            const value = await vscode.window.showInputBox({
                prompt: `${variable.description}${variable.required ? ' (required)' : ''}`,
                placeHolder: variable.defaultValue,
                value: variable.defaultValue,
                validateInput: (val) => {
                    if (variable.required && (!val || val.trim().length === 0)) {
                        return `${variable.name} is required`;
                    }
                    return null;
                }
            });

            if (value !== undefined) {
                variables[variable.name] = value;
            } else if (variable.required) {
                vscode.window.showErrorMessage(`${variable.name} is required`);
                return;
            }
        }
    }

    // Create the project
    outputChannel.appendLine(`Creating project: ${projectName} from template: ${selected.template.name}`);
    
    const success = await cli.createProject(selected.template.name, projectName, variables);
    
    if (success) {
        vscode.window.showInformationMessage(`Project "${projectName}" created successfully! 🎉`);
        treeProvider.refresh();
        
        // Ask to open the new project
        const openChoice = await vscode.window.showInformationMessage(
            `Open project "${projectName}"?`,
            'Open',
            'Later'
        );

        if (openChoice === 'Open') {
            vscode.commands.executeCommand('vscode.openFolder', 
                vscode.Uri.file(`${workspaceRoot}/${projectName}`)
            );
        }
    }
}

/**
 * Handle open project command
 */
async function handleOpenProject(item?: I2VisionTreeItem) {
    outputChannel.appendLine('Open project command invoked');

    if (item && item.metadata?.component) {
        const component = item.metadata.component;
        if (component.path) {
            await handleOpenFile(component.path);
        }
    } else if (item && item.metadata?.file) {
        const file = item.metadata.file;
        if (file.path) {
            await handleOpenFile(file.path);
        }
    } else {
        // Open project selection
        const discovery = treeProvider.getDiscoveryCache();
        const fsProject = treeProvider.getFileSystemCache();
        
        if (fsProject) {
            outputChannel.appendLine(`Opening project: ${fsProject.name}`);
            vscode.window.showInformationMessage(`Project: ${fsProject.name}`);
        } else {
            vscode.window.showInformationMessage('No project loaded. Use the tree view to explore.');
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
        await vscode.window.showTextDocument(doc);
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

    const discovery = treeProvider.getDiscoveryCache();
    
    if (!discovery) {
        vscode.window.showInformationMessage('No discovery results available. Run a scan first.');
        return;
    }

    // Show discovery summary
    const summary = [
        `Project: ${discovery.projectName}`,
        `Components: ${discovery.components?.length || 0}`,
        `Relationships: ${discovery.relationships?.length || 0}`,
        `Layers: ${discovery.layers?.length || 0}`
    ].join('\n');

    vscode.window.showInformationMessage(summary);
    outputChannel.appendLine(`Discovery: ${summary}`);
}

/**
 * Analyze architecture
 */
async function analyzeArchitecture(workspaceRoot: string) {
    outputChannel.appendLine('Analyzing architecture...');

    const discovery = treeProvider.getDiscoveryCache();
    
    if (!discovery) {
        vscode.window.showInformationMessage('No architecture data available. Run a scan first.');
        return;
    }

    // Create a simple architecture view
    const architectureText = `# Architecture Overview\n\n` +
        `## Layers\n${(discovery.layers || []).map((l: any) => `- ${l.name} (Level ${l.level})`).join('\n')}\n\n` +
        `## Components\n${(discovery.components || []).map((c: any) => `- ${c.name} (${c.type})`).join('\n')}`;

    const doc = await vscode.workspace.openTextDocument({
        content: architectureText,
        language: 'markdown'
    });

    await vscode.window.showTextDocument(doc);
    outputChannel.appendLine('Architecture view opened');
}

/**
 * View documentation
 */
async function viewDocumentation() {
    outputChannel.appendLine('Opening documentation...');

    const docPath = vscode.Uri.file(
        require('path').join(extensionContext.extensionPath, 'README.md')
    );

    try {
        const doc = await vscode.workspace.openTextDocument(docPath);
        await vscode.window.showTextDocument(doc);
    } catch (error: any) {
        vscode.window.showInformationMessage('Documentation not found. Check the extension README.');
    }
}

/**
 * Check CLI availability
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
