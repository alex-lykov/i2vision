package com.alyk.ai.koog.core.orchestrator.mcp

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Machine Learning-based tool selection system
 * Uses neural network-like approach for intelligent tool selection
 */
class MLToolSelector(
    private val featureExtractor: FeatureExtractor,
    private val modelTrainer: ModelTrainer
) {
    
    // Trained model weights
    private var modelWeights: ModelWeights = ModelWeights.initial()
    
    // Training data storage
    private val trainingData = mutableListOf<TrainingExample>()
    
    // Feature cache for performance
    private val featureCache = ConcurrentHashMap<String, FeatureVector>()
    
    // Learning parameters
    private val learningRate = 0.01
    private val batchSize = 32
    private val maxTrainingData = 10000
    
    init {
        initializeModel()
    }
    
    /**
     * Select tools using ML-based scoring
     */
    fun selectTools(
        prompt: String,
        availableTools: List<ProjectMcpTool>,
        context: SelectionContext
    ): List<MLSelectedTool> {
        
        // Extract features for the prompt
        val promptFeatures = featureExtractor.extractPromptFeatures(prompt, context)
        
        // Score each tool using the ML model
        val scoredTools = availableTools.map { tool ->
            val toolFeatures = featureExtractor.extractToolFeatures(tool, prompt)
            val combinedFeatures = combineFeatures(promptFeatures, toolFeatures)
            
            val score = predictToolSelection(combinedFeatures)
            val confidence = calculateConfidence(score, combinedFeatures)
            
            MLSelectedTool(
                tool = tool,
                mlScore = score,
                confidence = confidence,
                features = combinedFeatures,
                reasoning = generateReasoning(score, combinedFeatures, tool)
            )
        }
        
        // Sort by score and apply adaptive threshold
        val threshold = calculateAdaptiveThreshold(scoredTools, context)
        val selectedTools = scoredTools
            .filter { it.mlScore >= threshold }
            .sortedByDescending { it.mlScore }
        
        println("[ML_SELECTOR] Selected ${selectedTools.size} tools with threshold $threshold")
        selectedTools.forEach { tool ->
            println("[ML_SELECTOR] ${tool.tool.name}: score=${tool.mlScore}, confidence=${tool.confidence}")
        }
        
        return selectedTools
    }
    
    /**
     * Predict tool selection using neural network
     */
    private fun predictToolSelection(features: FeatureVector): Double {
        // Current implementation is a lightweight logistic model.
        // (We keep the ModelWeights shape extensible for future true multi-layer training.)
        val linear = features.dotProduct(modelWeights.linearWeights) + modelWeights.linearBias
        return sigmoid(linear)
    }
    
    /**
     * Activation function (ReLU)
     */
    private fun activate(x: Double): Double {
        return max(0.0, x)
    }
    
    /**
     * Sigmoid activation for output
     */
    private fun sigmoid(x: Double): Double {
        return 1.0 / (1.0 + exp(-x))
    }
    
    /**
     * Calculate confidence in prediction
     */
    private fun calculateConfidence(score: Double, features: FeatureVector): Double {
        // Confidence based on score magnitude and feature quality
        val scoreConfidence = if (score > 0.5) score * 2 else (1.0 - score) * 2
        val featureConfidence = features.qualityScore
        
        return (scoreConfidence + featureConfidence) / 2.0
    }
    
    /**
     * Generate reasoning for tool selection
     */
    private fun generateReasoning(
        score: Double,
        features: FeatureVector,
        tool: ProjectMcpTool
    ): String {
        val reasons = mutableListOf<String>()
        
        // Category matching
        if (features.categoryMatch > 0.5) {
            reasons.add("Strong category match (${(features.categoryMatch * 100).toInt()}%)")
        }
        
        // Keyword relevance
        if (features.keywordRelevance > 0.3) {
            reasons.add("Keyword relevance (${(features.keywordRelevance * 100).toInt()}%)")
        }
        
        // Context alignment
        if (features.contextAlignment > 0.4) {
            reasons.add("Context alignment (${(features.contextAlignment * 100).toInt()}%)")
        }
        
        // Historical performance
        if (features.historicalPerformance > 0.6) {
            reasons.add("Strong historical performance")
        }
        
        // Semantic similarity
        if (features.semanticSimilarity > 0.5) {
            reasons.add("Semantic similarity (${(features.semanticSimilarity * 100).toInt()}%)")
        }
        
        return if (reasons.isNotEmpty()) {
            reasons.joinToString("; ")
        } else {
            "ML model prediction (score: ${(score * 100).toInt()}%)"
        }
    }
    
    /**
     * Calculate adaptive threshold based on tool scores
     */
    private fun calculateAdaptiveThreshold(
        scoredTools: List<MLSelectedTool>,
        context: SelectionContext
    ): Double {
        if (scoredTools.isEmpty()) return 0.5
        
        val scores = scoredTools.map { it.mlScore }
        val meanScore = scores.average()
        val scoreStdDev = calculateStandardDeviation(scores, meanScore)
        
        // Adaptive threshold based on score distribution
        val baseThreshold = max(0.1, meanScore - scoreStdDev)
        
        // Adjust based on context
        val contextAdjustment = when {
            context.complexity == TaskComplexity.SIMPLE -> -0.1
            context.complexity == TaskComplexity.COMPLEX -> 0.1
            else -> 0.0
        }
        
        // Adjust based on number of tools
        val toolCountAdjustment = when {
            scoredTools.size > 10 -> 0.05
            scoredTools.size < 3 -> -0.05
            else -> 0.0
        }
        
        return (baseThreshold + contextAdjustment + toolCountAdjustment).coerceIn(0.05, 0.8)
    }
    
    /**
     * Combine prompt and tool features
     */
    private fun combineFeatures(promptFeatures: FeatureVector, toolFeatures: FeatureVector): FeatureVector {
        return FeatureVector(
            categoryMatch = toolFeatures.categoryMatch,
            keywordRelevance = toolFeatures.keywordRelevance,
            contextAlignment = promptFeatures.contextAlignment,
            historicalPerformance = toolFeatures.historicalPerformance,
            semanticSimilarity = calculateSemanticSimilarity(promptFeatures, toolFeatures),
            promptLength = promptFeatures.promptLength,
            toolComplexity = toolFeatures.toolComplexity,
            qualityScore = (promptFeatures.qualityScore + toolFeatures.qualityScore) / 2.0
        )
    }
    
    /**
     * Calculate semantic similarity between features
     */
    private fun calculateSemanticSimilarity(features1: FeatureVector, features2: FeatureVector): Double {
        // Simple cosine similarity approximation
        val dotProduct = features1.categoryMatch * features2.categoryMatch +
                        features1.keywordRelevance * features2.keywordRelevance +
                        features1.contextAlignment * features2.contextAlignment
        
        val magnitude1 = kotlin.math.sqrt(
            features1.categoryMatch * features1.categoryMatch +
            features1.keywordRelevance * features1.keywordRelevance +
            features1.contextAlignment * features1.contextAlignment
        )
        
        val magnitude2 = kotlin.math.sqrt(
            features2.categoryMatch * features2.categoryMatch +
            features2.keywordRelevance * features2.keywordRelevance +
            features2.contextAlignment * features2.contextAlignment
        )
        
        return if (magnitude1 > 0 && magnitude2 > 0) {
            dotProduct / (magnitude1 * magnitude2)
        } else 0.0
    }
    
    /**
     * Train the model with new data
     */
    fun trainModel(feedback: List<ExecutionFeedback>) {
        // Convert feedback to training examples
        feedback.forEach { fb ->
            val example = convertFeedbackToTrainingExample(fb)
            if (example != null) {
                trainingData.add(example)
            }
        }
        
        // Limit training data size
        if (trainingData.size > maxTrainingData) {
            trainingData.removeAt(0)
        }
        
        // Train model if enough data
        if (trainingData.size >= batchSize) {
            modelWeights = modelTrainer.train(trainingData.takeLast(batchSize), modelWeights, learningRate)
            println("[ML_SELECTOR] Model trained with ${trainingData.size} examples")
        }
    }
    
    /**
     * Convert execution feedback to training example
     */
    private fun convertFeedbackToTrainingExample(feedback: ExecutionFeedback): TrainingExample? {
        // This would need to be implemented based on actual feedback structure
        // For now, return null as placeholder
        return null
    }
    
    /**
     * Initialize model with random weights
     */
    private fun initializeModel() {
        // Initialize with small random weights
        modelWeights = ModelWeights.initial()
        println("[ML_SELECTOR] ML model initialized")
    }
    
    /**
     * Calculate standard deviation
     */
    private fun calculateStandardDeviation(values: List<Double>, mean: Double): Double {
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(variance)
    }
}

