package com.i2vision.llm

/**
 * Utility class for consistent token estimation across all model implementations
 */
object TokenEstimator {
    
    /**
     * Estimate token count for a given text
     * Uses a simple heuristic: approximately 4 characters per token for English text
     * This is a rough estimate and should be replaced with proper tokenization when available
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        
        // Basic heuristic: ~4 characters per token for English
        // This accounts for spaces and punctuation
        return (text.length + 3) / 4
    }
    
    /**
     * Estimate token count with more sophisticated heuristics
     * Takes into account code-specific patterns
     */
    fun estimateTokensForCode(text: String): Int {
        if (text.isEmpty()) return 0
        
        var tokenCount = 0
        
        // Split by lines to handle code patterns better
        text.lines().forEach { line ->
            when {
                // Comments and strings tend to have more natural language
                line.trimStart().startsWith("//") || line.trimStart().startsWith("/*") ||
                line.trimStart().startsWith("*") || line.contains("\"") -> {
                    tokenCount += estimateTokens(line)
                }
                // Code with many symbols typically has more tokens per character
                line.count { it in "(){}[];,.=+-*/<>!&|%~^?" } > line.length / 4 -> {
                    tokenCount += (line.length + 2) / 3 // More tokens for symbol-heavy code
                }
                // Regular code
                else -> {
                    tokenCount += estimateTokens(line)
                }
            }
        }
        
        return tokenCount
    }
    
    /**
     * Estimate max tokens that can fit in context window
     */
    fun estimateMaxTokens(contextLength: Int, promptTokens: Int, reserveForResponse: Int = 512): Int {
        return maxOf(0, contextLength - promptTokens - reserveForResponse)
    }
}
