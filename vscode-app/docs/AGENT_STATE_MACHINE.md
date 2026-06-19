# Agent State Machine - Comprehensive Documentation

## Overview

The Agent State Machine provides robust flow control for the entire LLM task lifecycle, ensuring predictable behavior, safety constraints, and optimal tool execution.

## Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    AGENT STATE MACHINE FLOW                                  │
└─────────────────────────────────────────────────────────────────────────────┘

IDLE 
  │
  ╰── USER_INPUT ──→ INTENT ──→ INTENT_CLASSIFIED ──→ PLAN
                                                         │
                                                         ├── TOOL_CALLS_RECEIVED ──→ CONSTRAINTS
                                                         │                              │
                                                         │                              ├── PLAN_VALIDATED ──→ SEQUENCE ──→ SEQUENCE_READY ──→ EXECUTE
                                                         │                              │                                                       │
                                                         │                              ╰── PLAN_INVALID ──→ PLAN                                ╰── TOOLS_EXECUTED ──→ VERIFY
                                                         │                                                                                              │
                                                         ├── TEXT_ONLY ──→ COMPLETE ←─────────────────────────────────────────────────────────────────┤
                                                         │                              │                                                               ├── BUILD_SUCCESS ──→ COMPLETE
                                                         │                              │                                                               ├── BUILD_FAILURE ──→ PLAN
                                                         ╰── PLAN_ONLY ──→ PLAN         │                                                               ├── SERVER_FAILURE ──→ PLAN
                                                                                        │                                                               ├── SERVER_STARTED ──→ COMPLETE
                                                                                        │                                                               ├── EDIT_SUCCESS ──→ PLAN
                                                                                        │                                                               ├── EDIT_FAILURE ──→ PLAN
                                                                                        │                                                               ├── WRITE_SUCCESS ──→ PLAN
                                                                                        │                                                               ╰── WRITE_FAILURE ──→ PLAN
                                                                                        │
                                                                                        ╰──→ FAILED (via LOOP_DETECTED, MAX_ITERATIONS, MAX_FAILURES, CANCELLED, TIMEOUT)