/**
 * Feature extractor for ML model
 */
class FeatureExtractor {
    
    /**
     * Extract features from prompt
     */
    fun extractPromptFeatures(prompt: String, context: SelectionContext): FeatureVector {
        val words = prompt.lowercase().split(Regex("\\s+"))
        
        return FeatureVector(
            categoryMatch = 0.0, // Not applicable for prompt
            keywordRelevance = calculateKeywordRelevance(words),
            contextAlignment = calculateContextAlignment(prompt, context),
            historicalPerformance = 0.0, // Not applicable for prompt
            semanticSimilarity = 0.0, // Not applicable for prompt
            promptLength = prompt.length.toDouble() / 1000.0, // Normalized
            toolComplexity = 0.0, // Not applicable for prompt
            qualityScore = calculatePromptQuality(prompt, words)
        )
    }
    
    /**
     * Extract features from tool
     */
    fun extractToolFeatures(tool: ProjectMcpTool, prompt: String): FeatureVector {
        val promptWords = prompt.lowercase().split(Regex("\\s+"))
        
        return FeatureVector(
            categoryMatch = calculateCategoryMatch(tool, promptWords),
            keywordRelevance = calculateToolKeywordRelevance(tool, promptWords),
            contextAlignment = 0.0, // Not applicable for tool alone
            historicalPerformance = getHistoricalPerformance(tool),
            semanticSimilarity = 0.0, // Calculated later
            promptLength = 0.0, // Not applicable for tool
            toolComplexity = calculateToolComplexity(tool),
            qualityScore = calculateToolQuality(tool)
        )
    }
    
