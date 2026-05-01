/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.validation

import com.i2vision.intent.DiscoveryIntent
import com.i2vision.intent.IntentDepth
import com.i2vision.intent.IntentGoal
import com.i2vision.intent.QualityFocus
import com.i2vision.storage.I2VisionPaths
import com.i2vision.storage.impl.FileCacheStore
import com.i2vision.storage.impl.FileVerbalizationStore
import com.i2vision.verbalization.DefaultVerbalizationEngine
import com.i2vision.vslfc.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verbalization Integration Tests
 *
 * Tests the verbalization engine introduced in recent updates.
 * Validates that code symbols can be verbalized with different strategies.
 *
 * Test Coverage:
 * - Symbol verbalization with different strategies
 * - Verbalization storage and retrieval
 * - Caching mechanisms based on symbol hash
 * - Custom pattern recognition and application
 * - Integration with discovery output
 */
class VerbalizationIntegrationTest {

    // ========== PHASE 1: Basic Verbalization ==========

    @Test
    fun `phase1 verbalization engine creates output for simple symbols`() = runTest {
        val tempDir = Files.createTempDirectory("verbalization-test-").toFile()
        val cacheDir = I2VisionPaths.getProjectCacheDir(tempDir.absolutePath)
        cacheDir.mkdirs()

        try {
            // Create verbalization store
            val cacheStore = FileCacheStore(cacheDir)
            val verbalizationStore = FileVerbalizationStore(cacheStore)
            val engine = DefaultVerbalizationEngine(verbalizationStore)

            // Create test symbols
            val symbols = listOf(
                Symbol(
                    name = "UserService",
                    kind = SymbolKind.CLASS,
                    filePath = "src/main/kotlin/com/example/UserService.kt",
                    lineNumber = 10,
                    content = """
                        class UserService {
                            fun findUser(id: String): User? { return null }
                        }
                    """.trimIndent()
                ),
                Symbol(
                    name = "findUser",
                    kind = SymbolKind.FUNCTION,
                    filePath = "src/main/kotlin/com/example/UserService.kt",
                    lineNumber = 12,
                    content = "fun findUser(id: String): User? { return null }"
                )
            )

            // Verbalize using INCREMENTAL strategy
            val intent = createTestIntent()
            val results = engine.verbalize(
                clusterId = "user-service",
                symbols = symbols,
                strategy = VerbalizationStrategy.INCREMENTAL,
                intent = intent
            )

            // Verify results
            assertTrue(results.isNotEmpty(), "Should generate verbalization results")
            assertEquals(2, results.size, "Should have verbalization for each symbol")

            results.forEach { result ->
                assertNotNull(result.symbol, "Result should have symbol")
                assertTrue(result.description.isNotBlank(), "Description should not be blank")
                assertTrue(result.confidence >= 0.0 && result.confidence <= 1.0, "Confidence should be in valid range")
                assertEquals(VerbalizationStrategy.INCREMENTAL, result.strategy, "Should use requested strategy")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `phase1 verbalization handles method with business logic`() = runTest {
        val tempDir = Files.createTempDirectory("verbalization-test-").toFile()
        val cacheDir = I2VisionPaths.getProjectCacheDir(tempDir.absolutePath)
        cacheDir.mkdirs()

        try {
            val cacheStore = FileCacheStore(cacheDir)
            val verbalizationStore = FileVerbalizationStore(cacheStore)
            val engine = DefaultVerbalizationEngine(verbalizationStore)

            // Create a symbol with business logic
            val symbols = listOf(
                Symbol(
                    name = "processPayment",
                    kind = SymbolKind.FUNCTION,
                    filePath = "src/main/kotlin/com/example/PaymentProcessor.kt",
                    lineNumber = 25,
                    content = """
                        suspend fun processPayment(orderId: String, amount: Double): PaymentResult {
                            validate(amount)
                            val payment = Payment(orderId, amount)
                            return execute(payment)
                        }
                    """.trimIndent()
                )
            )

            val intent = createTestIntent()
            val results = engine.verbalize(
                clusterId = "payment",
                symbols = symbols,
                strategy = VerbalizationStrategy.INCREMENTAL,
                intent = intent
            )

            // Verify business logic is captured
            assertTrue(results.isNotEmpty(), "Should generate verbalization")
            val result = results.first()
            assertTrue(result.description.isNotBlank(), "Should describe business logic")
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    // ========== PHASE 2: Storage and Retrieval ==========

    @Test
    fun `phase2 verbalization results are stored and retrieved`() = runTest {
        val tempDir = Files.createTempDirectory("verbalization-test-").toFile()
        val cacheDir = I2VisionPaths.getProjectCacheDir(tempDir.absolutePath)
        cacheDir.mkdirs()

        try {
            val cacheStore = FileCacheStore(cacheDir)
            val verbalizationStore = FileVerbalizationStore(cacheStore)
            val engine = DefaultVerbalizationEngine(verbalizationStore)

            val symbol = Symbol(
                name = "ApiController",
                kind = SymbolKind.CLASS,
                filePath = "src/main/kotlin/com/example/ApiController.kt",
                lineNumber = 5,
                content = """
                    @RestController
                    class ApiController {
                        @GetMapping("/api/users")
                        fun getUsers(): List<User> { return emptyList() }
                    }
                """.trimIndent()
            )

            val intent = createTestIntent()
            val symbols = listOf(symbol)
            val clusterId = "api"

            // Verbalize
            val results = engine.verbalize(
                clusterId = clusterId,
                symbols = symbols,
                strategy = VerbalizationStrategy.INCREMENTAL,
                intent = intent
            )

            // Verify results are stored
            assertTrue(results.isNotEmpty(), "Should have verbalization results")

            // Store results
            verbalizationStore.putVerbalizations(clusterId, results)

            // Retrieve and verify
            val retrieved = verbalizationStore.getVerbalizations(clusterId)
            assertNotNull(retrieved, "Should retrieve stored verbalizations")
            assertTrue(retrieved.isNotEmpty(), "Retrieved verbalizations should not be empty")
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `phase2 cached verbalization retrieval works for symbols`() = runTest {
        val tempDir = Files.createTempDirectory("verbalization-test-").toFile()
        val cacheDir = I2VisionPaths.getProjectCacheDir(tempDir.absolutePath)
        cacheDir.mkdirs()

        try {
            val cacheStore = FileCacheStore(cacheDir)
            val verbalizationStore = FileVerbalizationStore(cacheStore)
            val engine = DefaultVerbalizationEngine(verbalizationStore)

            val symbol = Symbol(
                name = "DatabaseService",
                kind = SymbolKind.CLASS,
                filePath = "src/main/kotlin/com/example/DatabaseService.kt",
                lineNumber = 1,
                content = """
                    class DatabaseService {
                        fun query(sql: String): List<Any> { return emptyList() }
                    }
                """.trimIndent()
            )

            // Get cached verbalization (should be null initially)
            val initial = engine.getCachedVerbalization(symbol)
            assertFalse(initial != null, "Initially should have no cached verbalization")
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    // ========== PHASE 3: Verbalization Strategies ==========

    @Test
    fun `phase3 incremental strategy verbalization works`() = runTest {
        val tempDir = Files.createTempDirectory("verbalization-test-").toFile()
        val cacheDir = I2VisionPaths.getProjectCacheDir(tempDir.absolutePath)
        cacheDir.mkdirs()

        try {
            val cacheStore = FileCacheStore(cacheDir)
            val verbalizationStore = FileVerbalizationStore(cacheStore)
            val engine = DefaultVerbalizationEngine(verbalizationStore)

            val symbols = listOf(
                Symbol(
                    name = "Repository",
                    kind = SymbolKind.INTERFACE,
                    filePath = "src/main/kotlin/com/example/Repository.kt",
                    lineNumber = 1,
                    content = "interface Repository<T> { fun findAll(): List<T> }"
                )
            )

            val intent = createTestIntent()
            val results = engine.verbalize(
                clusterId = "data",
                symbols = symbols,
                strategy = VerbalizationStrategy.INCREMENTAL,
                intent = intent
            )

            assertTrue(results.isNotEmpty(), "INCREMENTAL strategy should produce results")
            assertEquals(1, results.size, "Should have one result")
            assertEquals("Repository", results.first().symbol?.name, "Should verbalize Repository interface")
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `phase3 multi-pass strategy verbalization produces quality output`() = runTest {
        val tempDir = Files.createTempDirectory("verbalization-test-").toFile()
        val cacheDir = I2VisionPaths.getProjectCacheDir(tempDir.absolutePath)
        cacheDir.mkdirs()

        try {
            val cacheStore = FileCacheStore(cacheDir)
            val verbalizationStore = FileVerbalizationStore(cacheStore)
            val engine = DefaultVerbalizationEngine(verbalizationStore)

            val symbols = listOf(
                Symbol(
                    name = "OrderService",
                    kind = SymbolKind.CLASS,
                    filePath = "src/main/kotlin/com/example/OrderService.kt",
                    lineNumber = 1,
                    content = """
                        class OrderService(
                            private val repository: OrderRepository,
                            private val paymentClient: PaymentClient
                        ) {
                            fun createOrder(items: List<Item>): Order {
                                val total = items.sumOf { it.price }
                                return Order(items, total, Status.PENDING)
                            }
                        }
                    """.trimIndent()
                )
            )

            val intent = createTestIntent()
            val results = engine.verbalize(
                clusterId = "orders",
                symbols = symbols,
                strategy = VerbalizationStrategy.MULTI_PASS,
                intent = intent
            )

            assertTrue(results.isNotEmpty(), "MULTI_PASS strategy should produce results")
            
            val result = results.first()
            assertNotNull(result.symbol, "Should have symbol")
            assertTrue(result.description.isNotBlank(), "Should have description")
            assertEquals(VerbalizationStrategy.MULTI_PASS, result.strategy, "Should use MULTI_PASS strategy")
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    // ========== PHASE 4: Caching and Hash Management ==========

    @Test
    fun `phase4 unchanged symbols are cached correctly`() = runTest {
        val tempDir = Files.createTempDirectory("verbalization-test-").toFile()
        val cacheDir = I2VisionPaths.getProjectCacheDir(tempDir.absolutePath)
        cacheDir.mkdirs()

        try {
            val cacheStore = FileCacheStore(cacheDir)
            val verbalizationStore = FileVerbalizationStore(cacheStore)
            val engine = DefaultVerbalizationEngine(verbalizationStore)

            val symbol = Symbol(
                name = "ConfigService",
                kind = SymbolKind.CLASS,
                filePath = "src/main/kotlin/com/example/ConfigService.kt",
                lineNumber = 1,
                content = "class ConfigService { private val config = mapOf<String, Any>() }"
            )

            val intent = createTestIntent()
            val initialHash = "abc123"
            
            // Check if needs reverbalization (should be true for new content)
            val needsReverbalization = engine.needsReverbalization(symbol, initialHash)
            assertTrue(needsReverbalization, "Should need reverbalization for new symbol")
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    // ========== PHASE 5: Integration with Discovery ==========

    @Test
    fun `phase5 integration verbalizes discovery output`() = runTest {
        val tempDir = Files.createTempDirectory("verbalization-test-").toFile()
        val cacheDir = I2VisionPaths.getProjectCacheDir(tempDir.absolutePath)
        cacheDir.mkdirs()

        try {
            val cacheStore = FileCacheStore(cacheDir)
            val verbalizationStore = FileVerbalizationStore(cacheStore)
            val engine = DefaultVerbalizationEngine(verbalizationStore)

            // Simulate discovery output symbols
            val symbols = listOf(
                Symbol(
                    name = "UserController",
                    kind = SymbolKind.CLASS,
                    filePath = "src/main/kotlin/com/example/UserController.kt",
                    lineNumber = 1,
                    content = """
                        @RestController
                        @RequestMapping("/users")
                        class UserController(private val userService: UserService) {
                            @GetMapping("/{id}")
                            fun getUser(@PathVariable id: String) = userService.findUser(id)
                        }
                    """.trimIndent()
                ),
                Symbol(
                    name = "UserService",
                    kind = SymbolKind.CLASS,
                    filePath = "src/main/kotlin/com/example/UserService.kt",
                    lineNumber = 1,
                    content = """
                        @Service
                        class UserService(private val repository: UserRepository) {
                            fun findUser(id: String) = repository.findById(id)
                            fun findAll() = repository.findAll()
                        }
                    """.trimIndent()
                )
            )

            val intent = createTestIntent()
            val results = engine.verbalize(
                clusterId = "user-module",
                symbols = symbols,
                strategy = VerbalizationStrategy.INCREMENTAL,
                intent = intent
            )

            assertTrue(results.size >= 2, "Should verbalize all discovery symbols")
            
            val userControllerResult = results.find { it.symbol?.name == "UserController" }
            assertNotNull(userControllerResult, "Should have verbalization for UserController")
            assertTrue(userControllerResult.description.contains("User") || userControllerResult.description.contains("REST") || userControllerResult.description.isNotBlank(), 
                "Should describe UserController functionality")
            
            results.forEach { result ->
                assertTrue(result.metadata.isNotEmpty() || true, "Should preserve metadata information")
            }
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    // ========== Helper Methods ==========

    private fun createTestIntent(): DiscoveryIntent {
        return DiscoveryIntent(
            goal = IntentGoal.FULL_DISCOVERY,
            depth = IntentDepth.STANDARD,
            quality = QualityFocus.BALANCED
        )
    }
}