```

## States

### 1. **IDLE**
Initial state, waiting for user input.

**Entry Conditions:**
- Agent initialized
- No active task

**Exit Transitions:**
- `USER_INPUT` → INTENT

---

### 2. **INTENT**
Understanding user intent, classifying task type, gathering initial context.

**Entry Conditions:**
- User input received

**Actions:**
- Classify intent type (explore/create/debug/refactor/test/run)
- Store task description

**Exit Transitions:**
- `INTENT_CLASSIFIED` → PLAN

---

### 3. **PLAN**
LLM is formulating a plan (analyzing task, selecting tools).

**Entry Conditions:**
- Intent classified
- Context loaded

**Actions:**
- Call LLM with appropriate tools
- Parse response for tool calls

**Exit Transitions:**
- `TOOL_CALLS_RECEIVED` → CONSTRAINTS
- `TEXT_ONLY` → COMPLETE (natural answer)
- `PLAN_ONLY` → PLAN (nudge to use tools)

---

### 4. **CONSTRAINTS**
Validating plan against safety, resource limits, and policies.

**Entry Conditions:**
- Tool calls received from LLM

**Validations:**
- ✅ Destructive command detection
- ✅ Edit count limits (max 50 per apply_edits)
- ✅ Search loop detection
- ✅ Privilege escalation attempts
- ✅ Blocked command patterns

**Exit Transitions:**
- `PLAN_VALIDATED` → SEQUENCE
- `PLAN_INVALID` → PLAN (with violations)
- `PLAN_INVALID` + requiresConfirmation → PAUSED

---

### 5. **SEQUENCE**
Ordering tool calls into optimal execution sequence.

**Entry Conditions:**
- Constraints validated

**Optimization Rules:**
1. Read operations first (read_file, list_directory, search_files)
2. Write operations second (apply_edits, write_file)
3. Build/test third (run_build)
4. Run/deploy last (run_terminal for servers)

**Exit Transitions:**
- `SEQUENCE_READY` → EXECUTE

---

### 6. **EXECUTE**
Executing tool calls from the LLM.

**Entry Conditions:**
- Sequence optimized
- All constraints passed

**Actions:**
- Execute tools in order
- Record results and durations
- Track for loop detection
- Update metrics

**Exit Transitions:**
- `TOOLS_EXECUTED` → VERIFY
- `TOOL_FAILED` → PLAN (retry)
- `TIMEOUT` → FAILED

---

### 7. **VERIFY**
Verifying build/test/server results.

**Entry Conditions:**
- Tools executed
- Results available

**Actions:**
- Classify tool results
- Check for build success/failure
- Detect server startup status
- Evaluate edit success/failure

**Exit Transitions:**
- `BUILD_SUCCESS` → COMPLETE
- `BUILD_FAILURE` → PLAN (with errors)
- `SERVER_STARTED` → COMPLETE
- `SERVER_FAILURE` → PLAN
- `EDIT_SUCCESS` → PLAN (continue)
- `EDIT_FAILURE` → PLAN (retry)
- `WRITE_SUCCESS` → PLAN (continue)
- `WRITE_FAILURE` → PLAN (retry)
- `MAX_FAILURES` → FAILED

---

### 8. **COMPLETE**
Task completed successfully.

**Entry Conditions:**
- Build passed OR
- Natural language answer provided OR
- Server started successfully

**Actions:**
- Emit final response
- Clear temporary state

**Exit Transitions:**
- None (terminal state)
- `RETRY` → IDLE (if user wants to continue)

---

### 9. **FAILED**
Task failed unrecoverably.

**Entry Conditions:**
- Loop detected OR
- Max iterations reached OR
- Max failures reached OR
- User cancelled OR
- Timeout occurred

**Actions:**
- Log failure reason
- Preserve history for debugging

**Exit Transitions:**
- `RETRY` → IDLE (reset and start fresh)

---

### 10. **PAUSED**
Waiting for user confirmation (for destructive operations).

**Entry Conditions:**
- Constraints validation flagged requiresConfirmation

**Exit Transitions:**
- `USER_CONFIRMED` → EXECUTE
- `USER_REJECTED` → PLAN

---

## Events

### User Events
| Event | Description |
|-------|-------------|
| `USER_INPUT` | User submitted input |
| `USER_CONFIRMED` | User confirmed action (from PAUSED) |
| `USER_REJECTED` | User rejected action (from PAUSED) |
| `CANCELLED` | User cancelled task |

### LLM Response Events
| Event | Description |
|-------|-------------|
| `INTENT_CLASSIFIED` | Intent classified (explore/create/debug/etc.) |
| `TOOL_CALLS_RECEIVED` | LLM returned tool calls |
| `TEXT_ONLY` | LLM returned text only (no tool calls) |
| `PLAN_ONLY` | LLM described plans without calling tools |

### Validation Events
| Event | Description |
|-------|-------------|
| `PLAN_VALIDATED` | Plan passed constraint validation |
| `PLAN_INVALID` | Plan failed constraint validation |

### Execution Events
| Event | Description |
|-------|-------------|
| `SEQUENCE_READY` | Sequence optimized and ready |
| `TOOLS_EXECUTED` | All tools in iteration completed |
| `TOOL_COMPLETED` | Individual tool completed |
| `TOOL_FAILED` | Individual tool failed |

### Result Events
| Event | Description |
|-------|-------------|
| `BUILD_SUCCESS` | Build/compilation succeeded |
| `BUILD_FAILURE` | Build/compilation failed |
| `SERVER_STARTED` | Server started successfully |
| `SERVER_FAILURE` | Server startup failed |
| `EDIT_SUCCESS` | Edit applied successfully |
| `EDIT_FAILURE` | Edit failed (search text not found) |
| `WRITE_SUCCESS` | File written successfully |
| `WRITE_FAILURE` | File write failed |

### Error Events
| Event | Description |
|-------|-------------|
| `LOOP_DETECTED` | Repeated same tool call detected |
| `SEARCH_LOOP` | Same search pattern used multiple times |
| `MAX_ITERATIONS` | Max iterations reached |
| `MAX_FAILURES` | Max consecutive failures reached |
| `TIMEOUT` | Tool execution timeout |
| `RETRY` | Retry requested (from FAILED state) |

---

## Context Tracking

The state machine maintains comprehensive context:

```typescript
interface StateContext {
  // Iteration tracking
  iteration: number;
  