    /**
     * Calculate keyword relevance
     */
    private fun calculateKeywordRelevance(words: List<String>): Double {
        val relevantKeywords = listOf("file", "edit", "remove", "delete", "coroutine", "function", "read", "write")
        val matches = words.count { word -> relevantKeywords.any { keyword -> word.contains(keyword) } }
        return min(1.0, matches.toDouble() / relevantKeywords.size)
    }
    
    /**
     * Calculate context alignment
     */
    private fun calculateContextAlignment(prompt: String, context: SelectionContext): Double {
        var alignment = 0.0
        
        // Check if prompt mentions project context
        if (prompt.contains("project", ignoreCase = true)) alignment += 0.3
        
        // Check complexity alignment
        when (context.complexity) {
            TaskComplexity.SIMPLE -> if (prompt.length < 100) alignment += 0.4
            TaskComplexity.COMPLEX -> if (prompt.length > 200) alignment += 0.4
            TaskComplexity.MODERATE -> if (prompt.length in 100..200) alignment += 0.4
        }
        
        return min(1.0, alignment)
    }
    
    /**
     * Calculate category match
     */
    private fun calculateCategoryMatch(tool: ProjectMcpTool, promptWords: List<String>): Double {
        val categoryKeywords = getCategoryKeywords(tool.category)
        val matches = promptWords.count { word -> categoryKeywords.any { keyword -> word.contains(keyword) } }
        return min(1.0, matches.toDouble() / max(1, categoryKeywords.size))
    }
    
    /**
     * Calculate tool keyword relevance
     */
    private fun calculateToolKeywordRelevance(tool: ProjectMcpTool, promptWords: List<String>): Double {
        val toolKeywords = getToolKeywords(tool.name)
        val matches = promptWords.count { word -> toolKeywords.any { keyword -> word.contains(keyword) } }
        return min(1.0, matches.toDouble() / max(1, toolKeywords.size))
    }
    
    /**
     * Get historical performance for tool
     */
    private fun getHistoricalPerformance(tool: ProjectMcpTool): Double {
        // This would integrate with performance tracking system
        // For now, return default value
        return 0.7
    }
    
    /**
     * Calculate tool complexity
     */
    private fun calculateToolComplexity(tool: ProjectMcpTool): Double {
        return when (tool.category) {
            McpToolCategory.EXECUTION -> 0.8
            McpToolCategory.ANALYSIS -> 0.6
            McpToolCategory.FILE_ACCESS -> 0.4
            McpToolCategory.SEARCH -> 0.3
            McpToolCategory.CONTEXT -> 0.2
            McpToolCategory.NAVIGATION -> 0.1
            McpToolCategory.VERSION_CONTROL -> 0.5
        }
    }
    
    /**
     * Calculate prompt quality
     */
    private fun calculatePromptQuality(prompt: String, words: List<String>): Double {
        var quality = 0.5
        
        // Length factor
        when {
            prompt.length < 20 -> quality -= 0.2
            prompt.length > 500 -> quality -= 0.1
            prompt.length in 50..200 -> quality += 0.2
        }
        
        // Specificity factor
        if (words.any { it.contains(".kt") || it.contains(".java") || it.contains(".py") }) {
            quality += 0.2
        }
        
        // Action words
        val actionWords = listOf("remove", "add", "edit", "delete", "create", "modify")
        if (words.any { actionWords.contains(it) }) {
            quality += 0.1
        }
        
        return quality.coerceIn(0.0, 1.0)
    }
    
