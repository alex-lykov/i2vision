# Agent State Machine - Quick Reference

## State Flow (One-Liner)

```
IDLE → INTENT → PLAN → CONSTRAINTS → SEQUENCE → EXECUTE → VERIFY → COMPLETE/FAILED
```

## States & Transitions

| From State | Event | To State | Description |
|------------|-------|----------|-------------|
| IDLE | USER_INPUT | INTENT | User submitted task |
| INTENT | INTENT_CLASSIFIED | PLAN | Intent detected (explore/create/debug/etc.) |
| PLAN | TOOL_CALLS_RECEIVED | CONSTRAINTS | LLM returned tool calls |
| PLAN | TEXT_ONLY | COMPLETE | Natural language answer |
| PLAN | PLAN_ONLY | PLAN | Describing plans (nudge to use tools) |
| CONSTRAINTS | PLAN_VALIDATED | SEQUENCE | Safety checks passed |
| CONSTRAINTS | PLAN_INVALID | PLAN | Violations detected |
| SEQUENCE | SEQUENCE_READY | EXECUTE | Order optimized |
| EXECUTE | TOOLS_EXECUTED | VERIFY | All tools completed |
| VERIFY | BUILD_SUCCESS | COMPLETE | Compilation passed |
| VERIFY | BUILD_FAILURE | PLAN | Compilation failed |
| VERIFY | EDIT_SUCCESS | PLAN | Edit applied |
| VERIFY | EDIT_FAILURE | PLAN | Edit failed |
| ANY | LOOP_DETECTED | FAILED | Infinite loop detected |
| ANY | MAX_ITERATIONS | FAILED | Iteration limit reached |
| FAILED | RETRY | IDLE | Restart task |

## Events by Category

### Input Events
- `USER_INPUT` - Start task
- `USER_CONFIRMED` - Proceed with risky operation
- `USER_REJECTED` - Cancel risky operation
- `CANCELLED` - User cancelled

### LLM Response Events
- `INTENT_CLASSIFIED` - Task type detected
- `TOOL_CALLS_RECEIVED` - Has tool calls
- `TEXT_ONLY` - No tool calls (answer)
- `PLAN_ONLY` - Describing instead of doing

### Validation Events
- `PLAN_VALIDATED` - Constraints passed
- `PLAN_INVALID` - Constraints failed

### Execution Events
- `SEQUENCE_READY` - Order optimized
- `TOOLS_EXECUTED` - All tools done
- `TOOL_FAILED` - Individual tool error

### Result Events
- `BUILD_SUCCESS` - ✅ Compilation passed
- `BUILD_FAILURE` - ❌ Compilation failed
- `SERVER_STARTED` - ✅ Server running
- `SERVER_FAILURE` - ❌ Server failed
- `EDIT_SUCCESS` - ✅ Edit applied
- `EDIT_FAILURE` - ❌ Edit failed
- `WRITE_SUCCESS` - ✅ File written
- `WRITE_FAILURE` - ❌ Write failed

### Error Events
- `LOOP_DETECTED` - Repeated tool call
- `SEARCH_LOOP` - Repeated search
- `MAX_ITERATIONS` - Limit reached (default: 50)
- `MAX_FAILURES` - Consecutive failures (default: 3)
- `TIMEOUT` - Tool timeout

## Context Properties

```typescript
interface StateContext {
  // Counters
  iteration: number;
  consecutiveEdits: number;
  consecutivePlans: number;
  buildFailures: number;
  failedEditAttempts: number;
  
  // Build-fix cycle
  pendingFixes: string[];
  lastBuildErrors: string;
  inBuildFixCycle: boolean;
  
  // Loop detection
  inPlanLoop: boolean;
  inSearchLoop: boolean;
  lastSearchPattern: string | null;
  lastSearchFiles: string[];
  
  // Task info
  intentType: string;  // explore|create|debug|refactor|test|run
  taskDescription: string;
  
  // Execution
  validatedToolCalls: Array<{...}>;
  executionSequence: Array<{...}>;
  toolCallHistory: ToolCallRecord[];
  
  // Safety
  constraintsValidation: { passed, violations, warnings };
  safetyFlags: { requiresConfirmation, isDestructive, modifiesFiles, runsExternalProcess };
  
  // Metrics
  metrics: { totalToolCalls, successfulToolCalls, failedToolCalls, averageToolDurationMs, totalIterations };
}
```

## Common Patterns

### Start New Task
```typescript
stateMachine.reset();
stateMachine.dispatch(AgentEvent.USER_INPUT);
stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED, { intentType: 'debug' });
```

### Handle LLM Response
```typescript
if (hasToolCalls) {
  stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);
  const validation = stateMachine.validateConstraints(toolCalls);
  if (validation.passed) {
    stateMachine.dispatch(AgentEvent.PLAN_VALIDATED);
  } else {
    stateMachine.dispatch(AgentEvent.PLAN_INVALID);
  }
} else if (isPlanOnly) {
  stateMachine.dispatch(AgentEvent.PLAN_ONLY);
} else {
  stateMachine.dispatch(AgentEvent.TEXT_ONLY);
}
```

