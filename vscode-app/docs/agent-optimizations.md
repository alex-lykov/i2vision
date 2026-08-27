# Agent Optimization Plan

## Overview

This document outlines optimization opportunities for the i2-Vision agent system, categorized by impact and implementation effort.

**Last Updated:** 2026-08-26  
**Status:** Planning → Implementation

---

## Current State Analysis

Based on `3d-llm-optimization-analysis.md` and codebase review:

### ✅ Implemented Optimizations
- Layered prompt architecture (L1/L2/L3 separation)
- System prompt injection only once per conversation
- Tool schemas in `tools` array (not message content)
- Message compaction for tool-turn cycles
- Token budget trimming with summarization
- Session-based context management via ProxySessionManager

### ⚠️ Partial Implementation
- Conditional tool format instructions (still always included)
- Tool result compaction (only for very large results)
- Common tool extraction layer exists but legacy strategies remain

### ❌ Missing Optimizations
- Per-request token logging
- Dynamic tool filtering by relevance
- Binary file detection
- Parallel tool execution
- Early termination detection

---

## Optimization Catalog

### Category 1: 3D LLM Provider Specific

#### 1.1 Connection Pooling & Keep-Alive
**Impact:** Medium (20-50ms per request)  
**Effort:** Low (1-2 hours)  
**Status:** 📋 Planned

**Current:** New `fetch()` call per iteration  
**Optimized:** Reuse HTTP connection with keep-alive

```typescript
// ThreeDLlmProvider.ts
private httpAgent?: any;

constructor(...) {
  if (typeof process !== 'undefined') {
    const http = require('http');
    this.httpAgent = new http.Agent({ 
      keepAlive: true, 
      maxSockets: 5 
    });
  }
}

async callAPI(...) {
  const response = await fetch(url, {
    agent: this.httpAgent,
    headers: { 'Connection': 'keep-alive' }
  });
}
```

**Benefit:** 20-50ms saved per request on local network

---

#### 1.2 Binary File Detection
**Impact:** High (prevents garbled context, token waste)  
**Effort:** Low (1-2 hours)  
**Status:** 📋 Planned

**Current:** Attempts to read all files as text  
**Optimized:** Detect binary files early, skip or base64 encode

```typescript
// tools/fileTools.ts
async function isBinaryFile(filePath: string): Promise<boolean> {
  const buffer = await fs.readFile(filePath, { encoding: null });
  // Check for null bytes in first 8KB
  for (let i = 0; i < Math.min(8192, buffer.length); i++) {
    if (buffer[i] === 0) return true;
  }
  return false;
}
```

**Benefit:** Prevents garbled text in context, reduces token waste

---

#### 1.3 Smart Retry with Exponential Backoff
**Impact:** High (better rate limit handling)  
**Effort:** Low (1-2 hours)  
**Status:** 📋 Planned

**Current:** Fixed 3 retries  
**Optimized:** Exponential backoff + jitter for rate limits

```typescript
// ThreeDLlmProvider.ts
async callWithRetry(request: LLMRequest, maxRetries = 3): Promise<LLMResponse> {
  for (let attempt = 0; attempt < maxRetries; attempt++) {
    try {
      return await this.callAPI(request);
    } catch (error: any) {
      if (error.status === 429) { // Rate limited
        const delay = Math.pow(2, attempt) * 1000 + Math.random() * 1000;
        await new Promise(r => setTimeout(r, delay));
        continue;
      }
      throw error;
    }
  }
}
```

**Benefit:** Better handling of rate limits, fewer failures

---

#### 1.4 Progressive Tool Result Streaming
**Impact:** High (faster time-to-first-token)  
**Effort:** Medium (half-day)  
**Status:** 📋 Planned

**Current:** Wait for full tool result, then send to LLM  
**Optimized:** Stream large file reads in chunks

```typescript
// tools/fileTools.ts
async *streamFileRead(filePath: string): AsyncGenerator<string> {
  const file = await fs.open(filePath);
  const buffer = Buffer.alloc(4096);
  let bytesRead;
  
  while ((bytesRead = await file.read(buffer)) > 0) {
    yield buffer.toString('utf8', 0, bytesRead);
  }
}
```

**Benefit:** LLM can start processing while file still reading

---

### Category 2: Generic Flow Optimizations

#### 2.1 Parallel Independent Tool Execution
**Impact:** High (30-50% faster for multi-file ops)  
**Effort:** Medium (half-day)  
**Status:** 📋 Planned

