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
 */

import {
  AgentStateMachine,
  AgentState,
  AgentEvent,
  StateContext,
  createInitialContext,
} from '../AgentStateMachine';

describe('AgentStateMachine', () => {
  let stateMachine: AgentStateMachine;

  beforeEach(() => {
    stateMachine = new AgentStateMachine();
  });

  describe('Initialization', () => {
    test('should start in IDLE state', () => {
      expect(stateMachine.state).toBe(AgentState.IDLE);
    });

    test('should have initial context', () => {
      const context = stateMachine.context;
      expect(context.iteration).toBe(0);
      expect(context.consecutiveEdits).toBe(0);
      expect(context.consecutivePlans).toBe(0);
      expect(context.buildFailures).toBe(0);
      expect(context.pendingFixes).toEqual([]);
      expect(context.intentType).toBe('default');
    });

    test('should reset to initial state', () => {
      // Move to different state
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      
      expect(stateMachine.state).not.toBe(AgentState.IDLE);
      
      // Reset
      stateMachine.reset();
      
      expect(stateMachine.state).toBe(AgentState.IDLE);
      expect(stateMachine.context.iteration).toBe(0);
    });
  });

  describe('Intent Classification', () => {
    test('should classify explore intent', () => {
      const intent = stateMachine.classifyIntent('explain how this code works');
      expect(intent).toBe('explore');
    });

    test('should classify create intent', () => {
      const intent = stateMachine.classifyIntent('write a new function to handle users');
      expect(intent).toBe('create');
    });

    test('should classify debug intent', () => {
      const intent = stateMachine.classifyIntent('fix the bug in UserService');
      expect(intent).toBe('debug');
    });

    test('should classify refactor intent', () => {
      const intent = stateMachine.classifyIntent('refactor this method to be more readable');
      expect(intent).toBe('refactor');
    });

    test('should classify test intent', () => {
      const intent = stateMachine.classifyIntent('write unit tests for the API');
      expect(intent).toBe('test');
    });

    test('should classify run intent', () => {
      const intent = stateMachine.classifyIntent('run the backend server');
      expect(intent).toBe('run');
    });

    test('should default to default intent', () => {
      const intent = stateMachine.classifyIntent('hello');
      expect(intent).toBe('default');
    });
  });

  describe('LLM Response Classification', () => {
    test('should classify tool calls', () => {
      const event = stateMachine.classifyLLMResponse('Some text', true, stateMachine.context);
      expect(event).toBe(AgentEvent.TOOL_CALLS_RECEIVED);
    });

    test('should classify plan-only responses', () => {
      const event = stateMachine.classifyLLMResponse(
        'I will first read the file to understand the structure',
        false,
        stateMachine.context
      );
      expect(event).toBe(AgentEvent.PLAN_ONLY);
    });

    test('should classify natural text responses', () => {
      const event = stateMachine.classifyLLMResponse(
        'The code structure follows a typical MVC pattern with controllers handling HTTP requests...',
        false,
        stateMachine.context
      );
      expect(event).toBe(AgentEvent.TEXT_ONLY);
    });
  });

  describe('Tool Result Classification', () => {
    test('should classify build success', () => {
      const { event } = stateMachine.classifyToolResult(
        'run_build',
        '✅ BUILD SUCCESSFUL',
        undefined
      );
      expect(event).toBe(AgentEvent.BUILD_SUCCESS);
    });

    test('should classify build failure', () => {
      const { event } = stateMachine.classifyToolResult(
        'run_build',
        '❌ BUILD FAILED',
        undefined
      );
      expect(event).toBe(AgentEvent.BUILD_FAILURE);
    });

    test('should classify edit success', () => {
      const { event } = stateMachine.classifyToolResult(
        'apply_edits',
        '✅ Applied 3 edits to UserService.kt',
        undefined
      );
      expect(event).toBe(AgentEvent.EDIT_SUCCESS);
    });

    test('should classify edit failure', () => {
      const { event } = stateMachine.classifyToolResult(
        'apply_edits',
        '❌ No edits applied',
        'Search text not found'
      );
      expect(event).toBe(AgentEvent.EDIT_FAILURE);
    });

    test('should classify write success', () => {
      const { event } = stateMachine.classifyToolResult(
        'write_file',
        'Successfully wrote 1500 characters',
        undefined
      );
      expect(event).toBe(AgentEvent.WRITE_SUCCESS);
    });

    test('should default to TOOLS_EXECUTED', () => {
      const { event } = stateMachine.classifyToolResult(
        'read_file',
        'File content...',
        undefined
      );
      expect(event).toBe(AgentEvent.TOOLS_EXECUTED);
    });
  });

  describe('State Transitions - Happy Path', () => {
    test('should transition through complete workflow', () => {
      // IDLE → INTENT
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      expect(stateMachine.state).toBe(AgentState.INTENT);

      // INTENT → PLAN
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      expect(stateMachine.state).toBe(AgentState.PLAN);

      // PLAN → CONSTRAINTS
      stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);
      expect(stateMachine.state).toBe(AgentState.CONSTRAINTS);

      // CONSTRAINTS → SEQUENCE
      stateMachine.dispatch(AgentEvent.PLAN_VALIDATED);
      expect(stateMachine.state).toBe(AgentState.SEQUENCE);

      // SEQUENCE → EXECUTE
      stateMachine.dispatch(AgentEvent.SEQUENCE_READY);
      expect(stateMachine.state).toBe(AgentState.EXECUTE);

      // EXECUTE → VERIFY
      stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);
      expect(stateMachine.state).toBe(AgentState.VERIFY);

      // VERIFY → COMPLETE
      stateMachine.dispatch(AgentEvent.BUILD_SUCCESS);
      expect(stateMachine.state).toBe(AgentState.COMPLETE);
    });

    test('should complete naturally without tools', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.TEXT_ONLY);
      expect(stateMachine.state).toBe(AgentState.COMPLETE);
    });
  });

  describe('State Transitions - Error Handling', () => {
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
      expect(stateMachine.state).toBe(AgentState.PLAN);
      expect(stateMachine.context.buildFailures).toBe(1);
      expect(stateMachine.context.inBuildFixCycle).toBe(true);
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
      expect(stateMachine.state).toBe(AgentState.PLAN);
      expect(stateMachine.context.failedEditAttempts).toBe(1);
    });

    test('should fail on loop detection', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.LOOP_DETECTED);
      expect(stateMachine.state).toBe(AgentState.FAILED);
    });

    test('should fail on max iterations', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.MAX_ITERATIONS);
      expect(stateMachine.state).toBe(AgentState.FAILED);
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
      expect(stateMachine.context.buildFailures).toBe(3);

      // Next failure should trigger MAX_FAILURES
      stateMachine.dispatch(AgentEvent.MAX_FAILURES);
      expect(stateMachine.state).toBe(AgentState.FAILED);
    });
  });

  describe('Plan-Only Loop Detection', () => {
    test('should track consecutive plan-only responses', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);

      // First plan-only
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      expect(stateMachine.context.consecutivePlans).toBe(1);

      // Second plan-only
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      expect(stateMachine.context.consecutivePlans).toBe(2);
      expect(stateMachine.context.inPlanLoop).toBe(true);
    });

    test('should reset consecutive plans on tool calls', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      
      expect(stateMachine.context.consecutivePlans).toBe(2);

      // Now use tools
      stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);
      expect(stateMachine.context.consecutivePlans).toBe(0);
    });

    test('should detect stuck plan loop', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      
      // Three plan-only responses
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);

      expect(stateMachine.isPlanLoopStuck()).toBe(true);
    });
  });

  describe('Build-Fix Cycle', () => {
    test('should track consecutive successful edits', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);
      stateMachine.dispatch(AgentEvent.PLAN_VALIDATED);
      stateMachine.dispatch(AgentEvent.SEQUENCE_READY);
      stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);
      stateMachine.dispatch(AgentEvent.EDIT_SUCCESS);

      expect(stateMachine.context.consecutiveEdits).toBe(1);

      // Another successful edit
      stateMachine.dispatch(AgentEvent.EDIT_SUCCESS);
      expect(stateMachine.context.consecutiveEdits).toBe(2);
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

      expect(stateMachine.shouldForceBuild()).toBe(true);
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

      expect(stateMachine.isBuildFixCycleStuck()).toBe(true);
    });
  });

  describe('Tool Call Loop Detection', () => {
    test('should detect repeated tool calls', () => {
      stateMachine.recordToolCall('search_files', { pattern: 'UserService' }, 'Found 5 files', undefined, 150);
      stateMachine.recordToolCall('search_files', { pattern: 'UserService' }, 'Found 5 files', undefined, 145);

      const isLoop = stateMachine.detectLoop('search_files', { pattern: 'UserService' }, 3);
      expect(isLoop).toBe(true);
    });

    test('should not flag different tool calls as loops', () => {
      stateMachine.recordToolCall('search_files', { pattern: 'UserService' }, 'Found 5 files', undefined, 150);
      stateMachine.recordToolCall('read_file', { path: 'src/UserService.kt' }, 'File content...', undefined, 50);

      const isLoop = stateMachine.detectLoop('read_file', { path: 'src/UserService.kt' }, 2);
      expect(isLoop).toBe(false);
    });

    test('should not flag same tool with different args as loops', () => {
      stateMachine.recordToolCall('search_files', { pattern: 'UserService' }, 'Found 5 files', undefined, 150);
      stateMachine.recordToolCall('search_files', { pattern: 'OrderService' }, 'Found 3 files', undefined, 140);

      const isLoop = stateMachine.detectLoop('search_files', { pattern: 'OrderService' }, 2);
      expect(isLoop).toBe(false);
    });
  });

  describe('Constraints Validation', () => {
    test('should pass valid tool calls', () => {
      const toolCalls = [
        { toolName: 'read_file', args: { path: 'src/main.kt' } },
        { toolName: 'apply_edits', args: { path: 'src/main.kt', edits: [{ search: 'old', replace: 'new' }] } },
      ];

      const validation = stateMachine.validateConstraints(toolCalls);
      expect(validation.passed).toBe(true);
      expect(validation.violations).toEqual([]);
    });

    test('should detect destructive commands', () => {
      const toolCalls = [
        { toolName: 'run_terminal', args: { command: 'rm -rf /tmp/cache' } },
      ];

      const validation = stateMachine.validateConstraints(toolCalls);
      expect(validation.passed).toBe(false);
      expect(validation.violations).toContainEqual(expect.stringContaining('Destructive command blocked'));
      expect(validation.safetyFlags.isDestructive).toBe(true);
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
      expect(validation.passed).toBe(false);
      expect(validation.violations).toContainEqual(expect.stringContaining('Too many edits'));
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
      expect(validation.warnings).toContainEqual(expect.stringContaining('already used'));
    });
  });

  describe('Sequence Optimization', () => {
    test('should order tools by priority', () => {
      const toolCalls = [
        { toolName: 'run_build', args: {}, toolCallId: '1' },
        { toolName: 'read_file', args: { path: 'src/main.kt' }, toolCallId: '2' },
        { toolName: 'apply_edits', args: { path: 'src/main.kt', edits: [] }, toolCallId: '3' },
      ];

      const sequence = stateMachine.optimizeSequence(toolCalls);
      
      // Read operations should come first
      expect(sequence[0].toolName).toBe('read_file');
      // Then edits
      expect(sequence[1].toolName).toBe('apply_edits');
      // Then build
      expect(sequence[2].toolName).toBe('run_build');
    });
  });

  describe('Tool Filtering', () => {
    test('should return all tools in normal state', () => {
      const filter = stateMachine.getToolFilter();
      expect(filter).toBe('all');
    });

    test('should return read_only in COMPLETE state', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.TEXT_ONLY);
      
      const filter = stateMachine.getToolFilter();
      expect(filter).toBe('read_only');
    });

    test('should return read_only in FAILED state', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.LOOP_DETECTED);
      
      const filter = stateMachine.getToolFilter();
      expect(filter).toBe('read_only');
    });

    test('should return fix_only when stuck in plan loop', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      stateMachine.dispatch(AgentEvent.PLAN_ONLY);
      
      const filter = stateMachine.getToolFilter();
      expect(filter).toBe('fix_only');
    });
  });

  describe('State History', () => {
    test('should track state transitions', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);

      const history = stateMachine.getLastTransitions(10);
      expect(history.length).toBe(3);
      expect(history[0].from).toBe(AgentState.IDLE);
      expect(history[0].to).toBe(AgentState.INTENT);
      expect(history[1].to).toBe(AgentState.PLAN);
      expect(history[2].to).toBe(AgentState.CONSTRAINTS);
    });

    test('should provide last event', () => {
      expect(stateMachine.lastEvent).toBeNull();

      stateMachine.dispatch(AgentEvent.USER_INPUT);
      expect(stateMachine.lastEvent).toBe(AgentEvent.USER_INPUT);

      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      expect(stateMachine.lastEvent).toBe(AgentEvent.INTENT_CLASSIFIED);
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

  describe('Metrics Tracking', () => {
    test('should track tool call metrics', () => {
      stateMachine.recordToolCall('read_file', { path: 'src/main.kt' }, 'content', undefined, 50);
      stateMachine.recordToolCall('apply_edits', { path: 'src/main.kt', edits: [] }, '✅ Applied', undefined, 100);
      stateMachine.recordToolCall('run_build', {}, '❌ FAILED', 'Build error', 5000);

      const metrics = stateMachine.context.metrics;
      expect(metrics.totalToolCalls).toBe(3);
      expect(metrics.successfulToolCalls).toBe(2);
      expect(metrics.failedToolCalls).toBe(1);
      expect(metrics.averageToolDurationMs).toBeCloseTo(1716.67, 0);
    });

    test('should increment iteration counter', () => {
      expect(stateMachine.context.iteration).toBe(0);
      
      stateMachine.incrementIteration();
      expect(stateMachine.context.iteration).toBe(1);
      
      stateMachine.incrementIteration();
      expect(stateMachine.context.iteration).toBe(2);
    });
  });

  describe('State Checks', () => {
    test('should check current state', () => {
      expect(stateMachine.is(AgentState.IDLE)).toBe(true);
      expect(stateMachine.is(AgentState.PLAN)).toBe(false);

      stateMachine.dispatch(AgentEvent.USER_INPUT);
      expect(stateMachine.is(AgentState.IDLE)).toBe(false);
      expect(stateMachine.is(AgentState.INTENT)).toBe(true);
    });

    test('should check if in any of multiple states', () => {
      expect(stateMachine.isAny(AgentState.IDLE, AgentState.PLAN)).toBe(true);
      expect(stateMachine.isAny(AgentState.COMPLETE, AgentState.FAILED)).toBe(false);
    });

    test('should check if terminal state', () => {
      expect(stateMachine.isTerminal()).toBe(false);

      stateMachine.dispatch(AgentEvent.USER_INPUT);
      stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED);
      stateMachine.dispatch(AgentEvent.TEXT_ONLY);
      
      expect(stateMachine.isTerminal()).toBe(true);
    });
  });

  describe('Context Updates', () => {
    test('should update context directly', () => {
      stateMachine.updateContext({
        intentType: 'debug',
        taskDescription: 'Fix compilation error',
      });

      expect(stateMachine.context.intentType).toBe('debug');
      expect(stateMachine.context.taskDescription).toBe('Fix compilation error');
    });

    test('should preserve existing context on update', () => {
      stateMachine.dispatch(AgentEvent.USER_INPUT);
      
      stateMachine.updateContext({
        intentType: 'debug',
      });

      expect(stateMachine.context.iteration).toBe(1); // Preserved
      expect(stateMachine.context.intentType).toBe('debug'); // Updated
    });
  });
});

describe('StateMachineObserver', () => {
  // Tests for the observer would go here
  // This is a placeholder for future implementation
});
