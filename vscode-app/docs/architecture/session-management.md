# Session Management Architecture

## Overview

Session management enables stateful conversations with LLM providers that support it. The system provides a unified interface for session synchronization, health monitoring, context compaction, and automatic recovery.

## Architecture

```
AgentBridge
    ↓
SessionManager (Interface)
├── ProxySessionManager (3D LLM, Mistral)
└── NullSessionManager (Ollama, DeepSeek)
    ↓
Provider-Specific Session Proxy
```

## Session Managers

### ProxySessionManager

**Providers**: 3D LLM, Mistral

**Features**:
- Session synchronization
- Health monitoring
- Context compaction
- Automatic session reset
- Token usage tracking

**Base URL**: Configured via settings (`mistral.baseUrl` or proxy URL)

### NullSessionManager

**Providers**: Ollama, DeepSeek

**Features**:
- No-op implementation
- Stateless conversations
- No session persistence

## Provider Capabilities

| Provider | Session Management | Context Compaction | Token Tracking |
|----------|-------------------|-------------------|----------------|
| Ollama | ❌ | ❌ | ❌ |
| DeepSeek | ❌ | ❌ | ❌ |
| Mistral | ✅ | ✅ | ✅ |
| 3D LLM | ✅ | ✅ | ✅ |

## Session Lifecycle

### 1. Initialization

```typescript
// Create session manager based on provider
sessionManager = createSessionManager(provider, providerUrl)

if (sessionManager.name !== 'Null') {
  log(`Session manager initialized: ${sessionManager.name}`)
}
```

### 2. Synchronization

On first iteration of a non-fresh conversation:

```typescript
if (iteration === 1 && !isFreshConversation) {
  await sessionManager.syncSession(agentId)
  const sessionState = sessionManager.getSessionState()
  log(`Session ${sessionState.id} — ${sessionState.messageCount} msgs`)
}
```

### 3. Health Check

Proactive health check on first iteration:

```typescript
if (iteration === 1) {
  const health = await sessionManager.checkHealth()
  if (!health.healthy) {
    log(`Session unhealthy: ${health.warnings.join('; ')}`)
    const resetOk = await sessionManager.resetSession(agentId)
    if (resetOk) {
      log('Session reset due to health check failure')
    }
  }
}
```

### 4. Context Management

During conversation, monitor context usage:

```typescript
if (providerCapabilities.contextCompaction) {
  messages = await sessionManager.manageMessages(messages)
  
  const isExhausted = sessionManager.isContextExhausted()
  if (isExhausted) {
    log('Context exhaustion detected - triggering automatic session reset')
    await sessionManager.resetSession(agentId, 'token_limit')
  }
}
```

### 5. Session State Persistence

Save session state for cross-session persistence:

```typescript
getSessionState(): AgentSessionState {
  return {
    visitedPaths: [...this._autoReadFiles],
    searchCache: [...this._searchCache.entries()],
    proxySession: this.sessionManager?.getSessionState(),
    sessionManagerState: this.sessionManager?.serialize()
  }
}
```

## Session Errors

### Error Classification

```typescript
isSessionError(errorText: string): boolean {
  return errorText.includes('session') || 
         errorText.includes('context') ||
         errorText.includes('timeout')
}

isContextExhaustionError(errorText: string): boolean {
  return errorText.includes('token limit') ||
         errorText.includes('context window') ||
         errorText.includes('maximum context')
}
```

### Recovery Strategies

| Error Type | Recovery Action |
|------------|----------------|
| Session timeout | Reset session |
| Context exhaustion | Reset session with reason 'token_limit' |
| Network error | Retry with exponential backoff |
| Auth error | Prompt user to re-authenticate |

## Context Compaction

### Strategies

When approaching token limits:

1. **Summarize Old Messages**
   ```typescript
   const summarized = await summarizeConversation(oldMessages)
   ```

2. **Trim Messages**
   ```typescript
   const trimmed = await trimMessagesToBudget(messages, 0.75)
   ```

3. **Remove Tool Results**
   ```typescript
   const withoutResults = messages.filter(m => 
     m.role !== 'tool' || m.isImportant
   )
   ```

### Token Budget

```typescript
// Warning threshold: 50%
if (tokenUsagePercent > 50) {
  log(`Token usage: ${estimatedTokens} / ${maxTokens} (${percent}%)`)
}

// Action threshold: 80%
if (estimatedTokens > maxTokens * 0.8) {
  log('Token warning: summarizing old messages')
  const trimmed = await trimMessagesToBudget(messages, 0.75)
}
```

## Token Tracking

### Estimation

```typescript
estimateTokens(messages: LLMMessage[]): number {
  // Use baseline from last known LLM-reported tokens
  // Plus delta estimate for new content
  if (this.lastKnownPromptTokens > 0) {
    return this.lastKnownPromptTokens + estimateDelta(messages)
  }
  // Fallback: character-based estimation
  return estimateFromChars(messages)
}
```