**Current:** Sequential tool execution  
**Optimized:** Run independent tools in parallel

```typescript
// AgentBridge.ToolPipeline.ts
async executeToolsInParallel(toolCalls: LLMToolCall[]): Promise<ToolResult[]> {
  // Group by dependency
  const independent = toolCalls.filter(tc => 
    ['read_file', 'list_directory', 'search_files'].includes(tc.name)
  );
  
  // Execute independent tools concurrently
  const results = await Promise.all(
    independent.map(tc => this.executeTool(tc))
  );
  
  // Execute dependent tools sequentially
  const dependent = toolCalls.filter(tc => !independent.includes(tc));
  for (const tc of dependent) {
    results.push(await this.executeTool(tc));
  }
  
  return results;
}
```

**Benefit:** 30-50% faster for multi-file operations

---

#### 2.2 Semantic Deduplication of Tool Results
**Impact:** Medium (20-40% token reduction)  
**Effort:** Low (1-2 hours)  
**Status:** 📋 Planned

**Current:** Full tool output sent to LLM  
**Optimized:** Remove redundant information

```typescript
// AgentBridge.ToolPipeline.ts
compactToolResult(toolName: string, result: string): string {
  if (toolName === 'list_directory') {
    // Remove duplicate entries (e.g., ./foo and foo)
    const lines = result.split('\n');
    const normalized = new Set(lines.map(l => path.normalize(l)));
    return Array.from(normalized).join('\n');
  }
  
  if (toolName === 'search_files') {
    // Group by file, show only first 3 matches per file
    const byFile = groupBy(result, 'filePath');
    return Object.entries(byFile)
      .slice(0, 10) // Max 10 files
      .map(([file, matches]) => `${file}: ${matches.length} matches`)
      .join('\n');
  }
  
  return result;
}
```

**Benefit:** 20-40% token reduction in tool results

---

#### 2.3 Context-Aware Tool Selection
**Impact:** Medium (reduces confusion, faster decisions)  
**Effort:** Low (1-2 hours)  
**Status:** 📋 Planned

**Current:** All 21 tools available always  
**Optimized:** Filter tools based on task type

```typescript
// AgentBridge.ts
filterToolsByTask(task: string, allTools: LLMTool[]): LLMTool[] {
  const taskLower = task.toLowerCase();
  
  if (taskLower.includes('fix') || taskLower.includes('error')) {
    // Fix mode: only editing + build tools
    return allTools.filter(t => 
      ['read_file', 'apply_edits', 'write_file', 'run_build'].includes(t.name)
    );
  }
  
  if (taskLower.includes('explore') || taskLower.includes('understand')) {
    // Exploration mode: read/search tools only
    return allTools.filter(t => 
      ['list_directory', 'search_files', 'read_file', 'get_file_context'].includes(t.name)
    );
  }
  
  return allTools;
}
```

**Benefit:** Reduces confusion, smaller prompt, faster decisions

---

#### 2.4 Early Termination Detection
**Impact:** High (stop 1-2 iterations early on average)  
**Effort:** Medium (half-day)  
**Status:** 📋 Planned

**Current:** Run until max iterations or LLM stops  
**Optimized:** Detect task completion early

```typescript
// AgentBridge.ts
isTaskComplete(history: ChatMessage[], toolCalls: ToolCall[]): boolean {
  // Check if build succeeded after edit
  const lastBuild = history.find(m => 
    m.role === 'tool' && m.content.includes('BUILD SUCCESSFUL')
  );
  
  // Check if user confirmed completion
  const lastUser = history.filter(m => m.role === 'user').pop();
  const userConfirmed = lastUser?.content.includes('thanks') || 
                        lastUser?.content.includes('perfect');
  
  // Check if no more actions needed
  const noPendingActions = toolCalls.length === 0 && 
                          !history.some(m => m.content.includes('I will'));
  
  return (lastBuild && noPendingActions) || userConfirmed;
}
```

**Benefit:** Stop 1-2 iterations early on average

---

#### 2.5 Intent-Based Tool Prefetching
**Impact:** High (reduce 1-2 iterations per task)  
**Effort:** High (multi-day)  
**Status:** 📋 Future

**Current:** Wait for LLM to request tools one by one  
**Optimized:** Predict likely next tools based on intent

```typescript
// AgentBridge.ts
predictNextTools(lastTool: string, context: string): string[] {
  if (lastTool === 'read_file' && context.endsWith('.kt')) {
    return ['apply_edits', 'run_build']; // Likely next actions
  }
  if (lastTool === 'search_files' && context.includes('test')) {
    return ['read_file', 'run_terminal']; // Read then run tests
  }
  return [];
}
```

