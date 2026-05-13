/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization

import com.i2vision.verbalization.feedback.Feedback
import com.i2vision.verbalization.feedback.FeedbackStats
import com.i2vision.verbalization.feedback.IFeedbackStore
import com.i2vision.vslfc.*
import com.i2vision.vslfc.PutResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for ContextNeedinessCalculator.
 */
class ContextNeedinessCalculatorTest {

    private lateinit var calculator: ContextNeedinessCalculator
    private lateinit var mockSymbolRepository: MockSymbolRepository
    private lateinit var mockVerbalizationStore: MockVerbalizationStore
    private lateinit var mockFeedbackStore: MockFeedbackStore

    @BeforeEach
    fun setup() {
        mockSymbolRepository = MockSymbolRepository()
        mockVerbalizationStore = MockVerbalizationStore()
        mockFeedbackStore = MockFeedbackStore()
        calculator = ContextNeedinessCalculator(
            symbolRepository = mockSymbolRepository,
            verbalizationStore = mockVerbalizationStore,
            feedbackStore = mockFeedbackStore
        )
    }

    @Test
    fun `test empty cluster returns zero CNS`() = runBlocking {
        // Use clusterId that doesn't match domain patterns to ensure zero score
        val score = calculator.calculateWithData(
            clusterId = "utils",
            symbols = emptyList(),
            verbalizations = emptyList(),
            feedback = emptyList()
        )

        assertEquals(0.0, score.total, 0.01)
        assertEquals(VerbalizationStrategy.INCREMENTAL, score.recommendation.strategy)
    }

    @Test
    fun `test low confidence symbols increase CNS`() = runBlocking {
        val symbols = listOf(
            createSymbol("authenticate", SymbolKind.FUNCTION, "auth/AuthService.kt"),
            createSymbol("authorize", SymbolKind.FUNCTION, "auth/AuthService.kt"),
            createSymbol("validate", SymbolKind.FUNCTION, "auth/AuthService.kt")
        )

        val verbalizations = listOf(
            createVerbalization(symbols[0], "Authenticates user", 0.5), // Low confidence
            createVerbalization(symbols[1], "Authorizes user", 0.6),   // Low confidence
            createVerbalization(symbols[2], "Validates input", 0.9)    // High confidence
        )

        val score = calculator.calculateWithData(
            clusterId = "auth/service",
            symbols = symbols,
            verbalizations = verbalizations,
            feedback = emptyList()
        )

        assertTrue(score.symbolAmbiguity.total > 0)
        assertTrue(score.symbolAmbiguity.breakdown.containsKey("lowConfidenceRatio"))
    }

    @Test
    fun `test duplicate names increase CNS`() = runBlocking {
        val symbols = listOf(
            createSymbol("calculate", SymbolKind.FUNCTION, "math/Calculator.kt"),
            createSymbol("calculate", SymbolKind.FUNCTION, "utils/Calculator.kt")
        )

        val verbalizations = listOf(
            createVerbalization(symbols[0], "Calculates result", 0.8),
            createVerbalization(symbols[1], "Performs calculation", 0.8)
        )

        val score = calculator.calculateWithData(
            clusterId = "utils/mixed",
            symbols = symbols,
            verbalizations = verbalizations,
            feedback = emptyList()
        )

        assertTrue(score.symbolAmbiguity.breakdown.containsKey("duplicateNames"))
        assertTrue(score.symbolAmbiguity.breakdown["duplicateNames"]!! > 0)
    }

