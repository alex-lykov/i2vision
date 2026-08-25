# VSCode Extension: Provider & Model Selection

## Overview

The i2-Vision VSCode extension supports **four LLM providers** with a unified interface for switching between them. Each provider offers different capabilities for local and cloud-based AI assistance.

## Supported Providers

| Provider | Type | Session Support | Context Compaction | Best For |
|----------|------|----------------|-------------------|----------|
| **Ollama** | Local/Cloud | ❌ | ❌ | Privacy, offline work, fast iteration |
| **DeepSeek** | Cloud | ❌ | ❌ | Advanced reasoning, code generation |
| **Mistral** | Cloud | ✅ | ✅ | Enterprise features, session persistence |
| **3D LLM** | Proxy | ✅ | ✅ | Multi-provider routing, advanced session mgmt |

### Provider Details

#### Ollama (Local + Cloud)

Ollama provides access to both local models (running on your machine) and cloud models via Ollama Cloud API through the same endpoint.

**Local Models** (require Ollama installed locally):
- Gemma 3 1B (Ultra-fast, 815 MB)
- Qwen 2.5 Coder 0.5B (Quick code, 397 MB)
- Llama 3.2 3B (Fast general purpose, ~2 GB)
- Llama 3.2 7B (Balanced, ~4 GB)
- Llama 3.1 8B (General purpose, ~5 GB)
- Qwen 3 4B (Good coding, 2.5 GB)
- CodeLlama 7B (Code generation, ~4 GB)
- CodeLlama 13B (Advanced coding, ~8 GB)
- Mistral 7B (General purpose, ~4 GB)

**Cloud Models** (via Ollama Cloud API):
- MiniMax M2.1 (Multilingual code)
- Mistral Large 3 675B (Enterprise reasoning)
- Gemma 4 31B (Multimodal)
- DeepSeek V3.1 671B (Advanced reasoning)
- Qwen 3 Coder 480B (Expert coding)

**Configuration**:
```yaml
model:
  id: llama3.2:3b
  provider: ollama
```

#### DeepSeek (Cloud)

Direct API access to DeepSeek models for advanced reasoning and code generation.

**Models**:
- DeepSeek Chat (V3) - General purpose
- DeepSeek Coder - Code-specific tasks
- DeepSeek Reasoner (R1) - Complex reasoning

**Configuration**:
```yaml
model:
  id: deepseek-chat
  provider: deepseek
```

#### Mistral (Cloud)

Mistral AI provider with session management and context compaction capabilities.

**Features**:
- ✅ Session persistence across requests
- ✅ Context compaction when approaching token limits
- ✅ Automatic session reset on errors
- ✅ Token usage tracking

**Configuration**:
```yaml
model:
  id: mistral-large
  provider: mistral
```

#### 3D LLM (Proxy)

3D LLM proxy provider for advanced session management and multi-provider routing.

**Features**:
- ✅ Session management via proxy
- ✅ Context compaction
- ✅ Token tracking
- ✅ Multi-provider routing (FreeDeepseekAPI)
- ✅ Native tool call emulation

**Configuration**:
```yaml
model:
  id: 3d-llm-proxy
  provider: 3d-llm
```

## Usage

### 1. Open an Agent Tab

Create a new agent tab using one of the commands:
- `i2-Vision Agents: New Vision Agent Tab`
- `i2-Vision Agents: New Structure Agent Tab`
- `i2-Vision Agents: New Logic Agent Tab`
- `i2-Vision Agents: New Flow Agent Tab`
- `i2-Vision Agents: New Coding Agent Tab`

### 2. Select Provider and Model

In the agent tab header, you'll see the configuration panel:

```
┌─────────────────────────────────────────────────────────────┐
│ Provider: [Ollama ▼]  Model: [Llama 3.2 3B ▼]              │
│                                              Max Iterations: 10 │
└─────────────────────────────────────────────────────────────┘
```

**To change provider:**
1. Click the **Provider** dropdown
2. Select from: Ollama, DeepSeek, Mistral, or 3D LLM
3. The model dropdown will automatically update with available models

**To change model:**
1. Click the **Model** dropdown
2. Select your desired model
3. The agent will reload with the new configuration

### 3. Configuration Persistence

Changes are automatically saved to your project's `.vision-ai/` directory:
- `.vision-ai/vision-agent.yaml`
- `.vision-ai/structure-agent.yaml`
- `.vision-ai/logic-agent.yaml`
- `.vision-ai/flow-agent.yaml`
- `.vision-ai/code-agent.yaml`

Each layer has its own independent configuration.

## Provider Selection Guidelines

### When to Use Each Provider

