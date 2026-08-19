# VSCode Extension: Provider & Model Selection UI

## Overview

The i2-Vision VSCode extension now includes a **Provider and Model Selection UI** in each agent tab, allowing you to easily switch between different LLM providers and models without manually editing configuration files.

## Features

### 🎯 Provider Selection
- **Ollama (Local + Cloud)** - Single provider for both local and cloud models via Ollama API
- **DeepSeek Direct (Cloud)** - Direct API access to DeepSeek models (bypasses Ollama)

> 💡 **Key Insight**: Ollama provides access to BOTH local models (running on your machine) AND cloud models (via Ollama Cloud API) through the same endpoint. See [Ollama Architecture Guide](OLLAMA-ARCHITECTURE.md) for details.

### 🤖 Model Selection
Dynamic model dropdown that updates based on the selected provider:

#### Ollama Models (Organized by Type)

**Local Models** (Run on your machine):
- Gemma 3 1B (Ultra-fast, 815 MB)
- Qwen 2.5 Coder 0.5B (Quick code, 397 MB)
- Llama 3.2 3B (Fast general purpose, ~2 GB)
- Llama 3.2 7B (Balanced, ~4 GB)
- Llama 3.1 8B (General purpose, ~5 GB)
- Qwen 3 4B (Good coding, 2.5 GB)
- CodeLlama 7B (Code generation, ~4 GB)
- CodeLlama 13B (Advanced coding, ~8 GB)
- Mistral 7B (General purpose, ~4 GB)
- Qwen 2.5 7B (Multilingual, ~4 GB)
- Llama 2 7B (Legacy, 3.8 GB)

**Cloud Models** (Via Ollama Cloud API):
- MiniMax M2.1 (Multilingual code)
- Mistral Large 3 675B (Enterprise reasoning)
- Gemma 4 31B (Multimodal)
- DeepSeek V3.1 671B (Advanced reasoning)
- Qwen 3 Coder 480B (Expert coding)
- Qwen 3.5 (Balanced performance)
- GLM 5.1 / 4.7 / 4.6 (Agentic engineering)
- Kimi K2.6 (Long context)
- Nemotron 3 Super (Multi-agent)
- GPT-OSS 20B (Open GPT alternative)

#### DeepSeek Direct Models
- DeepSeek Chat (V3)
- DeepSeek Coder
- DeepSeek Reasoner (R1)

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
│ Provider: [Ollama (Local) ▼]  Model: [Llama 3.2 3B ▼]      │
│                                              Max Iterations: 10 │
└─────────────────────────────────────────────────────────────┘
```

**To change provider:**
1. Click the **Provider** dropdown
2. Select either "Ollama (Local)" or "DeepSeek (Cloud)"
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

## Architecture

### File Structure
```
vscode-app/src/agent/
├── AgentTabManager.ts          # Main UI manager (updated)
├── LocalAgentProvider.ts       # Config loader/saver
├── LocalI2VisionAgent.ts       # Agent implementation
└── AgentBridge.ts              # Type definitions
```

### Configuration Flow

```
User selects provider/model in UI
        ↓
AgentTabManager.handleWebviewMessage()
        ↓
changeProvider() / changeModel()
        ↓
saveAgentConfig() → Writes to .vision-ai/{layer}-agent.yaml
        ↓
reloadAgent() → Recreates agent with new config
        ↓
Webview notified: 'configUpdated'
```

### Webview Communication

**Messages from Webview to Extension:**
```typescript
// Change provider
{ command: 'changeProvider', provider: 'deepseek' }

// Change model
{ command: 'changeModel', model: 'deepseek-chat' }
```

**Messages from Extension to Webview:**
```typescript
// Configuration updated successfully
{ 
  command: 'configUpdated', 
  provider: 'deepseek', 
  model: 'deepseek-chat' 
}

// Reload webview with new config
{ command: 'configReloaded' }
```

## Configuration File Format

The provider/model selection updates your YAML configuration:

```yaml
# .vision-ai/vision-agent.yaml
key: vision-agent
agentType: configurable
version: 1.0.0
isActive: true