    @Test
    fun `test cyclomatic complexity increases CNS`() = runBlocking {
        val complexSymbol = createSymbol("processPayment", SymbolKind.FUNCTION, "payment/Processor.kt")
        // Manually set high-complexity content
        val complexSymbolWithContent = complexSymbol.copy(content = """
            fun processPayment(amount: Double, method: PaymentMethod): Result {
                if (amount <= 0) return Result.Error("Invalid amount")
                if (!isAuthorized()) return Result.Error("Not authorized")
                when (method) {
                    PaymentMethod.CARD -> {
                        if (card.isExpired()) return Result.Error("Card expired")
                        val authorized = gateway.authorize(card, amount)
                        if (!authorized) return Result.Error("Gateway rejected")
                    }
                    PaymentMethod.CRYPTO -> {
                        if (balance < amount) return Result.Error("Insufficient balance")
                        val tx = blockchain.transfer(to, amount)
                        if (tx == null) return Result.Error("Transfer failed")
                    }
                }
                return Result.Success(completed)
            }
        """.trimIndent())

        val score = calculator.calculateWithData(
            clusterId = "payment/processor",
            symbols = listOf(complexSymbolWithContent),
            verbalizations = listOf(createVerbalization(complexSymbolWithContent, "Processes payment", 0.8)),
            feedback = emptyList()
        )

        assertTrue(score.structuralComplexity.breakdown.containsKey("cyclomaticComplexity"))
        assertTrue(score.structuralComplexity.total > 0)
    }

    @Test
    fun `test external dependencies increase CNS`() = runBlocking {
        val symbol = createSymbol("fetchData", SymbolKind.FUNCTION, "data/Fetcher.kt")
        val symbolWithContent = symbol.copy(content = """
            suspend fun fetchData(url: String): Data {
                val response = httpClient.get(url)
                val data = parseJson(response.body)
                return data
            }
        """.trimIndent())

        val score = calculator.calculateWithData(
            clusterId = "data/fetcher",
            symbols = listOf(symbolWithContent),
            verbalizations = listOf(createVerbalization(symbolWithContent, "Fetches data from URL", 0.8)),
            feedback = emptyList()
        )

        assertTrue(score.structuralComplexity.breakdown.containsKey("externalDependencies"))
    }

    @Test
    fun `test domain module flag increases CNS`() = runBlocking {
        val symbols = listOf(
            createSymbol("processOrder", SymbolKind.FUNCTION, "core/order/OrderService.kt")
        )

        val score = calculator.calculateWithData(
            clusterId = "core/order",
            symbols = symbols,
            verbalizations = listOf(createVerbalization(symbols[0], "Processes order", 0.8)),
            feedback = emptyList()
        )

        assertEquals(10.0, score.architecturalSensitivity.breakdown["domainModule"]!!, 0.01)
    }

    @Test
    fun `test cross-cutting flows increase CNS`() = runBlocking {
        val symbol = createSymbol("handleRequest", SymbolKind.FUNCTION, "web/Handler.kt")
        val symbolWithContent = symbol.copy(content = """
            fun handleRequest(request: Request): Response {
                log.info("Processing request")
                if (!authenticator.isAuthenticated(request)) {
                    throw UnauthorizedException()
                }
                val result = service.process(request)
                cache.put(request.id, result)
                return Response.ok(result)
            }
        """.trimIndent())

        val score = calculator.calculateWithData(
            clusterId = "web/handler",
            symbols = listOf(symbolWithContent),
            verbalizations = listOf(createVerbalization(symbolWithContent, "Handles HTTP request with logging, auth, and caching", 0.8)),
            feedback = emptyList()
        )

        assertTrue(score.architecturalSensitivity.breakdown.containsKey("crossCuttingFlows"))
        assertTrue(score.architecturalSensitivity.breakdown["crossCuttingFlows"]!! > 0)
    }

    @Test
    fun `test feedback discrepancy increases CNS`() = runBlocking {
        val symbol = createSymbol("authenticate", SymbolKind.FUNCTION, "auth/AuthService.kt")

        val feedbackList = listOf(
            createFeedback(
                symbol = symbol,
                originalDescription = "Authenticates user", // Very generic
                correction = "Validates credentials via bcrypt, issues JWT with role claims, enforces rate limiting" // Much more detailed
            )
        )

        val score = calculator.calculateWithData(
            clusterId = "auth/service",
            symbols = listOf(symbol),
            verbalizations = listOf(createVerbalization(symbol, "Authenticates user", 0.8)),
            feedback = feedbackList
        )

        assertTrue(score.feedbackDiscrepancy.total > 0)
        assertTrue(score.feedbackDiscrepancy.breakdown.containsKey("semanticDistance"))
    }

