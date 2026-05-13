/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.strategy

import com.i2vision.intent.DiscoveryIntent
import com.i2vision.intent.IntentGoal
import com.i2vision.verbalization.HashManager
import com.i2vision.verbalization.PatternMatcher
import com.i2vision.verbalization.feedback.FeedbackStore
import com.i2vision.verbalization.llm.DefaultLlmVerbalizationClient
import com.i2vision.verbalization.llm.LlmClientConfig
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.SymbolKind
import com.i2vision.vslfc.VerbalizationPattern
import com.i2vision.vslfc.VerbalizationStrategy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/**
 * Unit tests for LearningVerbalizationStrategy.
 */
class LearningVerbalizationStrategyTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var patternMatcher: PatternMatcher
    private lateinit var hashManager: HashManager
    private lateinit var feedbackStore: FeedbackStore
    private lateinit var llmClient: DefaultLlmVerbalizationClient
    private lateinit var strategy: LearningVerbalizationStrategy

    private val testSymbol = Symbol(
        name = "authenticateUser",
        kind = SymbolKind.FUNCTION,
        filePath = "auth/service/AuthService.kt",
        lineNumber = 42,
        content = """
            suspend fun authenticateUser(username: String, password: String): User {
                val user = repository.findByUsername(username)
                if (user == null) throw UserNotFoundException()
                if (!passwordEncoder.matches(password, user.passwordHash)) {
                    throw InvalidCredentialsException()
                }
                return user
            }
        """.trimIndent()
    )

    private val testSymbol2 = Symbol(
        name = "processPayment",
        kind = SymbolKind.FUNCTION,
        filePath = "payment/PaymentService.kt",
        lineNumber = 10,
        content = """
            fun processPayment(amount: Double, method: PaymentMethod): PaymentResult {
                val fee = calculateFee(amount)
                return gateway.charge(method, amount + fee)
            }
        """.trimIndent()
    )

    @BeforeEach
    fun setup() {
        patternMatcher = PatternMatcher()
        hashManager = HashManager()
        feedbackStore = FeedbackStore(tempDir.toString())
        llmClient = DefaultLlmVerbalizationClient(LlmClientConfig(allowMockFallback = true))
        strategy = LearningVerbalizationStrategy(
            patternMatcher = patternMatcher,
            llmClient = llmClient,
            feedbackStore = feedbackStore,
            hashManager = hashManager
        )
    }

    @Test
    fun verbalizeReturnsResultsForAllSymbols() = runBlocking {
        val symbols = listOf(testSymbol, testSymbol2)
        val intent = DiscoveryIntent(goal = IntentGoal.QUICK_OVERVIEW)

        val results = strategy.verbalize(symbols, intent)

        assertEquals(2, results.size)
        assertTrue(results.all { it.strategy == VerbalizationStrategy.LEARNING })
    }

    @Test
    fun verbalizeUsesFeedbackWhenAvailable() = runBlocking {
        // Record high-rated feedback for symbol
        feedbackStore.recordFeedback(
            symbol = testSymbol,
            originalDescription = "Authenticates user",
            correction = "Validates credentials via bcrypt and issues JWT",
            rating = 5,
            reason = null
        )

        val symbols = listOf(testSymbol)
        val intent = DiscoveryIntent(goal = IntentGoal.QUICK_OVERVIEW)

        val results = strategy.verbalize(symbols, intent)

        assertEquals(1, results.size)
        assertEquals("Validates credentials via bcrypt and issues JWT", results[0].description)
        assertEquals(0.95, results[0].confidence)
        assertEquals("user_feedback", results[0].metadata["source"])
    }

    @Test
    fun verbalizeUsesLlmWhenNoFeedback() = runBlocking {
        val symbols = listOf(testSymbol)
        val intent = DiscoveryIntent(goal = IntentGoal.QUICK_OVERVIEW)

        val results = strategy.verbalize(symbols, intent)

        assertEquals(1, results.size)
        assertEquals(VerbalizationStrategy.LEARNING, results[0].strategy)
        assertTrue(results[0].description.isNotBlank())
        assertEquals("true", results[0].metadata["llm_used"])
        assertEquals("mock", results[0].metadata["llm_model"])
    }

    @Test
    fun verbalizeDoesNotUseLowRatedFeedback() = runBlocking {
        // Record low-rated feedback
        feedbackStore.recordFeedback(
            symbol = testSymbol,
            originalDescription = "Authenticates user",
            correction = "Bad correction",
            rating = 2,
            reason = null
        )

        val symbols = listOf(testSymbol)
        val intent = DiscoveryIntent(goal = IntentGoal.QUICK_OVERVIEW)

        val results = strategy.verbalize(symbols, intent)

        assertEquals(1, results.size)
        // Should use LLM, not the low-rated feedback
        assertEquals("true", results[0].metadata["llm_used"])
    }

    @Test
    fun verbalizeIncludesPatternHintInMetadata() = runBlocking {
        // Register a pattern
        patternMatcher.registerPattern(
            VerbalizationPattern(
                codePattern = """fun\s+authenticate""",
                description = "Authentication function",
                confidence = 0.8
            )
        )

        val symbols = listOf(testSymbol)
        val intent = DiscoveryIntent(goal = IntentGoal.QUICK_OVERVIEW)

        val results = strategy.verbalize(symbols, intent)

        assertEquals(1, results.size)
        assertEquals("true", results[0].metadata["pattern_hint_used"])
    }

    @Test
    fun verbalizeIncludesFeedbackHistoryInMetadata() = runBlocking {
        // Record some feedback
        feedbackStore.recordFeedback(testSymbol, "Desc 1", "Correction 1", 4, null)
        feedbackStore.recordFeedback(testSymbol, "Desc 2", "Correction 2", 5, null)

        val symbols = listOf(testSymbol)
        val intent = DiscoveryIntent(goal = IntentGoal.QUICK_OVERVIEW)

        val results = strategy.verbalize(symbols, intent)

        assertEquals(1, results.size)
        assertEquals("2", results[0].metadata["feedback_history_size"])
    }

    @Test
    fun verbalizeReturnsEmptyListForEmptySymbols() = runBlocking {
        val results = strategy.verbalize(emptyList(), DiscoveryIntent(goal = IntentGoal.FULL_DISCOVERY))
        assertTrue(results.isEmpty())
    }

    @Test
    fun getStrategyTypeReturnsLearning() {
        assertEquals(VerbalizationStrategy.LEARNING, strategy.getStrategyType())
    }

    @Test
    fun verbalizeIncludesArchitecturalLayerInContext() = runBlocking {
        // Controller layer
        val controllerSymbol = testSymbol.copy(
            filePath = "web/controller/UserController.kt",
            content = """
                @RestController
                class UserController {
                    @PostMapping("/login")
                    fun login(request: LoginRequest): Response
                }
            """.trimIndent()
        )

        val symbols = listOf(controllerSymbol)
        val intent = DiscoveryIntent(goal = IntentGoal.QUICK_OVERVIEW)

        val results = strategy.verbalize(symbols, intent)

        assertEquals(1, results.size)
        assertTrue(results[0].description.isNotBlank())
    }

    @Test
    fun verbalizeExtractsDependenciesFromContent() = runBlocking {
        val symbolWithImports = testSymbol.copy(
            content = """
                import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
                import com.example.repository.UserRepository

                suspend fun authenticateUser(username: String, password: String): User {
                    return User()
                }
            """.trimIndent()
        )

        val symbols = listOf(symbolWithImports)
        val intent = DiscoveryIntent(goal = IntentGoal.QUICK_OVERVIEW)

        val results = strategy.verbalize(symbols, intent)

        assertEquals(1, results.size)
        assertTrue(results[0].description.isNotBlank())
    }

    @Test
    fun verbalizeWithCustomClusterId() {
        val customStrategy = LearningVerbalizationStrategy(
            patternMatcher = patternMatcher,
            llmClient = llmClient,
            feedbackStore = feedbackStore,
            hashManager = hashManager,
            clusterId = "custom/cluster"
        )

        assertEquals(VerbalizationStrategy.LEARNING, customStrategy.getStrategyType())
    }
}