  // Success/failure counters
  consecutiveEdits: number;
  consecutivePlans: number;
  buildFailures: number;
  failedEditAttempts: number;
  
  // Build-fix cycle state
  pendingFixes: string[];
  lastBuildErrors: string;
  inBuildFixCycle: boolean;
  
  // Search loop detection
  lastSearchPattern: string | null;
  lastSearchFiles: string[];
  inSearchLoop: boolean;
  
  // Plan loop detection
  inPlanLoop: boolean;
  
  // Task metadata
  intentType: string;
  taskDescription: string;
  
  // Validated tool calls
  validatedToolCalls: Array<{ toolName: string; args: Record<string, any>; toolCallId: string }>;
  
  // Execution sequence
  executionSequence: Array<{ toolName: string; args: Record<string, any>; toolCallId: string; dependencies?: string[] }>;
  
  // Tool call history for loop detection
  toolCallHistory: ToolCallRecord[];
  
  // Constraints validation results
  constraintsValidation: {
    passed: boolean;
    violations: string[];
    warnings: string[];
  };
  
  // Safety flags
  safetyFlags: {
    requiresConfirmation: boolean;
    isDestructive: boolean;
    modifiesFiles: boolean;
    runsExternalProcess: boolean;
  };
  
  // Performance metrics
  metrics: {
    totalToolCalls: number;
    successfulToolCalls: number;
    failedToolCalls: number;
    averageToolDurationMs: number;
    totalIterations: number;
  };
}
```

---

## Usage Examples

### Basic Usage

```typescript
const stateMachine = new AgentStateMachine();

// Start with user input
stateMachine.dispatch(AgentEvent.USER_INPUT);
stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED, {
  intentType: 'debug',
  taskDescription: 'Fix compilation error in UserService.kt',
});

// LLM returns tool calls
stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);

// Validate constraints
const validation = stateMachine.validateConstraints(toolCalls);
if (validation.passed) {
  stateMachine.dispatch(AgentEvent.PLAN_VALIDATED);
  
  // Optimize sequence
  const sequence = stateMachine.optimizeSequence(toolCalls);
  stateMachine.dispatch(AgentEvent.SEQUENCE_READY, {
    executionSequence: sequence,
  });
  
  // Execute tools
  stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);
  
  // Verify results
  stateMachine.dispatch(AgentEvent.BUILD_SUCCESS);
  
  // Complete
  console.log(`Final state: ${stateMachine.state}`); // COMPLETE
}
```

### Loop Detection

```typescript
const stateMachine = new AgentStateMachine();

// Record tool calls for loop detection
stateMachine.recordToolCall('search_files', { pattern: 'UserService' }, 'Found 5 files', undefined, 150);
stateMachine.recordToolCall('search_files', { pattern: 'UserService' }, 'Found 5 files', undefined, 145);

// Detect loop
const isLoop = stateMachine.detectLoop('search_files', { pattern: 'UserService' }, 3);
if (isLoop) {
  stateMachine.dispatch(AgentEvent.LOOP_DETECTED);
  console.log(`State: ${stateMachine.state}`); // FAILED
}
```

### Constraint Validation

```typescript
const stateMachine = new AgentStateMachine();

const toolCalls = [
  { toolName: 'run_terminal', args: { command: 'rm -rf /tmp/cache' } },
  { toolName: 'apply_edits', args: { path: 'src/main.kt', edits: [...] } },
];

const validation = stateMachine.validateConstraints(toolCalls);

if (!validation.passed) {
  console.log('Violations:', validation.violations);
  // Output: ["Destructive command blocked: rm -rf /tmp/cache"]
  
  stateMachine.dispatch(AgentEvent.PLAN_INVALID, {
    constraintsValidation: validation,
  });
}
```

### Monitoring State Changes

```typescript
const stateMachine = new AgentStateMachine();

// Subscribe to state changes (via AgentBridge progress callback)
agentBridge.process(userInput, currentFile, (event) => {
  if (event.type === 'state_change') {
    console.log(`State: ${event.state.from} → ${event.state.to} (${event.state.event})`);
  }
});