**Benefit:** Reduce iteration count by 1-2 turns per task

---

### Category 3: Architecture-Wide

#### 3.1 Per-Request Token Logging
**Impact:** Medium (visibility for optimization)  
**Effort:** Low (1 hour)  
**Status:** 📋 Planned

```typescript
// ThreeDLlmProvider.ts - in callAPI()
this.log(`[Token Usage] prompt=${usage?.promptTokens}, completion=${usage?.completionTokens}, total=${usage?.totalTokens}`);
```

**Benefit:** Verify flat overhead across tool rounds

---

#### 3.2 Dynamic Tool Filtering
**Impact:** Medium (smaller prompts, faster decisions)  
**Effort:** Medium (half-day)  
**Status:** 📋 Planned

**Current:** All 21 tools sent on every request  
**Optimized:** Filter by relevance

```typescript
// AgentBridge.ts - in getTools()
getTools(lazyProfile?: ContextProfile['lazy'], toolFilter?: string): LLMTool[] {
  const allTools = this.toolRegistry.getLLMTools(this.currentLayer);
  
  // Filter by layer
  let filtered = allTools.filter(t => this.isToolRelevant(t, this.currentLayer));
  
  // Filter by task type
  if (this._currentTaskType === 'fix') {
    filtered = filtered.filter(t => ['apply_edits', 'read_file', 'run_build'].includes(t.name));
  }
  
  return filtered;
}
```

**Benefit:** 30-50% smaller tool catalog, faster LLM decisions

---

#### 3.3 LLM Response Caching
**Impact:** Medium (instant response for repeated patterns)  
**Effort:** Medium (half-day)  
**Status:** 📋 Future

```typescript
// AgentBridge.ts
async getCachedOrCall(task: string, context: string): Promise<LLMResponse> {
  const cacheKey = this.hash(task + context.substring(0, 500));
  const cached = await this.cache.get(cacheKey);
  
  if (cached && Date.now() - cached.timestamp < 5 * 60 * 1000) {
    return cached.response;
  }
  
  const response = await this.callLLM(...);
  await this.cache.set(cacheKey, { response, timestamp: Date.now() });
  return response;
}
```

**Benefit:** Instant response for repeated patterns

---

## Implementation Priority

### Phase 1: Quick Wins (Week 1)
1. ✅ Binary file detection (#1.2)
2. ✅ Smart retry with backoff (#1.3)
3. ✅ Per-request token logging (#3.1)
4. ✅ Semantic deduplication (#2.2)

### Phase 2: Medium Impact (Week 2)
5. ⚠️ Parallel tool execution (#2.1)
6. ⚠️ Context-aware tool filtering (#2.3)
7. ⚠️ Early termination detection (#2.4)
8. ⚠️ Connection pooling (#1.1)

### Phase 3: High Impact (Week 3-4)
9. 🔥 Progressive streaming (#1.4)
10. 🔥 Intent-based prefetching (#2.5)
11. 🔥 Dynamic tool filtering (#3.2)
12. 🔥 LLM response caching (#3.3)

---

## Success Metrics

| Metric | Current | Target | Measurement |
|--------|---------|--------|-------------|
| Avg iterations per task | 5-7 | 3-4 | AgentBridge logs |
| Token usage per iteration | ~2000 | ~1200 | LLM response usage |
| Tool execution latency | 500ms | 300ms | ToolPipeline timing |
| First response time | 2-3s | 1-1.5s | Extension timing |
| Success rate | ~85% | ~95% | Error tracking |

---

## Implementation Checklist

### Phase 1
- [ ] Create `BinaryFileDetector` utility
- [ ] Implement exponential backoff in `ThreeDLlmProvider`
- [ ] Add token logging to all providers
- [ ] Add `compactToolResult()` to `ToolPipeline`

### Phase 2
- [ ] Implement parallel execution in `ToolPipeline`
- [ ] Add task type detection and tool filtering
- [ ] Implement early termination in `AgentBridge`
- [ ] Add HTTP agent pooling to `ThreeDLlmProvider`

### Phase 3
- [ ] Implement streaming file reads
- [ ] Build intent prediction model
- [ ] Add dynamic tool relevance scoring
- [ ] Implement response caching layer

---

## Related Documentation

- `3d-llm-optimization-analysis.md` - Original analysis
- `provider-architecture.md` - Current architecture
- `flow-diagram.md` - Agent flow
