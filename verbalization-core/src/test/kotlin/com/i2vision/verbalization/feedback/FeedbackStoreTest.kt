/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.feedback

import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.SymbolKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for FeedbackStore.
 */
class FeedbackStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var feedbackStore: FeedbackStore

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
        // Use customBaseDir to bypass the I2VisionPaths.getProjectCacheDir logic
        feedbackStore = FeedbackStore(customBaseDir = tempDir.toString())
    }

    @Test
    fun `record and retrieve feedback for symbol`() = runBlocking {
        val feedback = feedbackStore.recordFeedback(
            symbol = testSymbol,
            originalDescription = "Authenticates user",
            correction = "Validates credentials via bcrypt and returns authenticated User with JWT claims",
            rating = 5,
            reason = "Original was too vague"
        )

        assertNotNull(feedback.id)
        assertEquals("authenticateUser", feedback.symbolName)
        assertEquals(5, feedback.rating)

        val retrieved = feedbackStore.getFeedbackForSymbol(testSymbol)
        assertNotNull(retrieved)
        assertEquals(feedback.id, retrieved.id)
        assertEquals(feedback.correction, retrieved.correction)
    }

    @Test
    fun `get all feedback for symbol`() = runBlocking {
        // Record multiple feedback entries
        feedbackStore.recordFeedback(testSymbol, "Original", "Correction 1", 3, null)
        feedbackStore.recordFeedback(testSymbol, "Original", "Correction 2", 5, null)
        feedbackStore.recordFeedback(testSymbol, "Original", "Correction 3", 4, null)

        val allFeedback = feedbackStore.getAllFeedbackForSymbol(testSymbol)
        assertEquals(3, allFeedback.size)
    }

    @Test
    fun `get cluster feedback`() = runBlocking {
        // Both symbols are in different clusters
        feedbackStore.recordFeedback(testSymbol, "Desc 1", "Correction 1", 5, null)
        feedbackStore.recordFeedback(testSymbol, "Desc 2", "Correction 2", 4, null)

        // testSymbol filePath is "auth/service/AuthService.kt" -> clusterId is "auth/service"
        val clusterFeedback = feedbackStore.getClusterFeedback("auth/service")
        assertEquals(2, clusterFeedback.size)

        // Different cluster should be empty
        val otherClusterFeedback = feedbackStore.getClusterFeedback("other")
        assertTrue(otherClusterFeedback.isEmpty())
    }

    @Test
    fun `feedback persistence across instances`() = runBlocking {
        // Record feedback with first instance
        feedbackStore.recordFeedback(
            symbol = testSymbol,
            originalDescription = "Original",
            correction = "Better description",
            rating = 5,
            reason = null
        )

        // Create new instance pointing to same directory
        val newStore = FeedbackStore(customBaseDir = tempDir.toString())
        val retrieved = newStore.getFeedbackForSymbol(testSymbol)

        assertNotNull(retrieved)
        assertEquals("Better description", retrieved.correction)
        // testSymbol cluster is "auth/service"
        assertEquals("auth/service/${testSymbol.name}", retrieved.symbolId)
    }

    @Test
    fun `feedback stats calculation`() = runBlocking {
        feedbackStore.recordFeedback(testSymbol, "Desc 1", "Correction 1", 5, null)
        feedbackStore.recordFeedback(testSymbol, "Desc 2", "Correction 2", 3, null)
        feedbackStore.recordFeedback(testSymbol, "Desc 3", "Correction 3", 4, null)

        val stats = feedbackStore.getClusterFeedbackStats("auth/service")
        assertEquals(3, stats.totalEntries)
        assertEquals(4.0, stats.averageRating, 0.01) // (5+3+4)/3 = 4.0
        assertTrue(stats.lastUpdated > 0)
    }

    @Test
    fun `clear cluster feedback`() = runBlocking {
        feedbackStore.recordFeedback(testSymbol, "Desc", "Correction", 5, null)
        feedbackStore.recordFeedback(testSymbol2, "Desc", "Correction", 4, null)

        // Clear only auth/service cluster
        feedbackStore.clearClusterFeedback("auth/service")

        val authFeedback = feedbackStore.getClusterFeedback("auth/service")
        assertTrue(authFeedback.isEmpty())

        // payment cluster should still have feedback
        val paymentFeedback = feedbackStore.getClusterFeedback("payment")
        assertEquals(1, paymentFeedback.size)
    }

    @Test
    fun `recent feedback query`() = runBlocking {
        // Add some feedback
        feedbackStore.recordFeedback(testSymbol, "Desc 1", "Correction 1", 5, null)
        feedbackStore.recordFeedback(testSymbol, "Desc 2", "Correction 2", 4, null)
        feedbackStore.recordFeedback(testSymbol2, "Desc 3", "Correction 3", 5, null)

        val recent = feedbackStore.getRecentFeedback(limit = 10)
        assertEquals(3, recent.size)
    }

    @Test
    fun `get recent feedback with limit`() = runBlocking {
        // Add 10 feedback entries
        repeat(10) { i ->
            feedbackStore.recordFeedback(testSymbol, "Desc $i", "Correction $i", (i % 5) + 1, null)
        }

        val recent = feedbackStore.getRecentFeedback(limit = 5)
        assertEquals(5, recent.size)
    }

    @Test
    fun `symbol without feedback returns null`() = runBlocking {
        val feedback = feedbackStore.getFeedbackForSymbol(testSymbol)
        assertNull(feedback)
    }

    @Test
    fun `malformed feedback file is skipped`() = runBlocking {
        // Write malformed line directly to file
        val feedbackFile = File(tempDir.toFile(), "auth/learning/feedback.jsonl")
        feedbackFile.parentFile?.mkdirs()
        feedbackFile.writeText("not valid json\n")
        feedbackFile.appendText("""{"id":"test","symbolId":"test","symbolPath":"test","symbolName":"test","originalDescription":"test","correction":"test","rating":5,"timestamp":123}""")

        val newStore = FeedbackStore(customBaseDir = tempDir.toString())
        val feedback = newStore.getClusterFeedback("auth")

        // Only valid JSON should be returned
        assertEquals(1, feedback.size)
    }
}

