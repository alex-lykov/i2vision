/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

// Built-in provider descriptors for MVVM settings
// Each descriptor describes a provider-specific config UI section

import {SettingDescriptor} from '../model/SettingDescriptor';

export const BUILTIN_PROVIDER_DESCRIPTORS: SettingDescriptor[] = [
  {
    key: 'provider.ollama.baseUrl',
    section: 'Ollama Provider',
    label: 'Base URL',
    description: 'URL of the local Ollama server',
    type: 'string',
    defaultValue: 'http://localhost:11434',
    ui: { order: 1, placeholder: 'http://localhost:11434' }
  },
  {
    key: 'provider.deepseek.baseUrl',
    section: 'DeepSeek Provider',
    label: 'Base URL',
    description: 'Endpoint for DeepSeek API',
    type: 'string',
    defaultValue: 'https://api.deepseek.com',
    ui: { order: 1, placeholder: 'https://api.deepseek.com' }
  },
  {
    key: 'provider.mistral.baseUrl',
    section: 'Mistral Provider',
    label: 'Base URL',
    description: 'Endpoint for Mistral API',
    type: 'string',
    defaultValue: 'https://api.mistral.ai',
    ui: { order: 1, placeholder: 'https://api.mistral.ai' }
  },
  {
    key: 'provider.3d-llm.baseUrl',
    section: '3D LLM Proxy',
    label: 'Proxy URL',
    description: 'URL of the 3D LLM proxy server',
    type: 'string',
    defaultValue: 'http://localhost:9655',
    ui: { order: 1, placeholder: 'http://localhost:9655' }
  }
];
// Alias for compatibility
export const BUILTIN_SETTING_DESCRIPTORS = BUILTIN_PROVIDER_DESCRIPTORS;
