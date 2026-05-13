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