// Or poll state directly
const state = agentBridge.getState();
console.log('Current state:', state.state);
console.log('Context:', state.context);
console.log('History:', state.history);
```

---

## Safety Features

### 1. Destructive Command Blocking
```typescript
// Blocked patterns
const BLOCKED_COMMAND_PATTERNS = [
  'rm -rf /',
  'del /F /S /Q C:\\*',
  'format',
  'mkfs',
  'dd if=/dev/zero'
];

// Automatically detected and blocked
```

### 2. Edit Count Limits
```typescript
// Maximum 50 edits per apply_edits call
if (edits.length > 50) {
  return { 
    result: '', 
    error: 'Too many edits. Use write_file for large changes.' 
  };
}
```

### 3. Search Loop Prevention
```typescript
// Detects repeated search patterns
if (lastSearchPattern === currentPattern) {
  return {
    result: '⚠️ You already searched for this pattern. READ the files instead.',
    error: 'SEARCH_LOOP_DETECTED'
  };
}
```

### 4. Plan-Only Loop Detection
```typescript
// Detects LLM describing plans instead of calling tools
if (consecutivePlans >= 3) {
  stateMachine.dispatch(AgentEvent.LOOP_DETECTED);
  // Forces intervention
}
```

### 5. Build-Fix Cycle Limits
```typescript
// Stops after 3 consecutive build failures
if (buildFailures >= 3) {
  stateMachine.dispatch(AgentEvent.MAX_FAILURES);
  // Requires manual intervention
}
```

---

## Performance Metrics

The state machine tracks:

- **Total Tool Calls**: Count of all tool invocations
- **Successful Tool Calls**: Count of successful executions
- **Failed Tool Calls**: Count of failed executions
- **Average Tool Duration**: Mean execution time in milliseconds
- **Total Iterations**: Number of LLM conversation turns

Access via:
```typescript
const metrics = stateMachine.context.metrics;
console.log(`Success rate: ${metrics.successfulToolCalls / metrics.totalToolCalls * 100}%`);
console.log(`Average tool duration: ${metrics.averageToolDurationMs}ms`);
```

---

## Debugging

### Get State Diagram
```typescript
console.log(stateMachine.getStateDiagram());
```

### Get Transition History
```typescript
const history = stateMachine.getLastTransitions(10);
history.forEach(h => {
  console.log(`${h.from} ──${h.event}──→ ${h.to}`);
});
```

### Check Current State
```typescript
console.log('State:', stateMachine.state);
console.log('Context:', stateMachine.context);
console.log('Is terminal:', stateMachine.isTerminal());
```

### Check Stuck Conditions
```typescript
if (stateMachine.isPlanLoopStuck()) {
  console.log('⚠️ Plan loop detected');
}

if (stateMachine.isBuildFixCycleStuck()) {
  console.log('⚠️ Build-fix cycle stuck');
}

if (stateMachine.isEditFailureStuck()) {
  console.log('⚠️ Edit failures excessive');
}
```

---

## Best Practices

### 1. Always Reset Between Tasks
```typescript
stateMachine.reset(); // Call before starting new conversation
```

### 2. Use State-Aware Tool Filtering
```typescript
const toolFilter = stateMachine.getToolFilter();
// Returns 'all', 'fix_only', or 'read_only' based on state
```

### 3. Record All Tool Calls
```typescript
stateMachine.recordToolCall(name, args, result, error, durationMs);
// Enables loop detection and metrics
```

### 4. Validate Before Execution
```typescript
const validation = stateMachine.validateConstraints(toolCalls);
if (!validation.passed) {
  // Reject plan, don't execute
}
```

### 5. Monitor State Changes
```typescript
// Emit state_change events for monitoring
yield { 
  type: 'state_change', 
  from: previousState, 
  to: currentState, 
  event: event 
};
```

---

## Architecture Benefits

1. **Predictability**: Clear state transitions prevent unexpected behavior
2. **Safety**: Constraint validation blocks dangerous operations
3. **Observability**: Full history and metrics for debugging
4. **Resilience**: Automatic detection and handling of loops/failures
5. **Optimization**: Intelligent tool sequencing for better performance
6. **User Control**: PAUSED state for confirmation of risky operations
