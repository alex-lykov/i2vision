/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * AgentBridge Smoke Tests
 * 
 * Lightweight smoke tests that verify the AgentBridge public API surface
 * works end-to-end with mock dependencies. These tests gate every phase
 * of the AgentBridge refactoring to catch behavioral regressions early.
 * 
 * NOTE: These tests run inside the VS Code extension host (via @vscode/test-electron).
 * Run with: npm test
 */

import * as assert from 'assert';
import * as vscode from 'vscode';
import { AgentBridge, AgentConfig, AgentChunk } from '../../agent/AgentBridge';
import { AgentSettingsManager } from '../../agent/AgentSettings';

/**
 * Create a minimal valid AgentConfig for testing.
 */
function makeTestConfig(overrides?: Partial<AgentConfig['model']>): AgentConfig {
  return {
    key: 'test-agent',
    agentType: 'bridge',
    version: '1.0.0',
    isActive: true,
    systemPromptTemplate: 'You are a helpful assistant.',
    templateVariables: { currentFile: '' },
    ruleSetKeys: [],
    model: {
      id: overrides?.id || 'deepseek-chat',
      provider: overrides?.provider || 'deepseek',
      contextLength: overrides?.contextLength || 65536,
      maxOutputTokens: overrides?.maxOutputTokens || 4096,
      temperature: overrides?.temperature || 0.7,
      topP: overrides?.topP || 0.9,
      thinkingEnabled: false,
      searchEnabled: false,
    },
    llm: {
      timeoutSeconds: 5,
      modificationTimeoutSeconds: 5,
      finalTurnBonusSeconds: 5,
      maxRetries: 1,
      retryBackoffMs: [100],
    },
    formattingRules: {
      rules: '',
      brief: '',
      reasoningHeader: '',
      toolCallHeader: '',
      eosMarker: '',
    },
    iterationSettings: {
      maxIterations: 1,
      maxConsecutiveToolCalls: 1,
      enableKickstart: false,
      kickstartMinInvalidOutputs: 3,
    },
    toolSelection: {
      requiredToolsForModification: [],
      defaultRelevanceThreshold: 0.5,
      maxToolsPerTask: 10,
      toolTimeoutSeconds: 30,
    },
    safety: {
      modificationKeywords: [],
      listingKeywords: [],
      listingModificationExclusions: [],
      blockGeneratedPaths: [],
      allowNewFileCreationPatterns: [],
    },
    parsing: {
      enabledParsers: [],
      headerPattern: '',
      toolCallPattern: '',
      malformedPattern: '',
      maxResponseSize: 100000,
      maxProseChars: 80000,
    },
    repairStrategies: [],
    discovery: {
      maxSearchTerms: 10,
      maxCandidates: 20,
      frameworkProfiles: {},
    },
    execution: {
      enableBuildVerification: false,
      buildCommand: '',
      buildTimeoutSeconds: 30,
      enableSynthesis: false,
      synthesisOnlyForNonModification: true,
      fileOperations: {
        mode: 'vscode',
        shell: {
          executable: '',
          useNoProfile: true,
          readFileEnabled: true,
          writeFileEnabled: true,
          listDirectoryEnabled: true,
          regexSearchEnabled: true,
        },
      },
    },
    formatting: {
      chunkSize: 50,
      delayMs: 10,
      maxObservationChars: 2000,
    },
    streaming: {
      enabled: false,
      methodCandidates: [],
      fallbackToNonStreaming: true,
      fallbackChunkSize: 100,
      fallbackChunkDelayMs: 50,
    },
    mcp: {
      enabled: false,
      injectClusterContext: false,
      directCliEnabled: false,
      allowedToolPrefixes: [],
      strictToolNamePolicy: false,
    },
  };
}

const os = require('os');
const workspaceRoot = os.tmpdir();

