/**
 * Type definitions for LLM providers
 */

export interface LLMRequest {
  prompt: string;
  model?: string;
  temperature?: number;
  topP?: number;
  topK?: number;
  maxTokens?: number;
  timeoutSeconds?: number;
  [key: string]: any; // Allow provider-specific options
}

export interface LLMResponse {
  text: string;
  model: string;
  provider: string;
  usage?: {
    promptTokens: number;
    completionTokens: number;
    totalTokens?: number;
  };
  [key: string]: any; // Allow provider-specific response data
}

export interface LLMProvider {
  /**
   * Get the name of the provider
   */
  getProviderName(): string;

  /**
   * Validate provider configuration
   * @throws Error if configuration is invalid
   */
  validateConfiguration(): void;

  /**
   * Make an API call to the LLM provider
   * @param request The LLM request
   * @returns Promise with LLM response
   */
  callAPI(request: LLMRequest): Promise<LLMResponse>;

  /**
   * Check if the provider is available
   * @returns Promise with availability status
   */
  isAvailable?(): Promise<boolean>;

  /**
   * Get list of available models
   * @returns Promise with list of model names
   */
  listModels?(): Promise<string[]>;
}