/**
 * Provider Factory - Creates LLM providers with unified error handling
 */

import { ErrorHandler } from '../core/ErrorHandler';
import { OllamaProvider } from './ollama/OllamaProvider';
import { DeepSeekProvider } from './deepseek/DeepSeekProvider';
import { ThreeDLlmProvider } from './3dllm/ThreeDLlmProvider';
import { MistralProvider } from './mistral/MistralProvider';
import { LLMProvider } from '../types/provider-types';

export class ProviderFactory {
  private errorHandler: ErrorHandler;

  constructor() {
    this.errorHandler = new ErrorHandler();
  }

  /**
   * Create a provider instance based on model ID
   */
  createProvider(modelId: string, config: any): LLMProvider {
    // Determine provider based on model ID
    if (this.isMistralModel(modelId)) {
      return this.createMistralProvider(config);
    } else if (this.isOllamaModel(modelId)) {
      return this.createOllamaProvider(config);
    } else if (this.isDeepSeekModel(modelId)) {
      return this.createDeepSeekProvider(config);
    } else if (this.isThreeDLlmModel(modelId)) {
      return this.createThreeDLlmProvider(config);
    }

    // Default to 3D LLM for unknown models
    return this.createThreeDLlmProvider(config);
  }

  /**
   * Create Ollama provider
   */
  private createOllamaProvider(config: any): LLMProvider {
    const baseUrl = config.ollamaUrl || 'http://localhost:11434';
    const defaultModel = config.ollamaModel || 'llama3.2:3b';
    
    return new OllamaProvider(baseUrl, defaultModel, this.errorHandler);
  }

  /**
   * Create Mistral provider
   */
  private createMistralProvider(config: any): LLMProvider {
    const apiKey = config.mistralApiKey;
    if (!apiKey) {
      throw new Error('Mistral API key is required');
    }
    
    const baseUrl = config.mistralUrl || 'https://api.mistral.ai';
    return new MistralProvider(apiKey, baseUrl, this.errorHandler);
  }

  /**
   * Create DeepSeek provider
   */
  private createDeepSeekProvider(config: any): LLMProvider {
    const apiKey = config.deepSeekApiKey;
    if (!apiKey) {
      throw new Error('DeepSeek API key is required');
    }
    
    const baseUrl = config.deepSeekUrl || 'https://api.deepseek.com';
    return new DeepSeekProvider(apiKey, baseUrl, this.errorHandler);
  }

  /**
   * Create 3D LLM provider
   */
  private createThreeDLlmProvider(config: any): LLMProvider {
    const baseUrl = config.threeDLlmUrl || 'http://localhost:9655';
    const defaultModel = config.threeDLlmModel || 'deepseek-web-v3';
    
    return new ThreeDLlmProvider(baseUrl, defaultModel, this.errorHandler);
  }

  /**
   * Check if model ID belongs to Mistral Cloud API (requires API key).
   * Local Mistral models served via Ollama are NOT matched here.
   */
  private isMistralModel(modelId: string): boolean {
    return modelId === 'mistral-tiny' ||
           modelId === 'mistral-small' ||
           modelId === 'mistral-medium' ||
           modelId === 'mistral-large' ||
           modelId === 'mistral-embed' ||
           modelId.startsWith('mistral:tiny') ||
           modelId.startsWith('mistral:small') ||
           modelId.startsWith('mistral:medium') ||
           modelId.startsWith('mistral:large') ||
           modelId.startsWith('mistral:embed');
  }

  /**
   * Check if model ID belongs to Ollama
   * Note: Local Mistral models (mistral:7b) go to Ollama, but Mistral Cloud API models go to MistralProvider
   */
  private isOllamaModel(modelId: string): boolean {
    return modelId.startsWith('ollama:') || 
           modelId.includes('llama') || 
           (modelId.includes('mistral') && !this.isMistralModel(modelId)) ||
           modelId.includes('vicuna') ||
           modelId.includes('orca') ||
           modelId.includes('codellama');
  }

  /**
   * Check if model ID belongs to DeepSeek
   */
  private isDeepSeekModel(modelId: string): boolean {
    return modelId.startsWith('deepseek:') || 
           modelId.includes('deepseek-chat') || 
           modelId.includes('deepseek-reasoner') ||
           modelId.includes('deepseek-coder');
  }

  /**
   * Check if model ID belongs to 3D LLM
   */
  private isThreeDLlmModel(modelId: string): boolean {
    return modelId.startsWith('3dllm:') || 
           modelId.includes('deepseek-web') ||
           modelId.includes('free-deepseek');
  }

  /**
   * Get all available provider configurations
   */
  getAvailableProviders(): { id: string; name: string; description: string }[] {
    return [
      {
        id: 'mistral',
        name: 'Mistral',
        description: 'Mistral Cloud API (mistral-tiny, mistral-small, etc.)'
      },
      {
        id: 'ollama',
        name: 'Ollama',
        description: 'Local and cloud Ollama models (Llama, Vicuna, local Mistral models, etc.)'
      },
      {
        id: 'deepseek',
        name: 'DeepSeek',
        description: 'DeepSeek cloud API'
      },
      {
        id: '3dllm',
        name: '3D LLM',
        description: 'FreeDeepseekAPI proxy (no API key required)'
      }
    ];
  }

  /**
   * Validate provider configuration
   */
  validateConfiguration(providerId: string, config: any): boolean {
    try {
      if (providerId === 'mistral' && !config.mistralApiKey) {
        return false;
      }
      if (providerId === 'deepseek' && !config.deepSeekApiKey) {
        return false;
      }
      return true;
    } catch (error) {
      return false;
    }
  }
}