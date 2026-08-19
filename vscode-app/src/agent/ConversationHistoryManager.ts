/**
 * ConversationHistoryManager - Persists agent conversations to JSON files
 * 
 * Simple, file-based storage for conversation history.
 * Saves to .vision-ai/history/{tabId}.json
 */

import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';

/**
 * Chat message in conversation history
 */
export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  toolCalls?: {
    toolName: string;
    args: Record<string, any>;
    result?: string;
  }[];
  reasoning?: string;
  timestamp: number;
}

/**
 * Session state that persists across agent recreation
 */
export interface AgentSessionState {
  visitedPaths: string[];
  searchCache: Array<{
    query: string;
    results: any[];
    timestamp: number;
    workspaceRoot: string;
  }>;
  resolvedDomain?: {
    primaryDomain: string;
    confidence: number;
    suggestedDirectories: string[];
    rationale?: string;
  };
  workingDirectory?: string;
  toolFilter?: 'all' | 'action_only';
  forceActionMode?: boolean;
  failedSearchCount?: number;
  lastSearchPattern?: string;

  /** Provider-specific session state (e.g. 3D LLM proxy session) */
  proxySession?: {
    id: string | null;
    messageCount: number;
    createdAt: number;
    accountId: string | null;
    continuityCounter: number;
    retryAttempts: number;
  };

  /** Last known token usage for context meter restoration */
  lastTokenUsage?: { prompt: number; completion: number; total: number };

  /** Serialized SessionManager state for restoration */
  sessionManagerState?: object;
}

/**
 * Saved conversation data structure
 */
export interface SavedConversation {
  id: string;
  workspace: string;
  layer: string;
  messages: ChatMessage[];
  createdAt: number;
  updatedAt: number;
  contextTitle?: string;
  sessionState?: AgentSessionState;
}

/**
 * Manages conversation persistence to JSON files
 */
export class ConversationHistoryManager {
  private storageDir: string;

  constructor(workspaceRoot: string) {
    this.storageDir = path.join(workspaceRoot, '.vision-ai', 'history');
    if (!fs.existsSync(this.storageDir)) {
      fs.mkdirSync(this.storageDir, { recursive: true });
    }
  }

  /**
   * Save conversation to JSON file
   */
  async save(tabId: string, messages: ChatMessage[], layer: string, sessionState?: AgentSessionState): Promise<void> {
    const contextTitle = this.extractContextTitle(messages);
    const filePath = path.join(this.storageDir, `${tabId}.json`);
    const data: SavedConversation = {
      id: tabId,
      workspace: vscode.workspace.workspaceFolders?.[0]?.name || 'unknown',
      layer,
      messages,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      contextTitle,
      sessionState
    };
    fs.writeFileSync(filePath, JSON.stringify(data, null, 2));
  }

  /**
   * Extract context title from first user message
   */
  private extractContextTitle(messages: ChatMessage[]): string {
    const firstUserMessage = messages.find(m => m.role === 'user');
    if (!firstUserMessage?.content) return 'Untitled';
    const content = firstUserMessage.content.trim();
    // Take first 50 characters or first line, whichever is shorter
    const title = content.split('\n')[0].substring(0, 50);
    return title.length > 50 ? title + '...' : title;
  }

  /**
   * Load conversation from JSON file
   */
  async load(tabId: string): Promise<SavedConversation | null> {
    const filePath = path.join(this.storageDir, `${tabId}.json`);
    if (!fs.existsSync(filePath)) {
      return null;
    }
    const content = fs.readFileSync(filePath, 'utf-8');
    return JSON.parse(content) as SavedConversation;
  }

  /**
   * List all saved conversation IDs
   */
  async list(): Promise<string[]> {
    if (!fs.existsSync(this.storageDir)) {
      return [];
    }
    return fs.readdirSync(this.storageDir)
      .filter(file => file.endsWith('.json'))
      .map(file => file.replace('.json', ''));
  }

  /**
   * Delete a conversation
   */
  async delete(tabId: string): Promise<void> {
    const filePath = path.join(this.storageDir, `${tabId}.json`);
    if (fs.existsSync(filePath)) {
      fs.unlinkSync(filePath);
    }
  }
}