    @Test
    fun `test strategy recommendation for low CNS`() = runBlocking {
        val symbol = createSymbol("helper", SymbolKind.FUNCTION, "utils/Helper.kt")
        val score = calculator.calculateWithData(
            clusterId = "utils/helper",
            symbols = listOf(symbol),
            verbalizations = listOf(createVerbalization(symbol, "Helper function", 0.95)),
            feedback = emptyList()
        )

        assertEquals(VerbalizationStrategy.INCREMENTAL, score.recommendation.strategy)
        assertEquals("Low need; heuristic verbalization is adequate", score.recommendation.reason)
    }

    @Test
    fun `test strategy recommendation for moderate CNS`() = runBlocking {
        val symbols = (1..10).map { idx -> createSymbol("func$idx", SymbolKind.FUNCTION, "service/Service$idx.kt") }
        val verbalizations = symbols.map { createVerbalization(it, "Description", 0.5) } // Low confidence

        val score = calculator.calculateWithData(
            clusterId = "core/service",
            symbols = symbols,
            verbalizations = verbalizations,
            feedback = emptyList()
        )

        assertEquals(VerbalizationStrategy.MULTI_PASS, score.recommendation.strategy)
        assertTrue(score.recommendation.reason.contains("Moderate need"))
    }

    @Test
    fun `test strategy recommendation for high CNS`() = runBlocking {
        // Create symbols with complex cross-cutting patterns
        val symbolsWithCrossCutting = (1..5).map { idx ->
            val sym = createSymbol("process$idx", SymbolKind.FUNCTION, "core/service/Service$idx.kt")
            sym.copy(content = """
                fun process$idx(data: Data): Result {
                    if (!authenticate()) throw UnauthorizedException()
                    if (!authorize()) throw ForbiddenException()
                    try {
                        val validated = validate(data)
                        val cached = cache.get(validated.id)
                        if (cached != null) return cached
                        val result = service.process(validated)
                        cache.put(validated.id, result)
                        return result
                    } catch (e: Exception) {
                        log.error("Processing failed", e)
                        return circuitBreaker.execute { retry { process$idx(data) } }
                    }
                }
            """.trimIndent())
        }

        val verbalizations = symbolsWithCrossCutting.map { sym ->
            createVerbalization(sym, "Process data with logging, authentication, authorization, validation, caching, error handling, retry, and circuit breaker", 0.3) // Very low confidence
        }

        val feedback = symbolsWithCrossCutting.map { sym ->
            createFeedback(
                symbol = sym,
                originalDescription = "Process data",
                correction = "Complex multi-step data processing pipeline with validation, transformation, error handling, authentication, authorization, caching and circuit breaker patterns for resilience and high availability in distributed microservices architecture"
            )
        }

        // Use clusterId "core/service" to match symbol file path derivation
        val score = calculator.calculateWithData(
            clusterId = "core/service",
            symbols = symbolsWithCrossCutting,
            verbalizations = verbalizations,
            feedback = feedback
        )

        // Debug output
        println("Symbol Ambiguity: ${score.symbolAmbiguity.total}")
        println("Structural Complexity: ${score.structuralComplexity.total}")
        println("Architectural Sensitivity: ${score.architecturalSensitivity.total}")
        println("Feedback Discrepancy: ${score.feedbackDiscrepancy.total}")
        println("Total: ${score.total}")

        // High CNS should trigger LEARNING strategy (requires total >= 50)
        assertEquals(VerbalizationStrategy.LEARNING, score.recommendation.strategy)
        assertTrue(score.total >= 50, "Expected CNS >= 50 but was ${score.total}")
    }