| Use Case | Recommended Provider | Reason |
|----------|---------------------|--------|
| **Local development** | Ollama | Privacy, no API costs, offline capable |
| **Quick iterations** | Ollama (small models) | Fast response times |
| **Complex reasoning** | DeepSeek or Mistral | Advanced capabilities |
| **Code generation** | DeepSeek Coder or Ollama CodeLlama | Specialized training |
| **Long conversations** | Mistral or 3D LLM | Session management, context compaction |
| **Enterprise features** | Mistral | Session persistence, token tracking |
| **Multi-provider routing** | 3D LLM | Proxy-based flexibility |

### Model Selection Guidelines

**For Local Development (Ollama):**
- Use **Llama 3.2 3B** for fast iterations
- Use **CodeLlama 13B** for complex code tasks
- Ensure you have enough RAM (3B ≈ 2GB, 13B ≈ 8GB)

**For Cloud Analysis (DeepSeek/Mistral):**
- Use **DeepSeek Chat** or **Mistral Medium** for general tasks
- Use **DeepSeek Coder** for code-specific tasks
- Use **DeepSeek Reasoner** or **Mistral Large** for complex reasoning

**For Long Sessions (Mistral/3D LLM):**
- Use when conversation exceeds 50+ messages
- Enable automatic context compaction
- Monitor token usage for cost control

## Architecture

### Provider Factory Pattern

The extension uses a `ProviderFactory` to create provider-specific instances:

```typescript
const provider = ProviderFactory.createProvider(modelId)
```

This pattern allows:
- Unified interface across providers
- Provider-specific optimizations
- Easy addition of new providers

### Session Management

Providers with session support (Mistral, 3D LLM) use `ProxySessionManager`:

```
AgentBridge
    ↓
SessionManager (Interface)
├── ProxySessionManager (Mistral, 3D LLM)
└── NullSessionManager (Ollama, DeepSeek)
```

See [Session Management Architecture](../architecture/session-management.md) for details.

### Provider Capabilities

| Capability | Ollama | DeepSeek | Mistral | 3D LLM |
|------------|--------|----------|---------|--------|
| Streaming | ✅ | ✅ | ✅ | ✅ |
| Native Tool Calls | ❌ | ❌ | ✅ | ✅ (emulated) |
| Session Management | ❌ | ❌ | ✅ | ✅ |
| Context Compaction | ❌ | ❌ | ✅ | ✅ |
| Token Tracking | ❌ | ❌ | ✅ | ✅ |

## Configuration File Format

The provider/model selection updates your YAML configuration:

```yaml
# .vision-ai/vision-agent.yaml
key: vision-agent
agentType: configurable
version: 1.0.0
isActive: true

model:
  id: mistral-large          # ← Updated by UI
  provider: mistral          # ← Updated by UI
  contextLength: 32768
  maxOutputTokens: 4096
  temperature: 0.7
  topP: 0.9

# ... rest of config
```

## Adding New Providers

To add a new provider:

### 1. Create Provider Implementation

```typescript
// src/providers/newprovider/NewProvider.ts
export class NewProvider implements LLMProvider {
  async callAPI(request: LLMRequest): Promise<LLMResponse> {
    // Implementation
  }
}
```

### 2. Register in ProviderFactory

```typescript
// src/providers/ProviderFactory.ts
switch (provider) {
  case 'newprovider':
    return new NewProvider()
}
```

### 3. Add Provider Capabilities

```typescript
// src/types/provider-types.ts
getProviderCapabilities('newprovider'): ProviderCapabilities {
  return {
    streaming: true,
    nativeToolCalls: false,
    sessionManagement: false,
    contextCompaction: false
  }
}
```

### 4. Update UI

Add provider to the dropdown in `AgentTabManager.ts`:

```html
<option value="newprovider" ${providerId === 'newprovider' ? 'selected' : ''}>New Provider</option>
```

## Troubleshooting

### Provider Not Connecting

**Check:**
1. Verify API key is configured (for cloud providers)
2. Check network connectivity
3. Look for errors in Output Channel → i2-Vision
4. Verify provider endpoint is accessible

### Session Errors (Mistral/3D LLM)

**Check:**
1. Session may have expired - try resetting
2. Context may be exhausted - check token usage
3. Network issues - verify connectivity

### Model Not Available

**For Ollama:**
1. Run `ollama list` to see available models
2. Pull missing models: `ollama pull <model-name>`
3. Check Ollama is running: `ollama serve`

**For Cloud Providers:**
1. Verify API key is valid
2. Check model is available in your region
3. Review provider documentation for model availability

## Related Documentation

- [Provider Architecture](../architecture/provider-architecture.md)
- [Session Management](../architecture/session-management.md)
- [DeepSeek Integration](deepseek-integration.md)
- [Ollama Integration](ollama-integration.md)
- [Provider Rules](../concepts/provider-rules.md)

---

**Version:** 2.0.0  
**Last Updated:** 2026-08-24  
**Changes:** Added Mistral and 3D LLM providers, updated provider capabilities table
