/**
 * VS Code Agent Bridge Implementation
 * 
 * Extends UniversalAgentBridge with VS Code-specific context building.
 */

import * as vscode from 'vscode';
import { AgentContext, VSLFCContext } from './Protocol';
import { UniversalAgentBridge } from './UniversalAgentBridge';
import { AgentTransport } from './Transport';
import { I2VisionUiConfig } from './I2VisionUiConfig';

/**
 * VS Code-specific agent bridge
 */
export class VsCodeAgentBridge extends UniversalAgentBridge {
  constructor(transport: AgentTransport, uiConfig: I2VisionUiConfig) {
    super(transport, uiConfig);
  }

  /**
   * Build context from VS Code environment
   */
  protected async buildContext(): Promise<AgentContext> {
    const context: AgentContext = {
      workspaceRoot: this.getWorkspaceRoot(),
      currentFile: this.getCurrentFilePath(),
      selectedFiles: await this.getSelectedFiles(),
      cursorPosition: this.getCursorPosition(),
      discoveryCache: this.getDiscoveryCachePath(),
      vslfcContext: await this.getVslfcContext()
    };

    console.log('[VsCodeAgentBridge] Context built:', {
      workspaceRoot: context.workspaceRoot,
      currentFile: context.currentFile,
      selectedFiles: context.selectedFiles?.length,
      hasVslfcContext: !!context.vslfcContext
    });

    return context;
  }

  /**
   * Get workspace root path
   */
  private getWorkspaceRoot(): string {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) {
      throw new Error('No workspace folder open');
    }
    return workspaceFolders[0].uri.fsPath;
  }

  /**
   * Get currently active file path
   */
  private getCurrentFilePath(): string | undefined {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
      return undefined;
    }
    return editor.document.uri.fsPath;
  }

  /**
   * Get selected files from explorer
   */
  private async getSelectedFiles(): Promise<string[] | undefined> {
    try {
      // VS Code doesn't expose selected files directly, but we can get from editor
      const editor = vscode.window.activeTextEditor;
      if (!editor) {
        return undefined;
      }

      // Return current file as selected
      return [editor.document.uri.fsPath];
    } catch (error) {
      console.error('[VsCodeAgentBridge] Error getting selected files:', error);
      return undefined;
    }
  }

  /**
   * Get cursor position in active editor
   */
  private getCursorPosition(): { line: number; character: number } | undefined {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
      return undefined;
    }

    const position = editor.selection.active;
    return {
      line: position.line,
      character: position.character
    };
  }

  /**
   * Get discovery cache path
   */
  private getDiscoveryCachePath(): string | undefined {
    const workspaceRoot = this.getWorkspaceRoot();
    return `${workspaceRoot}/.vscode/i2vision/discovery-cache.json`;
  }

  /**
   * Get VSLFC context for current file
   */
  private async getVslfcContext(): Promise<VSLFCContext | undefined> {
    const currentFile = this.getCurrentFilePath();
    if (!currentFile) {
      return undefined;
    }

    try {
      // Try to load VSLFC context from cache
      const fs = require('fs');
      const cachePath = this.getDiscoveryCachePath();
      
      if (cachePath && fs.existsSync(cachePath)) {
        const cache = JSON.parse(fs.readFileSync(cachePath, 'utf-8'));
        
        // Find component for current file
        const component = cache.components?.find((c: any) => 
          c.filePath === currentFile || c.files?.includes(currentFile)
        );

        if (component) {
          const layer = cache.layers?.find((l: any) => 
            l.name === component.layer
          );

          return {
            filePath: currentFile,
            component: {
              name: component.name,
              type: component.type,
              layer: component.layer
            },
            layer: layer ? {
              name: layer.name,
              level: layer.level,
              allowedDependencies: layer.allowedDependencies || []
            } : undefined,
            responsibilities: component.responsibilities,
            dependencies: component.dependencies,
            dependents: component.dependents,
            metrics: component.metrics
          };
        }
      }
    } catch (error: any) {
      console.error('[VsCodeAgentBridge] Error loading VSLFC context:', error.message);
    }

    return undefined;
  }

  /**
   * Override notification handlers for VS Code UI
   */
  protected onIteration(data: any): void {
    // Emit event for UI to display iteration progress
    vscode.window.withProgress(
      {
        location: vscode.ProgressLocation.Notification,
        title: 'Agent is thinking...',
        cancellable: true
      },
      async (progress, token) => {
        progress.report({ increment: 10 });
      }
    );
  }

  protected onToolCall(data: any): void {
    console.log('[VsCodeAgentBridge] Tool call:', data.toolName);
    // Could show tool call in chat UI
  }

  protected onProgress(data: any): void {
    // Update progress bar
  }

  protected onStream(data: any): void {
    // Stream text to chat UI
  }

  protected onStatus(data: any): void {
    // Update status bar
  }
}

/**
 * Create a VS Code agent bridge with stdio transport
 */
export function createVsCodeBridge(command: string, cwd?: string): VsCodeAgentBridge {
  const { StdioTransport } = require('./StdioTransport');
  const { loadUiConfig } = require('./I2VisionUiConfig');

  const transport = new StdioTransport({
    command,
    cwd: cwd || vscode.workspace.workspaceFolders?.[0]?.uri.fsPath
  });

  const uiConfig = loadUiConfig();

  return new VsCodeAgentBridge(transport, uiConfig);
}

/**
 * Create a VS Code agent bridge with HTTP transport
 */
export function createVsCodeHttpBridge(baseUrl: string): VsCodeAgentBridge {
  const { HttpTransport } = require('./HttpTransport');
  const { loadUiConfig } = require('./I2VisionUiConfig');

  const transport = new HttpTransport({ baseUrl });
  const uiConfig = loadUiConfig();

  return new VsCodeAgentBridge(transport, uiConfig);
}
