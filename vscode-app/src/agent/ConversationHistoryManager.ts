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
  timestamp: number;
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
  async save(tabId: string, messages: ChatMessage[], layer: string): Promise<void> {
    const filePath = path.join(this.storageDir, `${tabId}.json`);
    const data: SavedConversation = {
      id: tabId,
      workspace: vscode.workspace.workspaceFolders?.[0]?.name || 'unknown',
      layer,
      messages,
      createdAt: Date.now(),
      updatedAt: Date.now()
    };
    fs.writeFileSync(filePath, JSON.stringify(data, null, 2));
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