    @Test
    fun `test CNS total is within valid range`() = runBlocking {
        val symbols = (1..30).map { idx ->
            createSymbol("complex$idx", SymbolKind.FUNCTION, "core/domain/Service$idx.kt")
        }

        // Create content with many branching statements
        val symbolsWithContent = symbols.map { sym ->
            sym.copy(content = """
                fun ${sym.name}() {
                    if (a) { if (b) { if (c) { } } }
                    when (x) { 1 -> {} 2 -> {} 3 -> {} }
                    while (condition) { if (test) { } }
                }
            """.trimIndent())
        }

        val verbalizations = symbolsWithContent.map { createVerbalization(it, "Generic", 0.3) }
        val feedback = symbolsWithContent.take(10).map { sym ->
            createFeedback(sym, "Generic", "Very different correction here")
        }

        val score = calculator.calculateWithData(
            clusterId = "core/complex",
            symbols = symbolsWithContent,
            verbalizations = verbalizations,
            feedback = feedback
        )

        assertTrue(score.total >= 0)
        assertTrue(score.total <= 100)
    }

    @Test
    fun `test CNS calculation formula breakdown`() = runBlocking {
        val symbols = listOf(
            createSymbol("authenticate", SymbolKind.FUNCTION, "auth/AuthService.kt"),
            createSymbol("authorize", SymbolKind.FUNCTION, "auth/AuthService.kt")
        )

        val verbalizations = listOf(
            createVerbalization(symbols[0], "Authenticates", 0.5), // Low confidence
            createVerbalization(symbols[1], "Authorizes", 0.5)    // Low confidence
        )

        val score = calculator.calculateWithData(
            clusterId = "auth/service",
            symbols = symbols,
            verbalizations = verbalizations,
            feedback = emptyList()
        )

        // Verify breakdown structure
        assertEquals("SymbolAmbiguity", score.symbolAmbiguity.name)
        assertEquals("StructuralComplexity", score.structuralComplexity.name)
        assertEquals("ArchitecturalSensitivity", score.architecturalSensitivity.name)
        assertEquals("FeedbackDiscrepancy", score.feedbackDiscrepancy.name)

        // Verify each component has breakdown
        score.symbolAmbiguity.breakdown.keys.forEach { key ->
            assertTrue(score.symbolAmbiguity.breakdown[key]!! >= 0)
        }
    }

    // Helper functions

    private fun createSymbol(name: String, kind: SymbolKind, filePath: String): Symbol {
        return Symbol(
            name = name,
            kind = kind,
            filePath = filePath,
            lineNumber = 1,
            content = "fun $name() { }"
        )
    }

    private fun createVerbalization(symbol: Symbol, description: String, confidence: Double): VerbalizationResult {
        return VerbalizationResult(
            symbol = symbol,
            description = description,
            confidence = confidence,
            strategy = VerbalizationStrategy.INCREMENTAL,
            metadata = emptyMap()
        )
    }

    private fun createFeedback(
        symbol: Symbol,
        originalDescription: String,
        correction: String
    ): Feedback {
        return Feedback(
            id = "${symbol.filePath}#${symbol.name}:${System.nanoTime()}",
            symbolId = "${symbol.filePath}#${symbol.name}",
            symbolPath = symbol.filePath,
            symbolName = symbol.name,
            originalDescription = originalDescription,
            correction = correction,
            rating = 5,
            reason = null,
            timestamp = System.currentTimeMillis()
        )
    }

    // Mock classes for testing

    class MockSymbolRepository : SymbolRepository {
        private val storage = mutableMapOf<String, List<Symbol>>()

        override suspend fun getClusterSymbols(clusterId: String): List<Symbol> {
            return storage[clusterId] ?: emptyList()
        }

        fun addSymbols(clusterId: String, symbols: List<Symbol>) {
            storage[clusterId] = symbols
        }
    }

    class MockVerbalizationStore : VerbalizationStore {
        private val verbalizations = mutableMapOf<String, MutableList<VerbalizationResult>>()
        private val hashes = mutableMapOf<String, MutableMap<String, String>>()

        override suspend fun putVerbalizations(clusterId: String, results: List<VerbalizationResult>): PutResult {
            verbalizations[clusterId] = results.toMutableList()
            return PutResult.Success(results.size)
        }