    /**
     * Calculate tool quality
     */
    private fun calculateToolQuality(tool: ProjectMcpTool): Double {
        // Base quality by category
        val baseQuality = when (tool.category) {
            McpToolCategory.FILE_ACCESS -> 0.8
            McpToolCategory.SEARCH -> 0.7
            McpToolCategory.ANALYSIS -> 0.6
            McpToolCategory.EXECUTION -> 0.5
            else -> 0.4
        }
        
        // Adjust based on tool name specificity
        val specificity = if (tool.name.contains("_")) 0.1 else 0.0
        
        return (baseQuality + specificity).coerceIn(0.0, 1.0)
    }
    
    /**
     * Get keywords for category
     */
    private fun getCategoryKeywords(category: McpToolCategory): List<String> {
        return when (category) {
            McpToolCategory.FILE_ACCESS -> listOf("file", "read", "write", "edit", "remove", "delete", "create")
            McpToolCategory.SEARCH -> listOf("search", "find", "look", "pattern", "regex")
            McpToolCategory.ANALYSIS -> listOf("analyze", "analysis", "check", "review", "quality")
            McpToolCategory.EXECUTION -> listOf("run", "execute", "build", "test", "compile")
            McpToolCategory.CONTEXT -> listOf("project", "context", "structure", "overview")
            McpToolCategory.NAVIGATION -> listOf("navigate", "go", "open", "show", "list")
            McpToolCategory.VERSION_CONTROL -> listOf("git", "commit", "status", "diff", "log")
        }
    }
    
    /**
     * Get keywords for specific tool
     */
    private fun getToolKeywords(toolName: String): List<String> {
        return when (toolName) {
            "read_file" -> listOf("read", "open", "view", "file")
            "edit_file" -> listOf("edit", "modify", "change", "remove", "delete", "update")
            "write_file" -> listOf("write", "create", "save", "file")
            "code_search" -> listOf("search", "find", "pattern", "code")
            "file_analyzer" -> listOf("analyze", "analysis", "quality", "check")
            "build_runner" -> listOf("build", "compile", "run", "execute")
            "test_executor" -> listOf("test", "testing", "spec")
            "git_operations" -> listOf("git", "commit", "status", "diff")
            else -> toolName.split("_")
        }
    }
}

/**
 * Model trainer for neural network
 */
class ModelTrainer {
    
    /**
     * Train neural network using backpropagation
     */
    fun train(
        trainingData: List<TrainingExample>,
        currentWeights: ModelWeights,
        learningRate: Double
    ): ModelWeights {
        // Placeholder trainer: keep compilation + wiring intact.
        // A real trainer can be added later (SGD/logistic regression or a small NN).
        return currentWeights
    }
}

/**
 * Feature vector for ML model
 */
@Serializable
data class FeatureVector(
    val categoryMatch: Double,
    val keywordRelevance: Double,
    val contextAlignment: Double,
    val historicalPerformance: Double,
    val semanticSimilarity: Double,
    val promptLength: Double,
    val toolComplexity: Double,
    val qualityScore: Double
) {
    
    /**
     * Calculate dot product with weight vector
     */
    fun dotProduct(weights: List<Double>): Double {
        val features = listOf(
            categoryMatch, keywordRelevance, contextAlignment,
            historicalPerformance, semanticSimilarity, promptLength,
            toolComplexity, qualityScore
        )
        
        return features.zip(weights).sumOf { (f, w) -> f * w }
    }
}

/**
 * ML-selected tool with scores and reasoning
 */
data class MLSelectedTool(
    val tool: ProjectMcpTool,
    val mlScore: Double,
    val confidence: Double,
    val features: FeatureVector,
    val reasoning: String
)

/**
 * Model weights for neural network
 */
@Serializable
data class ModelWeights(
    val linearWeights: List<Double>,
    val linearBias: Double
) {
    companion object {
        fun initial(): ModelWeights {
            return ModelWeights(
                linearWeights = List(8) { (Math.random() - 0.5) * 0.1 },
                linearBias = (Math.random() - 0.5) * 0.1
            )
        }
    }
}

/**
 * Training example for ML model
 */
@Serializable
data class TrainingExample(
    val features: FeatureVector,
    val expectedOutput: Double,
    val toolName: String,
    val prompt: String
)

/**
 * Selection context for ML model
 */
data class SelectionContext(
    val complexity: TaskComplexity,
    val projectPath: String?,
    val sessionId: String,
    val previousSelections: List<String> = emptyList()
)