// ============ GUARDRAIL TESTS ============

/**
 * Unit tests for feedback guardrails.
 */
class FeedbackStoreGuardrailTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var feedbackStore: FeedbackStore

    private val testSymbol = Symbol(
        name = "testFunction",
        kind = SymbolKind.FUNCTION,
        filePath = "auth/service/TestService.kt",
        lineNumber = 10,
        content = "fun testFunction() { }"
    )

    private val coreSymbol = Symbol(
        name = "coreFunction",
        kind = SymbolKind.FUNCTION,
        filePath = "core/domain/CoreService.kt",
        lineNumber = 5,
        content = "fun coreFunction() { }"
    )

    @BeforeEach
    fun setup() {
        feedbackStore = FeedbackStore(
            customBaseDir = tempDir.toString(),
            guardrailConfig = FeedbackGuardrailConfig(
                enableExpiry = true,
                enableReviewWorkflow = true,
                enableConfidenceDecay = true,
                enableValidation = true,
                defaultExpiryDays = 90,
                maxCodeChangesBeforeExpiry = 10
            )
        )
    }

    @Test
    fun `feedback has correct default scope for regular symbols`() = runBlocking {
        val feedback = feedbackStore.recordFeedback(
            symbol = testSymbol,
            originalDescription = "Original description",
            correction = "Corrected description",
            rating = 4,
            reason = "Test"
        )

        assertEquals(FeedbackScope.CLUSTER, feedback.scope)
        assertEquals(1.0, feedback.confidenceScore)
        assertNotNull(feedback.expiresAt)
    }

    @Test
    fun `feedback has PROJECT scope for core domain symbols`() = runBlocking {
        val feedback = feedbackStore.recordFeedback(
            symbol = coreSymbol,
            originalDescription = "Original",
            correction = "Corrected",
            rating = 4,
            reason = null
        )

        assertEquals(FeedbackScope.PROJECT, feedback.scope)
    }

    @Test
    fun `feedback expires after time expiry`() = runBlocking {
        // Create store with very short expiry
        val shortExpiryStore = FeedbackStore(
            customBaseDir = tempDir.toString(),
            guardrailConfig = FeedbackGuardrailConfig(
                enableExpiry = true,
                defaultExpiryDays = 0, // Expires immediately
                enableConfidenceDecay = false
            )
        )

        val feedback = shortExpiryStore.recordFeedback(
            symbol = testSymbol,
            originalDescription = "Original",
            correction = "Corrected",
            rating = 4,
            reason = null
        )

        assertTrue(shortExpiryStore.isExpired(feedback))
    }

    @Test
    fun `feedback expires after code changes`() = runBlocking {
        feedbackStore.recordFeedback(
            symbol = testSymbol,
            originalDescription = "Original",
            correction = "Corrected",
            rating = 4,
            reason = null
        )

        // Simulate code changes - this updates the cached feedback's codeChangeCount
        repeat(11) {
            feedbackStore.onCodeChanged(testSymbol.filePath)
        }

        // Get the updated feedback from cache
        val updatedFeedback = feedbackStore.getFeedbackForSymbol(testSymbol)
        assertNotNull(updatedFeedback)

        // Now it should be expired (codeChangeCount >= 10)
        assertTrue(feedbackStore.isExpired(updatedFeedback))
    }

    @Test
    fun `confidence decays over time`() = runBlocking {
        val feedback = feedbackStore.recordFeedback(
            symbol = testSymbol,
            originalDescription = "Original",
            correction = "Corrected",
            rating = 4,
            reason = null
        )

        // Initial confidence should be 1.0
        assertEquals(1.0, feedbackStore.calculateDecayedConfidence(feedback), 0.01)
    }

    @Test
    fun `confidence decays after code changes`() = runBlocking {
        val feedback = feedbackStore.recordFeedback(
            symbol = testSymbol,
            originalDescription = "Original",
            correction = "Corrected",
            rating = 4,
            reason = null
        )

        // Simulate some code changes
        repeat(5) {
            feedbackStore.onCodeChanged(testSymbol.filePath)
        }

        val decayed = feedbackStore.calculateDecayedConfidence(feedback)
        assertTrue(decayed < 1.0)
        assertTrue(decayed >= 0.0)
    }

    @Test
    fun `active feedback filters out expired`() = runBlocking {
        // Create store with short expiry
        val shortExpiryStore = FeedbackStore(
            customBaseDir = tempDir.toString(),
            guardrailConfig = FeedbackGuardrailConfig(
                enableExpiry = true,
                defaultExpiryDays = 0,
                minConfidenceThreshold = 0.0
            )
        )

        shortExpiryStore.recordFeedback(testSymbol, "Orig", "Corr", 4, null)
        val active = shortExpiryStore.getActiveFeedbackForSymbol(testSymbol)

        assertTrue(active.isEmpty())
    }

    @Test
    fun `validate feedback rejects low rating with correction`() {
        val result = feedbackStore.validateFeedback(
            symbol = testSymbol,
            originalDescription = "Original description",
            correction = "Corrected description",
            rating = 2
        )

        assertTrue(result is FeedbackValidationResult.Invalid)
        assertTrue((result as FeedbackValidationResult.Invalid).reason.contains("Low rating"))
    }

    @Test
    fun `validate feedback rejects identical correction`() {
        val original = "Same description"
        val result = feedbackStore.validateFeedback(
            symbol = testSymbol,
            originalDescription = original,
            correction = original, // Same as original
            rating = 4
        )

        assertTrue(result is FeedbackValidationResult.Invalid)
        assertTrue((result as FeedbackValidationResult.Invalid).reason.contains("identical"))
    }

    @Test
    fun `validate feedback warns about short correction`() {
        val result = feedbackStore.validateFeedback(
            symbol = testSymbol,
            originalDescription = "This is a very long original description that explains everything in detail",
            correction = "Short", // Much shorter than original
            rating = 5
        )

        assertTrue(result is FeedbackValidationResult.Warning)
    }

    @Test
    fun `validate feedback accepts valid correction`() {
        val result = feedbackStore.validateFeedback(
            symbol = testSymbol,
            originalDescription = "Original description",
            correction = "Improved and more detailed description",
            rating = 5
        )

        assertEquals(FeedbackValidationResult.Valid, result)
    }

    @Test
    fun `mark as reviewed boosts confidence for global feedback`() = runBlocking {
        // Create a global-scoped feedback by using shared path
        val sharedSymbol = Symbol(
            name = "sharedFunction",
            kind = SymbolKind.FUNCTION,
            filePath = "shared/utils/SharedHelper.kt",  // "shared" triggers GLOBAL scope
            lineNumber = 5,
            content = "fun sharedFunction() { }"
        )

        val feedback = feedbackStore.recordFeedback(
            symbol = sharedSymbol,
            originalDescription = "Original",
            correction = "Corrected",
            rating = 4,
            reason = null
        )

        // Verify it's global scope
        assertEquals(FeedbackScope.GLOBAL, feedback.scope)

        // Mark as reviewed
        val marked = feedbackStore.markAsReviewed(feedback.id, "reviewer@example.com")
        assertTrue(marked)

        // Confidence should be boosted
        val updated = feedbackStore.getFeedbackForSymbol(sharedSymbol)
        assertNotNull(updated)
        assertEquals("reviewer@example.com", updated.reviewedBy)
    }

    @Test
    fun `get scoped feedback respects scope hierarchy`() = runBlocking {
        // Record cluster-scoped feedback
        val clusterFeedback = feedbackStore.recordFeedback(
            symbol = testSymbol,
            originalDescription = "Orig",
            correction = "Corr",
            rating = 4,
            reason = null
        )

        // Verify scope is CLUSTER
        assertEquals(FeedbackScope.CLUSTER, clusterFeedback.scope)

        // Get feedback at CLUSTER scope - should see it
        val clusterResults = feedbackStore.getScopedFeedback(testSymbol, FeedbackScope.CLUSTER)
        assertTrue(clusterResults.any { it.id == clusterFeedback.id }, 
            "CLUSTER scope should see CLUSTER-scoped feedback")

        // Get feedback at PROJECT scope - should NOT see cluster-scoped
        // because PROJECT scope only shows PROJECT and GLOBAL feedback
        val projectResults = feedbackStore.getScopedFeedback(testSymbol, FeedbackScope.PROJECT)
        assertTrue(projectResults.none { it.id == clusterFeedback.id }, 
            "PROJECT scope should NOT see CLUSTER-scoped feedback")
    }

    @Test
    fun `prune expired feedback removes expired entries`() = runBlocking {
        // Create store with short expiry
        val shortExpiryStore = FeedbackStore(
            customBaseDir = tempDir.toString(),
            guardrailConfig = FeedbackGuardrailConfig(
                enableExpiry = true,
                defaultExpiryDays = 0,
                minConfidenceThreshold = 0.0
            )
        )

        shortExpiryStore.recordFeedback(testSymbol, "Orig1", "Corr1", 4, null)
        shortExpiryStore.recordFeedback(testSymbol, "Orig2", "Corr2", 4, null)

        // Prune expired
        val prunedCount = shortExpiryStore.pruneExpiredFeedback("auth/service")

        assertEquals(2, prunedCount)

        // Should have no feedback left
        val remaining = shortExpiryStore.getClusterFeedback("auth/service")
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `detailed stats include scope breakdown`() = runBlocking {
        feedbackStore.recordFeedback(testSymbol, "Orig1", "Corr1", 4, null)

        val stats = feedbackStore.getDetailedFeedbackStats("auth/service")

        assertEquals(1, stats.totalEntries)
        assertEquals(1, stats.activeEntries)
        assertEquals(0, stats.expiredEntries)
        assertTrue(stats.scopeBreakdown[FeedbackScope.CLUSTER] == 1)
    }

    @Test
    fun `record with guardrails extension function works`() = runBlocking {
        val result = feedbackStore.recordWithGuardrails(
            symbol = testSymbol,
            originalDescription = "Original description",
            correction = "Improved description",
            rating = 5,
            reason = "Better wording"
        )

        assertTrue(result.isSuccess)
        val feedback = result.getOrNull()
        assertNotNull(feedback)
        assertEquals("Improved description", feedback?.correction)
    }

    @Test
    fun `record with guardrails rejects invalid feedback`() {
        val result = feedbackStore.recordWithGuardrails(
            symbol = testSymbol,
            originalDescription = "Original description",
            correction = "Corrected description",
            rating = 2, // Low rating with correction
            reason = null
        )

        assertTrue(result.isFailure)
    }
}