### LLM-Reported Tokens

```typescript
// Update from LLM response
if (response.tokenUsage) {
  this._lastTokenUsage = response.tokenUsage
  this.llmAdapter.lastKnownPromptTokens = response.tokenUsage.prompt
}
```

### Session Token Tracking

```typescript
// Session manager tracks cumulative tokens
sessionManager.updateTokenUsage({
  prompt: tokens,
  completion: tokens,
  total: tokens
})

// Check if exhausted
const isExhausted = sessionManager.isContextExhausted()
```

## Session Reset

### When to Reset

```typescript
// Context exhaustion
if (sessionManager.isContextExhausted()) {
  await sessionManager.resetSession(agentId, 'token_limit')
}

// Health check failure
if (!health.healthy) {
  await sessionManager.resetSession(agentId, 'health_check')
}

// Fresh conversation
if (isFreshConversation) {
  // Skip sync - use reset state
  log('Skipping session sync — fresh conversation')
}
```

### Reset Flow

```typescript
async resetSession(agentId: string, reason: string): Promise<boolean> {
  // 1. Clear local state
  this.cachedSystemPrompt = undefined
  this._searchCache.clear()
  
  // 2. Reset proxy session
  const success = await proxy.resetSession(agentId)
  
  // 3. Log reset
  log(`Session reset: ${reason}`)
  
  return success
}
```

## Serialization/Deserialization

### Save Session State

```typescript
serialize(): SessionManagerState {
  return {
    id: this.sessionId,
    messageCount: this.messageCount,
    tokenUsage: this.tokenUsage,
    createdAt: this.createdAt,
    messages: this.messages // Compacted
  }
}
```

### Restore Session State

```typescript
deserialize(state: SessionManagerState): void {
  this.sessionId = state.id
  this.messageCount = state.messageCount
  this.tokenUsage = state.tokenUsage
  this.messages = state.messages
}
```

## Provider-Specific Behavior

### 3D LLM

- Uses proxy session manager
- Supports session reset
- Tracks token usage
- Provides context compaction

### Mistral

- Uses proxy session manager
- Supports session reset
- Tracks token usage
- Provides context compaction

### Ollama

- Uses null session manager
- Stateless conversations
- No session persistence
- Local token estimation only

### DeepSeek

- Uses null session manager
- Stateless conversations
- No session persistence
- API-based token estimation

## Error Handling

### Session Sync Errors

```typescript
try {
  await sessionManager.syncSession(agentId)
} catch (error: any) {
  log(`Session sync failed: ${error.message}`)
  // Continue with local state
}
```

### Health Check Errors

```typescript
try {
  const health = await sessionManager.checkHealth()
  if (!health.healthy) {
    log(`Session unhealthy: ${health.warnings.join('; ')}`)
  }
} catch (error: any) {
  log(`Health check failed: ${error.message}`)
  // Assume healthy, continue
}
```

### Reset Errors

```typescript
const resetOk = await sessionManager.resetSession(agentId, reason)
if (!resetOk) {
  log('Session reset failed - continuing with current context')
}
```

## Testing

### Unit Tests

```typescript
describe('ProxySessionManager', () => {
  it('should sync session', async () => {
    const manager = new ProxySessionManager(url)
    await manager.syncSession('agent-123')
    
    const state = manager.getSessionState()
    expect(state.id).toBeDefined()
  })
  
  it('should detect context exhaustion', () => {
    const manager = new ProxySessionManager(url)
    manager.updateTokenUsage({ prompt: 100000, completion: 10000, total: 110000 })
    
    expect(manager.isContextExhausted()).toBe(true)
  })
})
```

### Integration Tests

```typescript
describe('Session Management Integration', () => {
  it('should reset session on context exhaustion', async () => {
    const bridge = new AgentBridge(config, ...)
    await bridge.process('task 1')
    await bridge.process('task 2')
    // ... many tasks to exhaust context
    
    // Should auto-reset
    const response = await bridge.process('task N')
    expect(response.success).toBe(true)
  })
  
  it('should persist session state', async () => {
    const bridge = new AgentBridge(config, ...)
    await bridge.process('task 1')
    
    const state = bridge.getSessionState()
    expect(state.proxySession).toBeDefined()
    expect(state.sessionManagerState).toBeDefined()
  })
})
```

## Configuration

### Session Timeouts

```typescript
interface LLMConfig {
  timeoutSeconds: number        // Per-request timeout
  modificationTimeoutSeconds: number
  finalTurnBonusSeconds: number
}
```

### Token Limits

```typescript
interface ModelConfig {
  contextLength: number         // Max context window
  maxOutputTokens: number       // Max completion tokens
}
```

## Related Documentation

- [AgentBridge Architecture](../architecture/agent-bridge.md)
- [Provider Selection Guide](../guides/provider-selection.md)
- [Context Management](../concepts/context-management.md)