model:
  id: deepseek-chat          # ← Updated by UI
  provider: deepseek         # ← Updated by UI
  contextLength: 32768
  maxOutputTokens: 4096
  temperature: 0.7
  topP: 0.9

# ... rest of config
```

## Technical Details

### Dynamic Model Population

The model dropdown is populated dynamically based on the selected provider:

```javascript
const MODELS_BY_PROVIDER = {
    'ollama': [
        { id: 'llama3.2:3b', name: 'Llama 3.2 3B (Fast)' },
        { id: 'llama3.2:7b', name: 'Llama 3.2 7B' },
        // ... more models
    ],
    'deepseek': [
        { id: 'deepseek-chat', name: 'DeepSeek Chat (V3)' },
        { id: 'deepseek-coder', name: 'DeepSeek Coder' },
        { id: 'deepseek-reasoner', name: 'DeepSeek Reasoner (R1)' }
    ]
};
```

### Config Save Logic

The `saveAgentConfig()` method:
1. Converts `AgentConfig` TypeScript object to YAML format
2. Ensures `.vision-ai/` directory exists
3. Writes YAML file with proper formatting
4. Preserves all other configuration settings

### Agent Reload

When provider/model changes:
1. Config is saved to disk
2. Agent is recreated with new config
3. Webview is notified to update UI
4. Next user input uses the new model

## Adding New Providers

To add a new provider:

### 1. Update `AgentTabManager.ts`

Add provider to the dropdown:
```html
<option value="openai" ${providerId === 'openai' ? 'selected' : ''}>OpenAI (Cloud)</option>
```

### 2. Add Models

Add to `MODELS_BY_PROVIDER`:
```javascript
'openai': [
    { id: 'gpt-4o', name: 'GPT-4o' },
    { id: 'gpt-4-turbo', name: 'GPT-4 Turbo' },
    { id: 'gpt-3.5-turbo', name: 'GPT-3.5 Turbo' }
]
```

### 3. Update Backend

Ensure `LocalAgentProvider` and `LocalI2VisionAgent` support the new provider.

## Troubleshooting

### Provider/Model Not Saving

**Check:**
1. Verify `.vision-ai/` directory exists in workspace
2. Check write permissions
3. Look for errors in Output Channel → i2-Vision

### Dropdown Not Updating

**Check:**
1. Open Developer Tools in webview (Ctrl+Shift+P → "Developer: Toggle Developer Tools")
2. Check console for JavaScript errors
3. Verify `MODELS_BY_PROVIDER` is correctly formatted

### Agent Not Reloading

**Check:**
1. Output Channel → i2-Vision for reload messages
2. Verify YAML file was written correctly
3. Try manual reload: Command Palette → "i2-Vision Debug: Reload Agent Config"

## Best Practices

### Model Selection Guidelines

**For Local Development (Ollama):**
- Use **Llama 3.2 3B** for fast iterations
- Use **CodeLlama 13B** for complex code tasks
- Ensure you have enough RAM (3B ≈ 2GB, 13B ≈ 8GB)

**For Cloud Analysis (DeepSeek):**
- Use **DeepSeek Chat (V3)** for general tasks
- Use **DeepSeek Coder** for code-specific tasks
- Use **DeepSeek Reasoner (R1)** for complex reasoning

### Configuration Management

- **Per-Layer Config**: Each agent layer can use different providers
- **Project-Specific**: Config is saved per-project in `.vision-ai/`
- **Version Control**: Consider committing `.vision-ai/*.yaml` to share team settings

## Future Enhancements

Potential improvements:
- [ ] Add more providers (OpenAI, Anthropic, Google)
- [ ] Model parameters UI (temperature, top_p, etc.)
- [ ] Provider health check / connectivity test
- [ ] Token usage tracking and cost estimation
- [ ] Model comparison / A/B testing
- [ ] Custom model endpoints (for Ollama)

## Related Documentation

- [DeepSeek Integration Guide](deepseek-integration.md)
- [Deployment Guide](DEPLOYMENT.md)
- [Agent Configuration](../reference/AGENT-CONFIG.md)

---

**Version:** 1.0.0  
**Last Updated:** 2026-06-06
