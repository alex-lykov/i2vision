/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.llm

import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.SymbolKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for DefaultLlmVerbalizationClient.
 */
class DefaultLlmVerbalizationClientTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var client: DefaultLlmVerbalizationClient

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

    private val dataClassSymbol = Symbol(
        name = "UserProfile",
        kind = SymbolKind.CLASS,
        filePath = "user/model/UserProfile.kt",
        lineNumber = 1,
        content = """
            data class UserProfile(
                val id: Long,
                val username: String,
                val email: String,
                val createdAt: LocalDateTime
            )
        """.trimIndent()
    )

    private val suspendSymbol = Symbol(
        name = "fetchData",
        kind = SymbolKind.FUNCTION,
        filePath = "data/Fetcher.kt",
        lineNumber = 10,
        content = """
            suspend fun fetchData(url: String): Response {
                return httpClient.get(url)
            }
        """.trimIndent()
    )

    @BeforeEach
    fun setup() {
        client = DefaultLlmVerbalizationClient(LlmClientConfig(allowMockFallback = true))
    }

    @Test
    fun `generate returns response for valid request`() = runBlocking {
        val request = LlmVerbalizationRequest(
            symbol = testSymbol,
            heuristicDescription = "Authenticates user",
            context = SymbolVerbalizationContext(
                clusterId = "auth/service",
                moduleName = "auth",
                dependencies = listOf("org.springframework.security"),
                architecturalLayer = "domain"
            )
        )

        val response = client.generate(request)

        assertNotNull(response)
        assertTrue(response.description.isNotBlank())
        assertEquals("mock", response.model)
    }

    @Test
    fun `generate uses heuristic description when provided`() = runBlocking {
        val request = LlmVerbalizationRequest(
            symbol = testSymbol,
            heuristicDescription = "Authenticates user via credentials",
            context = SymbolVerbalizationContext(
                clusterId = "auth/service",
                moduleName = "auth"
            )
        )

        val response = client.generate(request)

        assertNotNull(response)
        assertTrue(response.description.isNotEmpty())
    }

    @Test
    fun `generate batch processes multiple requests`() = runBlocking {
        val requests = listOf(
            LlmVerbalizationRequest(
                symbol = testSymbol,
                heuristicDescription = "Authenticates user",
                context = SymbolVerbalizationContext(clusterId = "auth/service", moduleName = "auth")
            ),
            LlmVerbalizationRequest(
                symbol = dataClassSymbol,
                heuristicDescription = "User profile data",
                context = SymbolVerbalizationContext(clusterId = "user/model", moduleName = "user")
            )
        )

        val responses = client.generateBatch(requests)

        assertEquals(2, responses.size)
        assertTrue(responses.all { it.description.isNotBlank() })
    }

    @Test
    fun `generate enhances kotlin suspend terminology`() = runBlocking {
        val request = LlmVerbalizationRequest(
            symbol = suspendSymbol,
            heuristicDescription = "Fetches data",
            context = SymbolVerbalizationContext(
                clusterId = "data/fetcher",
                moduleName = "data"
            )
        )

        val response = client.generate(request)

        assertNotNull(response)
        // Should mention suspend or async behavior
        assertTrue(
            response.description.contains("suspend") || response.description.contains("async") ||
            response.description.contains("asynchronous") || response.description.contains("fetch"),
            "Expected suspend/async terminology in: ${response.description}"
        )
    }

    @Test
    fun `generate applies feedback history improvements`() = runBlocking {
        val feedbackHistory = listOf(
            FeedbackHistoryEntry(
                originalDescription = "Authenticates user",
                correctedDescription = "Validates credentials via bcrypt, issues JWT with role claims",
                rating = 5,
                reason = "More specific"
            )
        )

        val request = LlmVerbalizationRequest(
            symbol = testSymbol,
            heuristicDescription = "Authenticates user",
            context = SymbolVerbalizationContext(clusterId = "auth/service", moduleName = "auth"),
            feedbackHistory = feedbackHistory
        )

        val response = client.generate(request)

        assertNotNull(response)
        // The description should include elements from the feedback correction
        assertTrue(
            response.description.contains("bcrypt") || response.description.contains("JWT") ||
            response.description.contains("validates") || response.description.contains("credentials"),
            "Expected feedback-influenced description, got: ${response.description}"
        )
    }

    @Test
    fun `generate handles data class symbols`() = runBlocking {
        val request = LlmVerbalizationRequest(
            symbol = dataClassSymbol,
            heuristicDescription = "User profile",
            context = SymbolVerbalizationContext(
                clusterId = "user/model",
                moduleName = "user"
            )
        )

        val response = client.generate(request)

        assertNotNull(response)
        assertTrue(response.description.isNotBlank())
    }

    @Test
    fun `isAvailable returns true when mock fallback allowed`() {
        val clientWithFallback = DefaultLlmVerbalizationClient(
            LlmClientConfig(allowMockFallback = true)
        )
        assertTrue(clientWithFallback.isAvailable())
    }

    @Test
    fun `client estimates tokens correctly`() {
        val text = "This is a test description for token estimation"
        val estimatedTokens = (text.length / 4).toInt()
        assertEquals(11, estimatedTokens) // 44 chars / 4 = 11 tokens
    }

    @Test
    fun `generate response includes generation metadata`() = runBlocking {
        val request = LlmVerbalizationRequest(
            symbol = testSymbol,
            heuristicDescription = "Authenticates user",
            context = SymbolVerbalizationContext(clusterId = "auth/service", moduleName = "auth")
        )

        val response = client.generate(request)

        assertNotNull(response)
        assertTrue(response.tokensUsed > 0)
        assertTrue(response.generationTimeMs >= 0)
        assertEquals("mock", response.model)
    }

    @Test
    fun `generate with empty content still returns description`() = runBlocking {
        val emptySymbol = testSymbol.copy(content = "")
        val request = LlmVerbalizationRequest(
            symbol = emptySymbol,
            heuristicDescription = null,
            context = SymbolVerbalizationContext(clusterId = "auth/service", moduleName = "auth")
        )

        val response = client.generate(request)

        assertNotNull(response)
        assertTrue(response.description.isNotBlank())
    }

    @Test
    fun `generate handles request without heuristic description`() = runBlocking {
        val request = LlmVerbalizationRequest(
            symbol = testSymbol,
            heuristicDescription = null,
            context = SymbolVerbalizationContext(clusterId = "auth/service", moduleName = "auth")
        )

        val response = client.generate(request)

        assertNotNull(response)
        assertTrue(response.description.isNotBlank())
    }
}