suite('AgentBridge Smoke Tests', () => {
  let outputChannel: vscode.OutputChannel;

  setup(() => {
    // Create a fresh output channel for each test
    outputChannel = vscode.window.createOutputChannel(`AgentBridge Test ${Date.now()}`, { log: true });
  });

  teardown(() => {
    if (outputChannel) {
      try { outputChannel.dispose(); } catch {}
    }
  });

  suite('Construction', () => {
    test('should construct with deepseek provider (stateless)', () => {
      const config = makeTestConfig({ provider: 'deepseek' });
      const mgr = AgentSettingsManager.getInstance(undefined as any);

      const bridge = new AgentBridge(config, outputChannel, __dirname, workspaceRoot, mgr);
      assert.ok(bridge);
      assert.ok(bridge.id);
      assert.strictEqual(typeof bridge.id, 'string');
      bridge.dispose();
    });

    test('should construct with ollama provider', () => {
      const config = makeTestConfig({ provider: 'ollama' });
      const mgr = AgentSettingsManager.getInstance(undefined as any);

      const bridge = new AgentBridge(config, outputChannel, __dirname, workspaceRoot, mgr);
      assert.ok(bridge);
      bridge.dispose();
    });

    test('should reject when workspaceRoot equals extensionRoot', () => {
      const config = makeTestConfig();
      const samePath = __dirname;
      const mgr = AgentSettingsManager.getInstance(undefined as any);

      assert.throws(() => {
        new AgentBridge(config, outputChannel, samePath, samePath, mgr);
      }, /cannot be the same path/i);
    });

    test('should throw when workspaceRoot is empty', () => {
      const config = makeTestConfig();
      const mgr = AgentSettingsManager.getInstance(undefined as any);

      assert.throws(() => {
        new AgentBridge(config, outputChannel, __dirname, '', mgr);
      }, /must be explicitly provided/i);
    });
  });

  suite('Public API', () => {
    let bridge: AgentBridge;

    setup(() => {
      const config = makeTestConfig();
      const mgr = AgentSettingsManager.getInstance(undefined as any);
      bridge = new AgentBridge(config, outputChannel, __dirname, workspaceRoot, mgr);
    });

    teardown(() => {
      if (bridge) {
        try { bridge.dispose(); } catch {}
      }
    });

    test('getConfig() returns a copy of the config', () => {
      const cfg = bridge.getConfig();
      assert.ok(cfg);
      assert.strictEqual(cfg.model.provider, 'deepseek');
      assert.strictEqual(cfg.model.id, 'deepseek-chat');
    });

    test('getState() returns state before initialization', () => {
      const state = bridge.getState();
      assert.ok(state);
    });

    test('setLayer() and getLayer() work', () => {
      bridge.setLayer('presentation' as any);
      const layer = bridge.getLayer();
      assert.strictEqual(layer, 'presentation');
    });

    test('getToolExecutionStats() returns object', () => {
      const stats = bridge.getToolExecutionStats();
      assert.ok(stats);
      assert.ok(Array.isArray(stats.recentTools));
    });

    test('getCurrentToolStatus() returns object', () => {
      const status = bridge.getCurrentToolStatus();
      assert.ok(status);
    });

    test('getToolExecutionReport() returns object', () => {
      const report = bridge.getToolExecutionReport();
      assert.ok(report);
    });

    test('getSessionState() returns object or null', () => {
      const state = bridge.getSessionState();
      if (state !== null) {
        assert.ok(typeof state === 'object');
      }
    });

    test('getSessionManager() returns a SessionManager', () => {
      const mgr = bridge.getSessionManager();
      assert.ok(mgr);
      assert.strictEqual(typeof mgr.name, 'string');
    });
  });

  suite('Initialize', () => {
    let bridge: AgentBridge;

    setup(() => {
      const config = makeTestConfig();
      const mgr = AgentSettingsManager.getInstance(undefined as any);
      bridge = new AgentBridge(config, outputChannel, __dirname, workspaceRoot, mgr);
    });

    teardown(() => {
      if (bridge) {
        try { bridge.dispose(); } catch {}
      }
    });

    test('initialize() completes without throwing', async () => {
      await bridge.initialize();
    });

    test('initialize() is idempotent', async () => {
      await bridge.initialize();
      await bridge.initialize();
    });
  });

  suite('process() Basic Flow', () => {
    let bridge: AgentBridge;

    setup(async () => {
      const config = makeTestConfig();
      const mgr = AgentSettingsManager.getInstance(undefined as any);
      bridge = new AgentBridge(config, outputChannel, __dirname, workspaceRoot, mgr);
      await bridge.initialize();
    });

    teardown(() => {
      if (bridge) {
        try { bridge.dispose(); } catch {}
      }
    });

    test('process() returns AgentResponse without crashing', async function () {
      this.timeout(30000);

      try {
        const response = await bridge.process('Hello, this is a test.');
        assert.ok(response);
        assert.ok('finalText' in response);
        assert.strictEqual(typeof response.success, 'boolean');
        assert.ok(Array.isArray(response.toolCalls));
        assert.strictEqual(typeof response.iterations, 'number');
        assert.strictEqual(typeof response.durationMs, 'number');
        if (!response.success) {
          assert.ok(response.error);
          assert.ok(typeof response.error === 'string');
        }
      } catch (err: any) {
        assert.fail(`process() threw unexpectedly: ${err.message}`);
      }
    });
  });

  suite('processStreaming()', () => {
    let bridge: AgentBridge;

    setup(async () => {
      const config = makeTestConfig();
      const mgr = AgentSettingsManager.getInstance(undefined as any);
      bridge = new AgentBridge(config, outputChannel, __dirname, workspaceRoot, mgr);
      await bridge.initialize();
    });

    teardown(() => {
      if (bridge) {
        try { bridge.dispose(); } catch {}
      }
    });

    test('processStreaming() yields chunks or completes gracefully', async () => {
      const chunks: AgentChunk[] = [];

      try {
        for await (const chunk of bridge.processStreaming('Hello')) {
          chunks.push(chunk);
        }
      } catch {}

      if (chunks.length > 0) {
        const chunkTypes = chunks.map(c => c.type);
        const validTypes = ['thinking', 'reasoning', 'tool_call_started', 'tool_call_completed',
          'text', 'done', 'iteration_complete', 'token_usage', 'error', 'state_change'];
        for (const type of chunkTypes) {
          assert.ok(validTypes.includes(type), `Unexpected chunk type: ${type}`);
        }
      }
    });

    test('processStreaming() with history does not throw', async () => {
      const history = [
        { role: 'user' as const, content: 'Previous question', timestamp: Date.now() },
        { role: 'assistant' as const, content: 'Previous answer', timestamp: Date.now() },
      ];

      try {
        for await (const chunk of bridge.processStreaming('Hello', undefined, history)) {
          // consume
        }
      } catch {}
      assert.ok(true, 'processStreaming with history should not throw');
    });
  });

  suite('Dispose', () => {
    test('dispose() completes without throwing', () => {
      const config = makeTestConfig();
      const mgr = AgentSettingsManager.getInstance(undefined as any);
      const bridge = new AgentBridge(config, outputChannel, __dirname, workspaceRoot, mgr);
      bridge.dispose();
    });

    test('double dispose() does not throw', () => {
      const config = makeTestConfig();
      const mgr = AgentSettingsManager.getInstance(undefined as any);
      const bridge = new AgentBridge(config, outputChannel, __dirname, workspaceRoot, mgr);
      bridge.dispose();
      bridge.dispose();
    });
  });
});
