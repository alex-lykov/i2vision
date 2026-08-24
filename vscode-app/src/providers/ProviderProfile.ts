export type ProviderId = 'mistral' | 'ollama' | 'deepseek' | '3dllm';

export interface ProviderModelSettings {
  temperature: number;
  maxTokens: number;
  topP: number;
  stop?: string[];
}

export interface ProviderToolPolicy {
  mode: 'native' | 'prompt';
  promptRules?: string;
}

export interface ProviderPromptPolicy {
  systemRole?: string;
  toolRules?: string;
}

export interface ProviderSessionPolicy {
  compactionThreshold: number;
  resetOnProviderChange: boolean;
}

export interface ProviderProfile {
  id: ProviderId;
  modelDefaults: ProviderModelSettings;
  toolPolicy: ProviderToolPolicy;
  prompt?: ProviderPromptPolicy;
  sessionPolicy: ProviderSessionPolicy;
}

const RESET_SESSION = true;

const TOOL_RULES_COMMON = 'You may call tools using the tool-call protocol described in the system instructions. Use only tools that are currently available. When a tool is required, return exactly one JSON tool call. Do not describe a tool call in prose; emit the tool call itself.';

const TOOL_RULES_3DLLM = 'Tool-call format: { "tool": "toolName", "input": {} }. Always call a tool when a task requires filesystem, terminal, build, or edit operations. If tool output contains an error, fix the input and retry once. After the final tool result, answer the user directly.';

export const PROVIDER_PROFILES: Record<ProviderId, ProviderProfile> = {
  mistral: {
    id: 'mistral',
    modelDefaults: { temperature: 0.7, maxTokens: 4096, topP: 1, stop: [] },
    toolPolicy: { mode: 'prompt', promptRules: TOOL_RULES_COMMON },
    prompt: { toolRules: TOOL_RULES_COMMON },
    sessionPolicy: { compactionThreshold: 0.8, resetOnProviderChange: RESET_SESSION },
  },
  ollama: {
    id: 'ollama',
    modelDefaults: { temperature: 0.8, maxTokens: 2048, topP: 0.9 },
    toolPolicy: { mode: 'prompt', promptRules: TOOL_RULES_COMMON },
    prompt: { toolRules: TOOL_RULES_COMMON },
    sessionPolicy: { compactionThreshold: 0.75, resetOnProviderChange: RESET_SESSION },
  },
  deepseek: {
    id: 'deepseek',
    modelDefaults: { temperature: 0.7, maxTokens: 8192, topP: 1 },
    toolPolicy: { mode: 'prompt', promptRules: TOOL_RULES_COMMON },
    prompt: { toolRules: TOOL_RULES_COMMON },
    sessionPolicy: { compactionThreshold: 0.85, resetOnProviderChange: RESET_SESSION },
  },
  '3dllm': {
    id: '3dllm',
    modelDefaults: { temperature: 0.2, maxTokens: 4096, topP: 0.95 },
    toolPolicy: { mode: 'prompt', promptRules: TOOL_RULES_3DLLM },
    prompt: { toolRules: TOOL_RULES_3DLLM },
    sessionPolicy: { compactionThreshold: 0.9, resetOnProviderChange: RESET_SESSION },
  },
};

export function getProviderProfile(providerId: string): ProviderProfile {
  const key = providerId as ProviderId;
  return PROVIDER_PROFILES[key] ?? PROVIDER_PROFILES.ollama;
}

export function mergeModelSettings(
  defaults: ProviderModelSettings,
  overrides: Partial<ProviderModelSettings> = {},
): ProviderModelSettings {
  return {
    temperature: overrides.temperature ?? defaults.temperature,
    maxTokens: overrides.maxTokens ?? defaults.maxTokens,
    topP: overrides.topP ?? defaults.topP,
    stop: overrides.stop ?? defaults.stop,
  };
}

export function getToolRules(profile: ProviderProfile, toolsEnabled: boolean): string | undefined {
  if (!toolsEnabled) {
    return undefined;
  }
  return profile.prompt?.toolRules ?? profile.toolPolicy.promptRules;
}