        override suspend fun getVerbalizations(clusterId: String): List<VerbalizationResult>? {
            return verbalizations[clusterId]
        }

        override suspend fun getHashes(clusterId: String): Map<String, String>? {
            return hashes[clusterId]
        }

        override suspend fun putHashes(clusterId: String, hashes: Map<String, String>): PutResult {
            this.hashes[clusterId] = hashes.toMutableMap()
            return PutResult.Success(hashes.size)
        }

        override suspend fun needsReverbalization(symbol: Symbol, currentHash: String): Boolean {
            val clusterId = getClusterId(symbol.filePath)
            val storedHash = hashes[clusterId]?.get(symbol.name)
            return storedHash != currentHash
        }

        override suspend fun getVerbalization(symbol: Symbol): VerbalizationResult? {
            return verbalizations.values.flatten().find { it.symbol.name == symbol.name }
        }

        override suspend fun clearVerbalizations(clusterId: String): Int {
            val count = verbalizations[clusterId]?.size ?: 0
            verbalizations.remove(clusterId)
            return count
        }

        private fun getClusterId(filePath: String): String {
            val parts = filePath.split("/")
            return if (parts.size >= 2) "${parts[0]}/${parts[1]}" else filePath
        }
    }

    class MockFeedbackStore : IFeedbackStore {
        private val feedbackStore = mutableMapOf<String, MutableList<Feedback>>()

        override fun getClusterFeedback(clusterId: String): List<Feedback> {
            return feedbackStore[clusterId] ?: emptyList()
        }

        override fun getFeedbackForSymbol(symbol: Symbol): Feedback? {
            val clusterId = getClusterId(symbol.filePath)
            return feedbackStore[clusterId]?.find { it.symbolName == symbol.name }
        }

        override fun getAllFeedbackForSymbol(symbol: Symbol): List<Feedback> {
            return getClusterFeedback(getClusterId(symbol.filePath))
                .filter { it.symbolName == symbol.name }
        }

        override fun recordFeedback(feedback: Feedback) {
            val clusterId = getClusterId(feedback.symbolPath)
            feedbackStore.getOrPut(clusterId) { mutableListOf() }.add(feedback)
        }

        override fun recordFeedback(
            symbol: Symbol,
            originalDescription: String,
            correction: String,
            rating: Int,
            reason: String?
        ): Feedback {
            val fb = createFeedback(symbol, originalDescription, correction).copy(rating = rating, reason = reason)
            recordFeedback(fb)
            return fb
        }

        override fun getClusterFeedbackStats(clusterId: String): FeedbackStats {
            val feedback = getClusterFeedback(clusterId)
            val avgRating = if (feedback.isNotEmpty()) {
                feedback.map { it.rating }.average()
            } else 0.0

            return FeedbackStats(
                clusterId = clusterId,
                totalEntries = feedback.size,
                averageRating = avgRating,
                lastUpdated = feedback.maxOfOrNull { it.timestamp } ?: 0L
            )
        }

        override fun clearClusterFeedback(clusterId: String) {
            feedbackStore.remove(clusterId)
        }

        override fun getRecentFeedback(limit: Int): List<Feedback> {
            return feedbackStore.values.flatten()
                .sortedByDescending { it.timestamp }
                .take(limit)
        }

        private fun getClusterId(filePath: String): String {
            val parts = filePath.split("/")
            return if (parts.size >= 2) "${parts[0]}/${parts[1]}" else filePath
        }

        private fun createFeedback(
            symbol: Symbol,
            originalDescription: String,
            correction: String
        ): Feedback {
            return Feedback(
                id = "${symbol.filePath}#${symbol.name}:${System.nanoTime()}",
                symbolId = "${symbol.filePath}#${symbol.name}",
                symbolPath = symbol.filePath,
                symbolName = symbol.name,
                originalDescription = originalDescription,
                correction = correction,
                rating = 5,
                reason = null,
                timestamp = System.currentTimeMillis()
            )
        }
    }
}