### Handle Tool Results
```typescript
stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);

if (result.includes('BUILD SUCCESSFUL')) {
  stateMachine.dispatch(AgentEvent.BUILD_SUCCESS);
} else if (result.includes('BUILD FAILED')) {
  stateMachine.dispatch(AgentEvent.BUILD_FAILURE);
} else if (result.includes('✅ Applied')) {
  stateMachine.dispatch(AgentEvent.EDIT_SUCCESS);
} else if (result.includes('❌')) {
  stateMachine.dispatch(AgentEvent.EDIT_FAILURE);
}
```

### Check for Problems
```typescript
if (stateMachine.isPlanLoopStuck()) {
  // Force tool usage
}

if (stateMachine.isBuildFixCycleStuck()) {
  // Require manual intervention
}

if (stateMachine.isEditFailureStuck()) {
  // Switch to write_file or revert
}
```

### Get Tool Filter
```typescript
const filter = stateMachine.getToolFilter();
// Returns: 'all' | 'fix_only' | 'read_only'
```

### Monitor State
```typescript
// Check current state
if (stateMachine.is(AgentState.EXECUTE)) {
  console.log('Executing tools...');
}

// Check if done
if (stateMachine.isTerminal()) {
  console.log(`Task ${stateMachine.state}`);
}

// Get history
const history = stateMachine.getLastTransitions(10);
```

## Safety Limits

| Limit | Value | Enforcement |
|-------|-------|-------------|
| Max edits per apply_edits | 50 | Constraint validation |
| Max iterations | 50 (configurable) | State transition guard |
| Max build failures | 3 | MAX_FAILURES event |
| Max edit failures | 3 | Auto-nudge to next file |
| Max consecutive plans | 3 | Loop detection |
| Search pattern reuse | 1 | SEARCH_LOOP event |

## Intent Types

| Type | Keywords | Tool Filter |
|------|----------|-------------|
| `explore` | explain, what, how, find, show | all (read-heavy) |
| `create` | write, create, add, implement | all |
| `debug` | fix, bug, error, crash, fail | fix_only when in build-fix cycle |
| `refactor` | refactor, rename, extract, move | all |
| `test` | test, spec, unit, integration | all |
| `run` | run, start, serve, launch | all |
| `default` | (no match) | all |

## Debugging

### Enable Debug Logging
```bash
export DEBUG=agent-state
```

### Get Debug Report
```typescript
observer.printDebugReport();
// Or
const report = observer.generateDebugReport();
```

### Export State History
```typescript
const json = observer.exportToJson();
// Save to file for analysis
```

### Check Anomalies
```typescript
const anomalies = observer.detectAnomalies();
if (anomalies.length > 0) {
  console.warn('Anomalies:', anomalies);
}
```

## Metrics Access

```typescript
const metrics = stateMachine.context.metrics;

console.log(`Total tool calls: ${metrics.totalToolCalls}`);
console.log(`Success rate: ${metrics.successfulToolCalls / metrics.totalToolCalls * 100}%`);
console.log(`Avg duration: ${metrics.averageToolDurationMs}ms`);
console.log(`Iterations: ${metrics.totalIterations}`);
```

## Common Issues & Solutions

### Issue: LLM keeps describing plans
**Detection:** `consecutivePlans >= 3`
**Solution:** State machine auto-nudges with "STOP describing plans. Use tool calling API NOW."

### Issue: Build fails repeatedly
**Detection:** `buildFailures >= 3`
**Solution:** State machine triggers MAX_FAILURES, stops and requires manual intervention

### Issue: Same search pattern used multiple times
**Detection:** `lastSearchPattern === currentPattern`
**Solution:** Returns SEARCH_LOOP error, suggests reading found files instead

### Issue: Edit fails multiple times on same file
**Detection:** `failedEditAttempts >= 3`
**Solution:** Auto-nudges to next file or suggests re-running build

### Issue: Tool call loop detected
**Detection:** `detectLoop(toolName, args, iteration)` returns true
**Solution:** Triggers LOOP_DETECTED event, transitions to FAILED state

## Best Practices

1. ✅ **Always reset** between tasks: `stateMachine.reset()`
2. ✅ **Validate constraints** before executing: `validateConstraints(toolCalls)`
3. ✅ **Record tool calls** for loop detection: `recordToolCall(...)`
4. ✅ **Use state-aware tool filtering**: `getToolFilter()`
5. ✅ **Monitor state changes**: Subscribe with `onStateChange(callback)`
6. ✅ **Check for stuck states**: `isPlanLoopStuck()`, `isBuildFixCycleStuck()`
7. ✅ **Export history** for debugging: `exportToJson()`

## File Locations

| File | Purpose |
|------|---------|
| `src/agent/AgentStateMachine.ts` | Core state machine implementation |
| `src/agent/AgentBridge.ts` | Integration with LLM and tools |
| `src/agent/StateMachineObserver.ts` | Monitoring and debugging |
| `docs/AGENT_STATE_MACHINE.md` | Full documentation |
| `docs/STATE_MACHINE_QUICK_REFERENCE.md` | This file |
