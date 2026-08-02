/**
 * Tests for ProviderFactory
 */

import { ProviderFactory } from '../ProviderFactory';
import { OllamaProvider } from '../ollama/OllamaProvider';
import { DeepSeekProvider } from '../deepseek/DeepSeekProvider';
import { ThreeDLlmProvider } from '../3dllm/ThreeDLlmProvider';
import { MistralProvider } from '../mistral/MistralProvider';

describe('ProviderFactory', () => {
  let providerFactory: ProviderFactory;

  beforeEach(() => {
    providerFactory = new ProviderFactory();
  });

  describe('createProvider', () => {
    it('should create Ollama provider for Ollama models', () => {
      const ollamaModels = [
        'ollama:llama3',
        'llama3.2:3b',
        'codellama:latest',
        'vicuna:13b'
      ];

      ollamaModels.forEach(modelId => {
        const provider = providerFactory.createProvider(modelId, {});
        expect(provider).toBeInstanceOf(OllamaProvider);
        expect(provider.getProviderName()).toBe('Ollama');
      });
    });

    it('should create Ollama provider for Mistral models', () => {
      const mistralModels = [
        'mistral:7b',
        'mistral:latest',
        'mistral-7b-instruct',
        'mistral:7b-instruct-v0.2'
      ];

      mistralModels.forEach(modelId => {
        const provider = providerFactory.createProvider(modelId, {});
        expect(provider).toBeInstanceOf(OllamaProvider);
        expect(provider.getProviderName()).toBe('Ollama');
      });
    });

    it('should create Mistral provider for Mistral Cloud API models', () => {
      const mistralModels = [
        'mistral-tiny',
        'mistral-small',
        'mistral-medium',
        'mistral-large',
        'mistral-embed'
      ];

      mistralModels.forEach(modelId => {
        const config = { mistralApiKey: 'test-key' };
        const provider = providerFactory.createProvider(modelId, config);
        expect(provider).toBeInstanceOf(MistralProvider);
        expect(provider.getProviderName()).toBe('Mistral');
      });
    });

    it('should create DeepSeek provider for DeepSeek models', () => {
      const deepSeekModels = [
        'deepseek:chat',
        'deepseek-chat',
        'deepseek-coder',
        'deepseek-reasoner'
      ];

      deepSeekModels.forEach(modelId => {
        const config = { deepSeekApiKey: 'test-key' };
        const provider = providerFactory.createProvider(modelId, config);
        expect(provider).toBeInstanceOf(DeepSeekProvider);
        expect(provider.getProviderName()).toBe('DeepSeek');
      });
    });

    it('should create 3D LLM provider for 3D LLM models', () => {
      const threeDLlmModels = [
        '3dllm:deepseek-web',
        'deepseek-web-v3',
        'free-deepseek'
      ];

      threeDLlmModels.forEach(modelId => {
        const provider = providerFactory.createProvider(modelId, {});
        expect(provider).toBeInstanceOf(ThreeDLlmProvider);
        expect(provider.getProviderName()).toBe('3D LLM');
      });
    });

    it('should default to 3D LLM provider for unknown models', () => {
      const unknownModels = [
        'unknown-model',
        'custom:model',
        'some-random-name'
      ];

      unknownModels.forEach(modelId => {
        const provider = providerFactory.createProvider(modelId, {});
        expect(provider).toBeInstanceOf(ThreeDLlmProvider);
        expect(provider.getProviderName()).toBe('3D LLM');
      });
    });

    it('should throw error for DeepSeek models without API key', () => {
      const deepSeekModels = [
        'deepseek-chat',
        'deepseek:coder'
      ];

      deepSeekModels.forEach(modelId => {
        expect(() => {
          providerFactory.createProvider(modelId, {});
        }).toThrow('DeepSeek API key is required');
      });
    });
  });

  describe('provider detection methods', () => {
    it('should correctly identify Ollama models', () => {
      const ollamaModels = [
        'ollama:llama3',
        'llama3.2:3b',
        'mistral:7b',
        'codellama:latest',
        'vicuna:13b',
        'orca:mini'
      ];

      ollamaModels.forEach(modelId => {
        expect((providerFactory as any).isOllamaModel(modelId)).toBe(true);
        expect((providerFactory as any).isDeepSeekModel(modelId)).toBe(false);
        expect((providerFactory as any).isThreeDLlmModel(modelId)).toBe(false);
      });
    });

    it('should correctly identify Mistral Cloud API models', () => {
      const mistralCloudModels = [
        'mistral-tiny',
        'mistral-small',
        'mistral-medium',
        'mistral-large',
        'mistral-embed'
      ];

      mistralCloudModels.forEach(modelId => {
        expect((providerFactory as any).isMistralModel(modelId)).toBe(true);
        expect((providerFactory as any).isOllamaModel(modelId)).toBe(false);
        expect((providerFactory as any).isDeepSeekModel(modelId)).toBe(false);
        expect((providerFactory as any).isThreeDLlmModel(modelId)).toBe(false);
      });
    });

    it('should correctly identify local Mistral models as Ollama models', () => {
      const localMistralModels = [
        'mistral:7b',
        'mistral:7b-instruct-v0.2'
      ];

      localMistralModels.forEach(modelId => {
        // Local Mistral models served via Ollama are NOT Mistral Cloud API models
        expect((providerFactory as any).isMistralModel(modelId)).toBe(false);
        expect((providerFactory as any).isOllamaModel(modelId)).toBe(true);
      });
    });

    it('should correctly identify DeepSeek models', () => {
      const deepSeekModels = [
        'deepseek:chat',
        'deepseek-chat',
        'deepseek-coder',
        'deepseek-reasoner'
      ];

      deepSeekModels.forEach(modelId => {
        expect((providerFactory as any).isDeepSeekModel(modelId)).toBe(true);
        expect((providerFactory as any).isOllamaModel(modelId)).toBe(false);
        expect((providerFactory as any).isThreeDLlmModel(modelId)).toBe(false);
      });
    });

    it('should correctly identify 3D LLM models', () => {
      const threeDLlmModels = [
        '3dllm:deepseek-web',
        'deepseek-web-v3',
        'free-deepseek',
        '3dllm:custom-model'
      ];

      threeDLlmModels.forEach(modelId => {
        expect((providerFactory as any).isThreeDLlmModel(modelId)).toBe(true);
        expect((providerFactory as any).isOllamaModel(modelId)).toBe(false);
        expect((providerFactory as any).isDeepSeekModel(modelId)).toBe(false);
      });
    });
  });

  describe('getAvailableProviders', () => {
    it('should return all available provider configurations', () => {
      const providers = providerFactory.getAvailableProviders();
      
      expect(providers).toHaveLength(4);

      const providerIds = providers.map(p => p.id);
      expect(providerIds).toContain('mistral');
      expect(providerIds).toContain('ollama');
      expect(providerIds).toContain('deepseek');
      expect(providerIds).toContain('3dllm');
    });

    it('should include Mistral in Ollama provider description', () => {
      const providers = providerFactory.getAvailableProviders();
      const ollamaProvider = providers.find(p => p.id === 'ollama');
      
      expect(ollamaProvider).toBeDefined();
      expect(ollamaProvider?.description).toContain('Mistral');
    });
  });

  describe('validateConfiguration', () => {
    it('should validate Ollama configuration', () => {
      expect(providerFactory.validateConfiguration('ollama', {})).toBe(true);
      expect(providerFactory.validateConfiguration('ollama', { ollamaUrl: 'http://localhost:11434' })).toBe(true);
    });

    it('should validate Mistral configuration', () => {
      expect(providerFactory.validateConfiguration('mistral', { mistralApiKey: 'test-key' })).toBe(true);
      expect(providerFactory.validateConfiguration('mistral', {})).toBe(false);
    });

    it('should validate DeepSeek configuration', () => {
      expect(providerFactory.validateConfiguration('deepseek', { deepSeekApiKey: 'test-key' })).toBe(true);
      expect(providerFactory.validateConfiguration('deepseek', {})).toBe(false);
    });

    it('should validate 3D LLM configuration', () => {
      expect(providerFactory.validateConfiguration('3dllm', {})).toBe(true);
      expect(providerFactory.validateConfiguration('3dllm', { threeDLlmUrl: 'http://localhost:9655' })).toBe(true);
    });
  });
});