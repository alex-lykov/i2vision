package com.alyk.ai.koog.core.orchestrator.mcp.ml

import kotlinx.serialization.Serializable

/**
 * Machine Learning components for intelligent tool selection
 */

@Serializable
data class FeatureVector(
    val promptEmbedding: DoubleArray,
    val projectContextScore: Double,
    val historicalSuccessRate: Double,
    val userFeedbackScore: Double,
    val toolComplexity: Double
)

@Serializable
data class TrainingExample(
    val prompt: String,
    val selectedTool: String,
    val wasSuccessful: Boolean,
    val userRating: Double? = null,
    val executionTime: Long,
    val features: FeatureVector
)

@Serializable
data class MLModel(
    val weights: Map<String, DoubleArray>,
    val bias: Double,
    val accuracy: Double = 0.0,
    val lastTrained: Long = System.currentTimeMillis()
)

class ToolSelectionML {
    private var model: MLModel = MLModel(emptyMap(), 0.0)
    private val trainingData = mutableListOf<TrainingExample>()
    private val learningRate = 0.01
    
    /**
     * Extract features from prompt and context for ML prediction
     */
    fun extractFeatures(prompt: String, projectKeywords: Map<String, Double>, toolName: String): FeatureVector {
        // Simple embedding simulation (in real implementation, use proper NLP)
        val promptEmbedding = createPromptEmbedding(prompt)
        
        // Project context relevance
        val projectContextScore = calculateProjectRelevance(prompt, projectKeywords, toolName)
        
        // Historical success rate for this tool
        val historicalSuccessRate = calculateHistoricalSuccessRate(toolName)
        
        // User feedback score
        val userFeedbackScore = calculateUserFeedbackScore(toolName)
        
        // Tool complexity based on category
        val toolComplexity = calculateToolComplexity(toolName)
        
        return FeatureVector(
            promptEmbedding = promptEmbedding,
            projectContextScore = projectContextScore,
            historicalSuccessRate = historicalSuccessRate,
            userFeedbackScore = userFeedbackScore,
            toolComplexity = toolComplexity
        )
    }
    
    /**
     * Predict tool selection score using ML model
     */
    fun predictScore(features: FeatureVector, toolName: String): Double {
        val weights = model.weights[toolName] ?: DoubleArray(5) { 0.1 }
        
        // Linear combination of features with weights
        val score = (
            features.promptEmbedding.sum() * weights[0] +
            features.projectContextScore * weights[1] +
            features.historicalSuccessRate * weights[2] +
            features.userFeedbackScore * weights[3] +
            features.toolComplexity * weights[4] +
            model.bias
        )
        
        return score.coerceIn(0.0, 1.0)
    }
    
    /**
     * Train the model with new example
     */
    fun trainModel(example: TrainingExample) {
        trainingData.add(example)
        
        // Simple gradient descent update (simplified)
        val prediction = predictScore(example.features, example.selectedTool)
        val error = if (example.wasSuccessful) 1.0 - prediction else 0.0 - prediction
        
        // Update weights (simplified learning)
        updateWeights(example.selectedTool, error, example.features)
        
        // Retrain periodically
        if (trainingData.size % 10 == 0) {
            retrainModel()
        }
    }
    
    /**
     * Calculate dynamic threshold based on model confidence
     */
    fun calculateDynamicThreshold(prompt: String, availableTools: List<String>): Double {
        if (trainingData.size < 5) return 0.2 // fallback to fixed threshold
        
        // Calculate confidence based on prediction variance
        val predictions = availableTools.map { tool ->
            val features = extractFeatures(prompt, emptyMap(), tool)
            predictScore(features, tool)
        }
        
        val mean = predictions.average()
        val variance = predictions.map { (it - mean).pow(2) }.average()
        val confidence = 1.0 / (1.0 + variance)
        
        // Dynamic threshold: lower when confident, higher when uncertain
        return 0.1 + (0.3 * (1.0 - confidence))
    }
    
    private fun createPromptEmbedding(prompt: String): DoubleArray {
        // Simplified embedding - in real implementation use word2vec, BERT, etc.
        val words = prompt.lowercase().split(" ", ".", "_", "-")
        val embedding = DoubleArray(50) { 0.0 } // 50-dimensional embedding
        
        words.forEachIndexed { index, word ->
            if (index < 50) {
                embedding[index] = word.hashCode().toDouble() / Int.MAX_VALUE.toDouble()
            }
        }
        
        return embedding
    }
    
    private fun calculateProjectRelevance(prompt: String, projectKeywords: Map<String, Double>, toolName: String): Double {
        val promptWords = prompt.lowercase().split(" ", ".", "_", "-")
        val relevantKeywords = projectKeywords.filter { (keyword, _) ->
            promptWords.any { it.contains(keyword) }
        }
        
        return if (relevantKeywords.isNotEmpty()) {
            relevantKeywords.values.average()
        } else 0.0
    }
    
    private fun calculateHistoricalSuccessRate(toolName: String): Double {
        val toolExamples = trainingData.filter { it.selectedTool == toolName }
        return if (toolExamples.isNotEmpty()) {
            toolExamples.count { it.wasSuccessful }.toDouble() / toolExamples.size
        } else 0.5 // default neutral
    }
    
    private fun calculateUserFeedbackScore(toolName: String): Double {
        val toolExamples = trainingData.filter { it.selectedTool == toolName && it.userRating != null }
        return if (toolExamples.isNotEmpty()) {
            toolExamples.mapNotNull { it.userRating }.average() / 5.0 // normalize to 0-1
        } else 0.5
    }
    
    private fun calculateToolComplexity(toolName: String): Double {
        return when {
            toolName.contains("write") || toolName.contains("edit") -> 0.7
            toolName.contains("build") || toolName.contains("test") -> 0.8
            toolName.contains("search") || toolName.contains("read") -> 0.3
            else -> 0.5
        }
    }
    
    private fun updateWeights(toolName: String, error: Double, features: FeatureVector) {
        val currentWeights = model.weights[toolName]?.let { weights -> 
            weights.map { it }.toDoubleArray().copyOf() 
        } ?: DoubleArray(5) { 0.1 }
        
        // Gradient descent update
        currentWeights[0] += learningRate * error * features.promptEmbedding.sum()
        currentWeights[1] += learningRate * error * features.projectContextScore
        currentWeights[2] += learningRate * error * features.historicalSuccessRate
        currentWeights[3] += learningRate * error * features.userFeedbackScore
        currentWeights[4] += learningRate * error * features.toolComplexity
        
        val newWeights = model.weights.toMutableMap()
        newWeights[toolName] = currentWeights
        model = model.copy(weights = newWeights, bias = model.bias + learningRate * error)
    }
    
    private fun retrainModel() {
        if (trainingData.size < 10) return
        
        // Batch training - simplified
        var totalError = 0.0
        
        trainingData.forEach { example ->
            val prediction = predictScore(example.features, example.selectedTool)
            val error = if (example.wasSuccessful) 1.0 - prediction else 0.0 - prediction
            totalError += error * error
        }
        
        val newAccuracy = 1.0 - (totalError / trainingData.size)
        model = model.copy(accuracy = newAccuracy, lastTrained = System.currentTimeMillis())
        
        println("[ML] Model retrained. Accuracy: ${model.accuracy}")
    }
    
    private fun Double.pow(exponent: Int): Double = this.toDouble().pow(exponent)
}
