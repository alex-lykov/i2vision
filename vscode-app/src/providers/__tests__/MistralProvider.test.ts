/**
 * Tests for MistralProvider
 */

import { MistralProvider } from '../mistral/MistralProvider';
import { ErrorHandler } from '../../core/ErrorHandler';

describe('MistralProvider', () => {
  let mistralProvider: MistralProvider;
  let mockErrorHandler: jest.Mocked<ErrorHandler>;

  beforeEach(() => {
    mockErrorHandler = {
      handleError: jest.fn()
    } as any;
    
    mistralProvider = new MistralProvider('test-api-key', 'https://api.mistral.ai', mockErrorHandler);
  });

  describe('constructor and configuration', () => {
    it('should create provider with default URL', () => {
      const provider = new MistralProvider('test-key', undefined as any, mockErrorHandler);
      expect(provider.getProviderName()).toBe('Mistral');
    });

    it('should create provider with custom URL', () => {
      const customUrl = 'https://custom.mistral.ai';
      const provider = new MistralProvider('test-key', customUrl, mockErrorHandler);
      expect(provider.getProviderName()).toBe('Mistral');
    });

    it('should throw error when API key is missing during validation', () => {
      const providerWithoutKey = new MistralProvider('', 'https://api.mistral.ai', mockErrorHandler);
      expect(() => providerWithoutKey.validateConfiguration()).toThrow('Mistral API key is required');
    });

    it('should validate configuration correctly', () => {
      expect(() => mistralProvider.validateConfiguration()).not.toThrow();
      
      const providerWithoutKey = new MistralProvider('', 'https://api.mistral.ai', mockErrorHandler);
      expect(() => providerWithoutKey.validateConfiguration()).toThrow('Mistral API key is required');
    });
  });

  describe('model detection', () => {
    it('should correctly identify Mistral Cloud API models', () => {
      const mistralModels = [
        'mistral-tiny',
        'mistral-small', 
        'mistral-medium',
        'mistral-large',
        'mistral-embed'
      ];

      mistralModels.forEach(modelId => {
        expect(MistralProvider.isMistralModel(modelId)).toBe(true);
      });
    });

    it('should not confuse local Mistral models with Cloud API models', () => {
      // These should NOT be identified as Mistral Cloud API models
      // (they would go to Ollama provider for local execution)
      expect(MistralProvider.isMistralModel('mistral:7b')).toBe(false);
      expect(MistralProvider.isMistralModel('local-mistral-model')).toBe(false);
    });

    it('should return false for non-Mistral models', () => {
      const nonMistralModels = [
        'llama3:70b',
        'deepseek-chat',
        'gpt-4',
        'custom-model'
      ];

      nonMistralModels.forEach(modelId => {
        expect(MistralProvider.isMistralModel(modelId)).toBe(false);
      });
    });
  });

  describe('callAPI', () => {
    it('should use default model when not specified', async () => {
      mockErrorHandler.handleError.mockImplementation(async (operation) => operation());
      
      global.fetch = jest.fn(() =>
        Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ choices: [{ message: { content: 'response' } }] })
        })
      ) as any;

      await mistralProvider.callAPI({ prompt: 'Test prompt' });
      
      // The mock should have been called with the operation
      expect(mockErrorHandler.handleError).toHaveBeenCalled();
    });

    it('should handle API errors with proper error messages', async () => {
      const testCases = [
        { status: 401, expectedMessage: '401 Unauthorized' },
        { status: 402, expectedMessage: '402' },
        { status: 429, expectedMessage: '429' },
        { status: 404, expectedMessage: '404' }
      ];

      for (const testCase of testCases) {
        global.fetch = jest.fn(() =>
          Promise.resolve({
            ok: false,
            status: testCase.status,
            statusText: 'Error',
            text: () => Promise.resolve('Error details')
          })
        ) as any;

        // Make handleError re-throw to test error classification
        mockErrorHandler.handleError.mockImplementation(async (operation) => {
          try {
            return await operation();
          } catch (e) {
            throw e;
          }
        });

        await expect(mistralProvider.callAPI({ prompt: 'Test' }))
          .rejects
          .toThrow(testCase.expectedMessage);
      }
    });

    it('should include tool calls in response when present', async () => {
      const mockResponse = {
        choices: [{
          message: {
            content: 'Test response',
            tool_calls: [{
              id: 'call_123',
              type: 'function',
              function: {
                name: 'test_function',
                arguments: '{"param": "value"}'
              }
            }]
          }
        }],
        usage: {
          prompt_tokens: 10,
          completion_tokens: 20,
          total_tokens: 30
        }
      };

      global.fetch = jest.fn(() => 
        Promise.resolve({
          ok: true,
          json: () => Promise.resolve(mockResponse)
        })
      ) as any;

      mockErrorHandler.handleError.mockImplementation(async (operation) => operation());
      
      const result = await mistralProvider.callAPI({
        prompt: 'Test',
        model: 'mistral-tiny'
      });

      expect(result.text).toBe('Test response');
      expect(result.tool_calls).toBeDefined();
      expect(result.tool_calls?.length).toBe(1);
      expect(result.usage).toBeDefined();
    });
  });

  describe('listModels', () => {
    it('should return list of available models', async () => {
      const mockModels = {
        data: [
          { id: 'mistral-tiny' },
          { id: 'mistral-small' },
          { id: 'mistral-medium' }
        ]
      };

      global.fetch = jest.fn(() => 
        Promise.resolve({
          ok: true,
          json: () => Promise.resolve(mockModels)
        })
      ) as any;

      const models = await mistralProvider.listModels();
      expect(models).toEqual(['mistral-tiny', 'mistral-small', 'mistral-medium']);
    });

    it('should return empty array on error', async () => {
      global.fetch = jest.fn(() => 
        Promise.reject(new Error('Network error'))
      ) as any;

      const models = await mistralProvider.listModels();
      expect(models).toEqual([]);
    });
  });

  describe('isAvailable', () => {
    it('should return true when API is available', async () => {
      global.fetch = jest.fn(() => 
        Promise.resolve({ ok: true })
      ) as any;

      const available = await mistralProvider.isAvailable();
      expect(available).toBe(true);
    });

    it('should return false when API is unavailable', async () => {
      global.fetch = jest.fn(() => 
        Promise.reject(new Error('Network error'))
      ) as any;

      const available = await mistralProvider.isAvailable();
      expect(available).toBe(false);
    });
  });

  describe('createEmbeddings', () => {
    it('should create embeddings for string input', async () => {
      const mockEmbeddingResponse = {
        data: [{
          embedding: [0.1, 0.2, 0.3],
          index: 0
        }],
        model: 'mistral-embed',
        usage: {
          prompt_tokens: 5,
          total_tokens: 5
        }
      };

      global.fetch = jest.fn(() => 
        Promise.resolve({
          ok: true,
          json: () => Promise.resolve(mockEmbeddingResponse)
        })
      ) as any;

      mockErrorHandler.handleError.mockImplementation(async (operation) => operation());
      
      const result = await mistralProvider.createEmbeddings('Test input');
      expect(result).toEqual(mockEmbeddingResponse);
    });

    it('should create embeddings for array input', async () => {
      const mockEmbeddingResponse = {
        data: [
          { embedding: [0.1, 0.2], index: 0 },
          { embedding: [0.3, 0.4], index: 1 }
        ],
        model: 'mistral-embed'
      };

      global.fetch = jest.fn(() => 
        Promise.resolve({
          ok: true,
          json: () => Promise.resolve(mockEmbeddingResponse)
        })
      ) as any;

      mockErrorHandler.handleError.mockImplementation(async (operation) => operation());
      
      const result = await mistralProvider.createEmbeddings(['Input 1', 'Input 2']);
      expect(result.data).toHaveLength(2);
    });
  });

  describe('getModelInfo', () => {
    it('should return model information', async () => {
      const mockModelInfo = {
        id: 'mistral-tiny',
        object: 'model',
        created: 1234567890,
        owned_by: 'mistral'
      };

      global.fetch = jest.fn(() => 
        Promise.resolve({
          ok: true,
          json: () => Promise.resolve(mockModelInfo)
        })
      ) as any;

      mockErrorHandler.handleError.mockImplementation(async (operation) => operation());
      
      const result = await mistralProvider.getModelInfo('mistral-tiny');
      expect(result).toEqual(mockModelInfo);
    });
  });

  describe('error handling integration', () => {
    it('should use error handler for all API calls', async () => {
      mockErrorHandler.handleError.mockImplementation(async (operation) => operation());
      
      // Test that various methods use the error handler
      await mistralProvider.callAPI({ prompt: 'Test' });
      await mistralProvider.createEmbeddings('Test');
      await mistralProvider.getModelInfo('mistral-tiny');
      
      // Each call should have used the error handler
      expect(mockErrorHandler.handleError).toHaveBeenCalledTimes(3);
    });

    it('should pass correct context to error handler', async () => {
      let capturedContext: any = null;
      mockErrorHandler.handleError.mockImplementation(async (operation, providerName, context) => {
        capturedContext = context;
        return operation();
      });

      await mistralProvider.callAPI({
        prompt: 'Test prompt',
        model: 'mistral-small',
        temperature: 0.7
      });

      expect(capturedContext).toBeDefined();
      expect(capturedContext?.request).toBeDefined();
      expect(capturedContext?.request.model).toBe('mistral-small');
      expect(capturedContext?.request.prompt).toBe('Test prompt');
    });
  });
});