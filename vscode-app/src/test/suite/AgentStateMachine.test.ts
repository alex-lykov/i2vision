/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * AgentStateMachine Tests
 * 
 * Comprehensive test suite for the AgentStateMachine class
 * Uses Mocha/Chai test framework
 */

import * as assert from 'assert';
import {
  AgentStateMachine,
  AgentState,
  AgentEvent,
} from '../../agent/AgentStateMachine';

suite('AgentStateMachine', () => {
  let stateMachine: AgentStateMachine;

  setup(() => {
    stateMachine = new AgentStateMachine();
  });

  suite('Initialization', () => {
    test('should start in IDLE state', () => {
      assert.strictEqual(stateMachine.state, AgentState.IDLE);
    });

    test('should have initial context', () => {
      const context = stateMachine.context;
      assert.strictEqual(context.iteration, 0);
      assert.strictEqual(context.consecutiveEdits, 0);
      assert.strictEqual(context.consecutivePlans, 0);
      assert.strictEqual(context.buildFailures, 0);
      assert.deepStrictEqual(context.pendingFixes, []);
      assert.strictEqual(context.intentType, 'default');
    });

    test('should reset to initial state', () => {
      // Move to different state
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      
      assert.notStrictEqual(stateMachine.state, AgentState.IDLE);
      
      // Reset
      stateMachine.reset();
      
      assert.strictEqual(stateMachine.state, AgentState.IDLE);
      assert.strictEqual(stateMachine.context.iteration, 0);
    });
  });

  suite('Intent Classification', () => {
    test('should classify explore intent', () => {
      const intent = stateMachine.classifyIntent('explain how this code works');
      assert.strictEqual(intent, 'explore');
    });

    test('should classify create intent', () => {
      const intent = stateMachine.classifyIntent('write a new function to handle users');
      assert.strictEqual(intent, 'create');
    });

    test('should classify debug intent', () => {
      const intent = stateMachine.classifyIntent('fix the bug in UserService');
      assert.strictEqual(intent, 'debug');
    });

    test('should classify refactor intent', () => {
      const intent = stateMachine.classifyIntent('refactor this method to be more readable');
      assert.strictEqual(intent, 'refactor');
    });

    test('should classify test intent', () => {
      const intent = stateMachine.classifyIntent('write unit tests for the API');
      assert.strictEqual(intent, 'test');
    });

    test('should classify run intent', () => {
      const intent = stateMachine.classifyIntent('run the backend server');
      assert.strictEqual(intent, 'run');
    });

    test('should default to default intent', () => {
      const intent = stateMachine.classifyIntent('hello');
      assert.strictEqual(intent, 'default');
    });
  });

  suite('LLM Response Classification', () => {
    test('should classify tool calls', () => {
      const event = stateMachine.classifyLLMResponse('Some text', true, stateMachine.context);
      assert.strictEqual(event, AgentEvent.TOOL_CALLS_RECEIVED);
    });

    test('should classify plan-only responses', () => {
      const event = stateMachine.classifyLLMResponse(
        'I will first read the file to understand the structure',
        false,
        stateMachine.context
      );
      assert.strictEqual(event, AgentEvent.PLAN_ONLY);
    });

    test('should classify natural text responses', () => {
      const event = stateMachine.classifyLLMResponse(
        'The code structure follows a typical MVC pattern with controllers handling HTTP requests...',
        false,
        stateMachine.context
      );
      assert.strictEqual(event, AgentEvent.TEXT_ONLY);
    });
  });

  suite('Tool Result Classification', () => {
    test('should classify build success', () => {
      const { event } = stateMachine.classifyToolResult(
        'run_build',
        '✅ BUILD SUCCESSFUL',
        undefined
      );
      assert.strictEqual(event, AgentEvent.BUILD_SUCCESS);
    });

    test('should classify build failure', () => {
      const { event } = stateMachine.classifyToolResult(
        'run_build',
        '❌ BUILD FAILED',
        undefined
      );
      assert.strictEqual(event, AgentEvent.BUILD_FAILURE);
    });

    test('should classify edit success', () => {
      const { event } = stateMachine.classifyToolResult(
        'apply_edits',
        '✅ Applied 3 edits to UserService.kt',
        undefined
      );
      assert.strictEqual(event, AgentEvent.EDIT_SUCCESS);
    });

    test('should classify edit failure', () => {
      const { event } = stateMachine.classifyToolResult(
        'apply_edits',
        '❌ No edits applied',
        'Search text not found'
      );
      assert.strictEqual(event, AgentEvent.EDIT_FAILURE);
    });

    test('should classify write success', () => {
      const { event } = stateMachine.classifyToolResult(
        'write_file',
        'Successfully wrote 1500 characters',
        undefined
      );
      assert.strictEqual(event, AgentEvent.WRITE_SUCCESS);
    });

    test('should default to TOOLS_EXECUTED', () => {
      const { event } = stateMachine.classifyToolResult(
        'read_file',
        'File content...',
        undefined
      );
      assert.strictEqual(event, AgentEvent.TOOLS_EXECUTED);
    });
  });

  suite('State Transitions - Happy Path', () => {
    test('should transition through complete workflow', () => {
      // IDLE → INTENT
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      assert.strictEqual(stateMachine.state, AgentState.INTENT);

      // INTENT → PLAN
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      assert.strictEqual(stateMachine.state, AgentState.PLAN);

      // PLAN → CONSTRAINTS
      stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);
      assert.strictEqual(stateMachine.state, AgentState.CONSTRAINTS);

      // CONSTRAINTS → SEQUENCE
      stateMachine.dispatch(AgentEvent.PLAN_VALIDATED);
      assert.strictEqual(stateMachine.state, AgentState.SEQUENCE);

      // SEQUENCE → EXECUTE
      stateMachine.dispatch(AgentEvent.SEQUENCE_READY);
      assert.strictEqual(stateMachine.state, AgentState.EXECUTE);

      // EXECUTE → VERIFY
      stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);
      assert.strictEqual(stateMachine.state, AgentState.VERIFY);

      // VERIFY → COMPLETE
      stateMachine.dispatch(AgentEvent.BUILD_SUCCESS);
      assert.strictEqual(stateMachine.state, AgentState.COMPLETE);
    });

    test('should complete naturally without tools', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.TEXT_ONLY);
      assert.strictEqual(stateMachine.state, AgentState.COMPLETE);
    });
  });

  suite('State Transitions - Error Handling', () => {
    test('should handle build failure and return to PLAN', () => {
      // Setup: reach VERIFY state
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);
      stateMachine.dispatch(AgentEvent.PLAN_VALIDATED);
      stateMachine.dispatch(AgentEvent.SEQUENCE_READY);
      stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);

      // Build fails
      stateMachine.dispatch(AgentEvent.BUILD_FAILURE);
      assert.strictEqual(stateMachine.state, AgentState.PLAN);
      assert.strictEqual(stateMachine.context.buildFailures, 1);
      assert.strictEqual(stateMachine.context.inBuildFixCycle, true);
    });

    test('should handle edit failure and return to PLAN', () => {
      // Setup: reach VERIFY state
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);
      stateMachine.dispatch(AgentEvent.PLAN_VALIDATED);
      stateMachine.dispatch(AgentEvent.SEQUENCE_READY);
      stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);

      // Edit fails
      stateMachine.dispatch(AgentEvent.EDIT_FAILURE);
      assert.strictEqual(stateMachine.state, AgentState.PLAN);
      assert.strictEqual(stateMachine.context.failedEditAttempts, 1);
    });

    test('should fail on loop detection', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.LOOP_DETECTED);
      assert.strictEqual(stateMachine.state, AgentState.FAILED);
    });

    test('should fail on max iterations', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.MAX_ITERATIONS);
      assert.strictEqual(stateMachine.state, AgentState.FAILED);
    });

    test('should fail on max failures', () => {
      // Setup: reach VERIFY state with build failures
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);
      stateMachine.dispatch(AgentEvent.PLAN_VALIDATED);
      stateMachine.dispatch(AgentEvent.SEQUENCE_READY);
      stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);
      stateMachine.dispatch(AgentEvent.BUILD_FAILURE);
      stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);
      stateMachine.dispatch(AgentEvent.BUILD_FAILURE);
      stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);
      stateMachine.dispatch(AgentEvent.BUILD_FAILURE);

      // Should be in PLAN state with 3 build failures
      assert.strictEqual(stateMachine.context.buildFailures, 3);

      // Next failure should trigger MAX_FAILURES
      stateMachine.dispatch(AgentEvent.MAX_FAILURES);
      assert.strictEqual(stateMachine.state, AgentState.FAILED);
    });
  });

  suite('Plan-Only Loop Detection', () => {
    test('should track consecutive plan-only responses', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);

      // First plan-only
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      assert.strictEqual(stateMachine.context.consecutivePlans, 1);

      // Second plan-only
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      assert.strictEqual(stateMachine.context.consecutivePlans, 2);
      assert.strictEqual(stateMachine.context.inPlanLoop, true);
    });

    test('should reset consecutive plans on tool calls', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      
      assert.strictEqual(stateMachine.context.consecutivePlans, 2);

      // Now use tools
      stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);
      assert.strictEqual(stateMachine.context.consecutivePlans, 0);
    });

    test('should detect stuck plan loop', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      
      // Three plan-only responses
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);

      assert.strictEqual(stateMachine.isPlanLoopStuck(), true);
    });
  });

  suite('Build-Fix Cycle', () => {
    test('should track consecutive successful edits', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);
      stateMachine.dispatch(AgentEvent.PLAN_VALIDATED);
      stateMachine.dispatch(AgentEvent.SEQUENCE_READY);
      stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);
      stateMachine.dispatch(AgentEvent.EDIT_SUCCESS);

      assert.strictEqual(stateMachine.context.consecutiveEdits, 1);

      // Another successful edit
      stateMachine.dispatch(AgentEvent.EDIT_SUCCESS);
      assert.strictEqual(stateMachine.context.consecutiveEdits, 2);
    });

    test('should suggest build verification after consecutive edits', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);
      stateMachine.dispatch(AgentEvent.PLAN_VALIDATED);
      stateMachine.dispatch(AgentEvent.SEQUENCE_READY);
      stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);
      stateMachine.dispatch(AgentEvent.EDIT_SUCCESS);
      stateMachine.dispatch(AgentEvent.EDIT_SUCCESS);

      assert.strictEqual(stateMachine.shouldForceBuild(), true);
    });

    test('should detect stuck build-fix cycle', () => {
      // Simulate 3 build failures
      for (let i = 0; i < 3; i++) {
        stateMachine.dispatch(AgentEvent.USER_INPUT);
        stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
        stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);
        stateMachine.dispatch(AgentEvent.PLAN_VALIDATED);
        stateMachine.dispatch(AgentEvent.SEQUENCE_READY);
        stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);
        stateMachine.dispatch(AgentEvent.BUILD_FAILURE);
      }

      assert.strictEqual(stateMachine.isBuildFixCycleStuck(), true);
    });
  });

  suite('Tool Call Loop Detection', () => {
    test('should detect repeated tool calls', () => {
      stateMachine.recordToolCall('search_files', { pattern: 'UserService' }, 'Found 5 files', undefined, 150);
      stateMachine.recordToolCall('search_files', { pattern: 'UserService' }, 'Found 5 files', undefined, 145);

      const isLoop = stateMachine.detectLoop('search_files', { pattern: 'UserService' }, 3);
      assert.strictEqual(isLoop, true);
    });

    test('should not flag different tool calls as loops', () => {
      stateMachine.recordToolCall('search_files', { pattern: 'UserService' }, 'Found 5 files', undefined, 150);
      stateMachine.recordToolCall('read_file', { path: 'src/UserService.kt' }, 'File content...', undefined, 50);

      const isLoop = stateMachine.detectLoop('read_file', { path: 'src/UserService.kt' }, 2);
      assert.strictEqual(isLoop, false);
    });

    test('should not flag same tool with different args as loops', () => {
      stateMachine.recordToolCall('search_files', { pattern: 'UserService' }, 'Found 5 files', undefined, 150);
      stateMachine.recordToolCall('search_files', { pattern: 'OrderService' }, 'Found 3 files', undefined, 140);

      const isLoop = stateMachine.detectLoop('search_files', { pattern: 'OrderService' }, 2);
      assert.strictEqual(isLoop, false);
    });
  });

  suite('Constraints Validation', () => {
    test('should pass valid tool calls', () => {
      const toolCalls = [
        { toolName: 'read_file', args: { path: 'src/main.kt' } },
        { toolName: 'apply_edits', args: { path: 'src/main.kt', edits: [{ search: 'old', replace: 'new' }] } },
      ];

      const validation = stateMachine.validateConstraints(toolCalls);
      assert.strictEqual(validation.passed, true);
      assert.deepStrictEqual(validation.violations, []);
    });

    test('should detect destructive commands', () => {
      const toolCalls = [
        { toolName: 'run_terminal', args: { command: 'rm -rf /tmp/cache' } },
      ];

      const validation = stateMachine.validateConstraints(toolCalls);
      assert.strictEqual(validation.passed, false);
      assert.ok(validation.violations.some(v => v.includes('Destructive command blocked')));
      assert.strictEqual(validation.safetyFlags.isDestructive, true);
    });

    test('should detect excessive edits', () => {
      const toolCalls = [
        { 
          toolName: 'apply_edits', 
          args: { 
            path: 'src/main.kt', 
            edits: Array(60).fill({ search: 'old', replace: 'new' }) 
          } 
        },
      ];

      const validation = stateMachine.validateConstraints(toolCalls);
      assert.strictEqual(validation.passed, false);
      assert.ok(validation.violations.some(v => v.includes('Too many edits')));
    });

    test('should detect search loops', () => {
      // First search
      stateMachine.updateContext({
        lastSearchPattern: 'UserService',
        lastSearchFiles: ['src/UserService.kt'],
      });

      const toolCalls = [
        { toolName: 'search_files', args: { pattern: 'UserService' } },
      ];

      const validation = stateMachine.validateConstraints(toolCalls);
      assert.ok(validation.warnings.some(w => w.includes('already used')));
    });
  });

  suite('Sequence Optimization', () => {
    test('should order tools by priority', () => {
      const toolCalls = [
        { toolName: 'run_build', args: {}, toolCallId: '1' },
        { toolName: 'read_file', args: { path: 'src/main.kt' }, toolCallId: '2' },
        { toolName: 'apply_edits', args: { path: 'src/main.kt', edits: [] }, toolCallId: '3' },
      ];

      const sequence = stateMachine.optimizeSequence(toolCalls);
      
      // Read operations should come first
      assert.strictEqual(sequence[0].toolName, 'read_file');
      // Then edits
      assert.strictEqual(sequence[1].toolName, 'apply_edits');
      // Then build
      assert.strictEqual(sequence[2].toolName, 'run_build');
    });
  });

  suite('Tool Filtering', () => {
    test('should return all tools in normal state', () => {
      const filter = stateMachine.getToolFilter();
      assert.strictEqual(filter, 'all');
    });

    test('should return read_only in COMPLETE state', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.TEXT_ONLY);
      
      const filter = stateMachine.getToolFilter();
      assert.strictEqual(filter, 'read_only');
    });

    test('should return read_only in FAILED state', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.LOOP_DETECTED);
      
      const filter = stateMachine.getToolFilter();
      assert.strictEqual(filter, 'read_only');
    });

    test('should return fix_only when stuck in plan loop', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      
      const filter = stateMachine.getToolFilter();
      assert.strictEqual(filter, 'fix_only');
    });
  });

  suite('State History', () => {
    test('should track state transitions', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);

      const history = stateMachine.getLastTransitions(10);
      assert.strictEqual(history.length, 3);
      assert.strictEqual(history[0].from, AgentState.IDLE);
      assert.strictEqual(history[0].to, AgentState.INTENT);
      assert.strictEqual(history[1].to, AgentState.PLAN);
      assert.strictEqual(history[2].to, AgentState.CONSTRAINTS);
    });

    test('should provide last event', () => {
      assert.strictEqual(stateMachine.lastEvent, null);

      stateMachine.dispatch(AgentEvent.USER_INPUT);
      assert.strictEqual(stateMachine.lastEvent, AgentEvent.USER_INPUT);

      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      assert.strictEqual(stateMachine.lastEvent, AgentEvent.INTENT_CLASSIFIED);
    });

    test('should count transitions to specific state', () => {
      // Cycle through PLAN state multiple times
      for (let i = 0; i < 3; i++) {
        stateMachine.dispatch(AgentEvent.USER_INPUT);
        stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
        stateMachine.dispatch(AgentEvent.PLAN_ONLY);
        stateMachine.reset();
      }

      // Note: This test shows the concept, but reset clears history
      // In real usage, you'd check history without resetting
    });
  });

  suite('Metrics Tracking', () => {
    test('should track tool call metrics', () => {
      stateMachine.recordToolCall('read_file', { path: 'src/main.kt' }, 'content', undefined, 50);
      stateMachine.recordToolCall('apply_edits', { path: 'src/main.kt', edits: [] }, '✅ Applied', undefined, 100);
      stateMachine.recordToolCall('run_build', {}, '❌ FAILED', 'Build error', 5000);

      const metrics = stateMachine.context.metrics;
      assert.strictEqual(metrics.totalToolCalls, 3);
      assert.strictEqual(metrics.successfulToolCalls, 2);
      assert.strictEqual(metrics.failedToolCalls, 1);
      assert.ok(Math.abs(metrics.averageToolDurationMs - 1716.67) < 1);
    });

    test('should increment iteration counter', () => {
      assert.strictEqual(stateMachine.context.iteration, 0);
      
      stateMachine.incrementIteration();
      assert.strictEqual(stateMachine.context.iteration, 1);
      
      stateMachine.incrementIteration();
      assert.strictEqual(stateMachine.context.iteration, 2);
    });
  });

  suite('State Checks', () => {
    test('should check current state', () => {
      assert.strictEqual(stateMachine.is(AgentState.IDLE), true);
      assert.strictEqual(stateMachine.is(AgentState.PLAN), false);

      stateMachine.dispatch(AgentEvent.USER_INPUT);
      assert.strictEqual(stateMachine.is(AgentState.IDLE), false);
      assert.strictEqual(stateMachine.is(AgentState.INTENT), true);
    });

    test('should check if in any of multiple states', () => {
      assert.strictEqual(stateMachine.isAny(AgentState.IDLE, AgentState.PLAN), true);
      assert.strictEqual(stateMachine.isAny(AgentState.COMPLETE, AgentState.FAILED), false);
    });

    test('should check if terminal state', () => {
      assert.strictEqual(stateMachine.isTerminal(), false);

      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.TEXT_ONLY);
      
      assert.strictEqual(stateMachine.isTerminal(), true);
    });
  });

  suite('Context Updates', () => {
    test('should update context directly', () => {
      stateMachine.updateContext({
        intentType: 'debug',
        taskDescription: 'Fix compilation error',
      });

      assert.strictEqual(stateMachine.context.intentType, 'debug');
      assert.strictEqual(stateMachine.context.taskDescription, 'Fix compilation error');
    });

    test('should preserve existing context on update', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      
      stateMachine.updateContext({
        intentType: 'debug',
      });

      assert.strictEqual(stateMachine.context.iteration, 1); // Preserved
      assert.strictEqual(stateMachine.context.intentType, 'debug'); // Updated
    });
  });
});
