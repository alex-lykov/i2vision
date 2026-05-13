/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization

import com.i2vision.intent.DiscoveryIntent
import com.i2vision.intent.IntentGoal
import com.i2vision.intent.LayerFocus
import com.i2vision.verbalization.strategy.ContextProvider
import com.i2vision.verbalization.strategy.MultiPassVerbalizationStrategy
import com.i2vision.verbalization.strategy.SymbolContext
import com.i2vision.vslfc.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Test suite for cross-layer enrichment in MULTI_PASS strategy.
 * Validates that Flow, Logic, and Structure layer data is properly extracted and used.
 */
@DisplayName("Cross-Layer Enrichment Tests")
class CrossLayerEnrichmentTest {

    private lateinit var patternMatcher: PatternMatcher
    private lateinit var contextProvider: ContextProvider
    private lateinit var hashManager: HashManager
    private lateinit var multiPassStrategy: MultiPassVerbalizationStrategy

    @BeforeEach
    fun setup() {
        patternMatcher = PatternMatcher()
        hashManager = HashManager()
        
        // Use mock store for testing
        val store = MockVerbalizationStore()
        contextProvider = CrossLayerContextProvider(store)
        
        multiPassStrategy = MultiPassVerbalizationStrategy(
            patternMatcher = patternMatcher,
            contextProvider = contextProvider,
            hashManager = hashManager
        )
    }

    @Nested
    @DisplayName("Context Provider - Dependency Extraction")
    inner class DependencyExtractionTests {

        @Test
        fun `should extract import dependencies from symbol content`() {
            val symbol = createSymbol(
                content = """
                    import com.example.auth.TokenValidator
                    import com.example.user.UserRepository
                    import com.example.audit.AuditLogger
                    import kotlin.collections.List
                """.trimIndent()
            )

            val context = contextProvider.getContext(symbol)

            assertTrue(context.moduleDependencies.isNotEmpty())
            assertTrue(context.moduleDependencies.contains("com.example.auth.TokenValidator"))
            assertTrue(context.moduleDependencies.contains("com.example.user.UserRepository"))
            // Kotlin imports should be filtered out
            assertFalse(context.moduleDependencies.any { it.startsWith("kotlin") })
        }

        @Test
        fun `should extract structure dependencies from constructor`() {
            val symbol = createSymbol(
                content = """
                    class AuthService constructor(
                        private val userRepository: UserRepository,
                        private val tokenValidator: TokenValidator
                    ) {
                        // ...
                    }
                """.trimIndent()
            )

            val context = contextProvider.getContext(symbol)

            assertTrue(context.moduleDependencies.contains("UserRepository"))
            assertTrue(context.moduleDependencies.contains("TokenValidator"))
        }

        @Test
        fun `should extract property type dependencies`() {
            val symbol = createSymbol(
                content = """
                    class OrderService {
                        private val paymentGateway: PaymentGateway
                        private val inventoryManager: InventoryManager
                        val notificationService: NotificationService
                    }
                """.trimIndent()
            )

            val context = contextProvider.getContext(symbol)

            assertTrue(context.moduleDependencies.contains("PaymentGateway"))
            assertTrue(context.moduleDependencies.contains("InventoryManager"))
            assertTrue(context.moduleDependencies.contains("NotificationService"))
        }
    }

    @Nested
    @DisplayName("Context Provider - Flow Layer Extraction")
    inner class FlowLayerTests {

        @Test
        fun `should extract flows from annotations`() {
            val symbol = createSymbol(
                content = """
                    @Flow("LoginFlow")
                    @Sequence("AuthenticationSequence")
                    fun authenticate(credentials: Credentials): AuthResult {
                        // ...
                    }
                """.trimIndent()
            )

            val context = contextProvider.getContext(symbol)

            assertTrue(context.callingFlows.contains("LoginFlow"))
            assertTrue(context.callingFlows.contains("AuthenticationSequence"))
        }

        @Test
        fun `should extract flows from comments`() {
            val symbol = createSymbol(
                content = """
                    // Flow: OrderProcessingFlow
                    fun processOrder(order: Order): Result {
                        // ...
                    }
                """.trimIndent()
            )

            val context = contextProvider.getContext(symbol)

            assertTrue(context.callingFlows.contains("OrderProcessingFlow"))
        }

        @Test
        fun `should extract flow references from method calls`() {
            val symbol = createSymbol(
                content = """
                    fun handleRequest() {
                        paymentFlow.execute()
                        notificationFlow.send()
                    }
                """.trimIndent()
            )

            val context = contextProvider.getContext(symbol)

            assertTrue(context.callingFlows.contains("paymentFlow"))
            assertTrue(context.callingFlows.contains("notificationFlow"))
        }
    }

    @Nested
    @DisplayName("Context Provider - Logic Layer Extraction")
    inner class LogicLayerTests {

        @Test
        fun `should extract business rules from require statements`() {
            val symbol = createSymbol(
                content = """
                    fun createUser(username: String, email: String) {
                        require(username.isNotBlank()) { "Username is required" }
                        require(email.contains("@")) { "Invalid email format" }
                        require(username.length >= 3) { "Username too short" }
                    }
                """.trimIndent()
            )

            val context = contextProvider.getContext(symbol)

            assertTrue(context.businessRules.any { it.contains("Username is required") })
            assertTrue(context.businessRules.any { it.contains("Invalid email format") })
            assertTrue(context.businessRules.any { it.contains("Username too short") })
        }

        @Test
        fun `should extract invariants from check statements`() {
            val symbol = createSymbol(
                content = """
                    fun transferFunds(amount: Double) {
                        check(balance >= amount) { "Insufficient balance" }
                        check(amount > 0) { "Amount must be positive" }
                    }
                """.trimIndent()
            )

            val context = contextProvider.getContext(symbol)

            assertTrue(context.businessRules.any { it.contains("Insufficient balance") })
            assertTrue(context.businessRules.any { it.contains("Amount must be positive") })
        }

        @Test
        fun `should extract validation logic from if-throw statements`() {
            val symbol = createSymbol(
                content = """
                    fun validateToken(token: String) {
                        if (token.isBlank()) throw InvalidTokenException()
                        if (token.length < 10) throw TokenTooShortException()
                    }
                """.trimIndent()
            )

            val context = contextProvider.getContext(symbol)

            assertTrue(context.businessRules.any { it.contains("InvalidTokenException") })
            assertTrue(context.businessRules.any { it.contains("TokenTooShortException") })
        }

        @Test
        fun `should extract business rule annotations`() {
            val symbol = createSymbol(
                content = """
                    @BusinessRule("Users must be authenticated before accessing admin features")
                    @Constraint("Admin role required")
                    fun accessAdminFeature() {
                        // ...
                    }
                """.trimIndent()
            )

            val context = contextProvider.getContext(symbol)

            assertTrue(context.businessRules.any { it.contains("Users must be authenticated") })
            assertTrue(context.businessRules.any { it.contains("Admin role required") })
        }
    }

    @Nested
    @DisplayName("Context Provider - Architectural Layer Detection")
    inner class ArchitecturalLayerTests {

        @Test
        fun `should detect presentation layer from path`() {
            val symbol = createSymbol(
                filePath = "com/example/controller/UserController.kt",
                content = ""
            )

            val context = contextProvider.getContext(symbol)

            assertEquals("presentation", context.architecturalLayer)
        }

        @Test
        fun `should detect domain layer from path`() {
            val symbol = createSymbol(
                filePath = "com/example/service/OrderService.kt",
                content = ""
            )

            val context = contextProvider.getContext(symbol)

            assertEquals("domain", context.architecturalLayer)
        }

        @Test
        fun `should detect data layer from path`() {
            val symbol = createSymbol(
                filePath = "com/example/repository/UserRepository.kt",
                content = ""
            )

            val context = contextProvider.getContext(symbol)

            assertEquals("data", context.architecturalLayer)
        }

        @Test
        fun `should detect flow layer from path`() {
            val symbol = createSymbol(
                filePath = "com/example/flow/CheckoutFlow.kt",
                content = ""
            )

            val context = contextProvider.getContext(symbol)

            assertEquals("flow", context.architecturalLayer)
        }

        @Test
        fun `should detect logic layer from path`() {
            val symbol = createSymbol(
                filePath = "com/example/logic/PricingLogic.kt",
                content = ""
            )

            val context = contextProvider.getContext(symbol)

            assertEquals("logic", context.architecturalLayer)
        }
    }

    @Nested
    @DisplayName("Context Provider - Technical Context Detection")
    inner class TechnicalContextTests {

        @Test
        fun `should detect database access`() {
            val symbol = createSymbol(
                content = """
                    @Repository
                    class UserRepository(
                        private val entityManager: EntityManager
                    ) {
                        // Uses database
                    }
                """.trimIndent()
            )

            val context = contextProvider.getContext(symbol)

            assertTrue(context.hasDatabaseAccess)
        }

        @Test
        fun `should detect external API calls`() {
            val symbol = createSymbol(
                content = """
                    class PaymentService(
                        private val httpClient: HttpClient,
                        private val restClient: RestTemplate
                    ) {
                        // Makes external calls
                    }
                """.trimIndent()
            )

            val context = contextProvider.getContext(symbol)

            assertTrue(context.hasExternalCalls)
        }
    }

    @Nested
    @DisplayName("Multi-Pass Strategy - Cross-Layer Enrichment")
    inner class MultiPassEnrichmentTests {

        @Test
        fun `should enrich description with flow context`() = runBlocking {
            val symbol = createSymbol(
                content = """
                    @Flow("LoginFlow")
                    fun authenticate(credentials: Credentials): AuthResult {
                        require(credentials.password.isNotBlank()) { "Password required" }
                        // Authentication logic
                    }
                """.trimIndent()
            )

            val results = multiPassStrategy.verbalize(
                symbols = listOf(symbol),
                intent = DiscoveryIntent(
                    goal = IntentGoal.FULL_DISCOVERY,
                    focus = LayerFocus.ALL
                )
            )

            assertTrue(results.isNotEmpty())
            val result = results.first()
            
            // Should have cross-layer references in metadata
            assertTrue(result.metadata.containsKey("cross_layer_refs"))
            assertTrue(result.metadata["cross_layer_refs"]!!.contains("flows:"))
        }

        @Test
        fun `should enrich description with business rules`() = runBlocking {
            val symbol = createSymbol(
                content = """
                    fun validateOrder(order: Order) {
                        require(order.items.isNotEmpty()) { "Order must have items" }
                        require(order.total > 0) { "Order total must be positive" }
                        check(order.customer != null) { "Customer required" }
                    }
                """.trimIndent()
            )

            val results = multiPassStrategy.verbalize(
                symbols = listOf(symbol),
                intent = DiscoveryIntent(
                    goal = IntentGoal.FULL_DISCOVERY,
                    focus = LayerFocus.ALL
                )
            )

            assertTrue(results.isNotEmpty())
            val result = results.first()
            
            // Should have business rules in metadata
            assertTrue(result.metadata.containsKey("cross_layer_refs"))
            assertTrue(result.metadata["cross_layer_refs"]!!.contains("rules:"))
        }

        @Test
        fun `should enrich description with dependencies`() = runBlocking {
            val symbol = createSymbol(
                content = """
                    class OrderProcessor(
                        private val paymentGateway: PaymentGateway,
                        private val inventoryManager: InventoryManager
                    ) {
                        fun process(order: Order) {
                            paymentGateway.charge()
                            inventoryManager.reserve()
                        }
                    }
                """.trimIndent()
            )

            val results = multiPassStrategy.verbalize(
                symbols = listOf(symbol),
                intent = DiscoveryIntent(
                    goal = IntentGoal.FULL_DISCOVERY,
                    focus = LayerFocus.ALL
                )
            )

            assertTrue(results.isNotEmpty())
            val result = results.first()
            
            // Should have dependencies in metadata
            assertTrue(result.metadata.containsKey("cross_layer_refs"))
            assertTrue(result.metadata["cross_layer_refs"]!!.contains("deps:"))
        }

        @Test
        fun `should increase confidence after cross-layer enrichment`() = runBlocking {
            val symbol = createSymbol(
                content = """
                    @Flow("CheckoutFlow")
                    class CheckoutService(
                        private val paymentGateway: PaymentGateway
                    ) {
                        require(amount > 0) { "Amount must be positive" }
                    }
                """.trimIndent()
            )

            val results = multiPassStrategy.verbalize(
                symbols = listOf(symbol),
                intent = DiscoveryIntent(
                    goal = IntentGoal.FULL_DISCOVERY,
                    focus = LayerFocus.ALL
                )
            )

            assertTrue(results.isNotEmpty())
            val result = results.first()
            
            // Confidence should be boosted by cross-layer context
            assertTrue(result.confidence >= 0.7)
        }

        @Test
        fun `should add architectural layer to metadata`() = runBlocking {
            val symbol = createSymbol(
                filePath = "com/example/service/OrderService.kt",
                content = "class OrderService { }"
            )

            val results = multiPassStrategy.verbalize(
                symbols = listOf(symbol),
                intent = DiscoveryIntent(
                    goal = IntentGoal.FULL_DISCOVERY,
                    focus = LayerFocus.ALL
                )
            )

            assertTrue(results.isNotEmpty())
            val result = results.first()
            
            assertTrue(result.metadata.containsKey("architectural_layer"))
            assertEquals("domain", result.metadata["architectural_layer"])
        }
    }

    @Nested
    @DisplayName("Symbol Context - Cross-Layer Summary")
    inner class CrossLayerSummaryTests {

        @Test
        fun `should generate summary with flows`() {
            val context = SymbolContext(
                relatedSymbols = emptyList(),
                moduleDependencies = emptyList(),
                hasDatabaseAccess = false,
                hasExternalCalls = false,
                callingFlows = listOf("LoginFlow", "LogoutFlow"),
                businessRules = emptyList(),
                architecturalLayer = "domain"
            )

            val summary = context.getCrossLayerSummary()

            assertTrue(summary.contains("Called by 2 flow(s)"))
        }

        @Test
        fun `should generate summary with business rules`() {
            val context = SymbolContext(
                relatedSymbols = emptyList(),
                moduleDependencies = emptyList(),
                hasDatabaseAccess = false,
                hasExternalCalls = false,
                callingFlows = emptyList(),
                businessRules = listOf("Validation: email required", "Invariant: age >= 18"),
                architecturalLayer = "domain"
            )

            val summary = context.getCrossLayerSummary()

            assertTrue(summary.contains("Enforces 2 business rule(s)"))
        }

        @Test
        fun `should generate summary with multiple cross-layer refs`() {
            val context = SymbolContext(
                relatedSymbols = listOf(
                    createSymbol(name = "ServiceA"),
                    createSymbol(name = "ServiceB")
                ),
                moduleDependencies = emptyList(),
                hasDatabaseAccess = true,
                hasExternalCalls = false,
                callingFlows = listOf("Flow1"),
                businessRules = listOf("Rule1"),
                architecturalLayer = "domain"
            )

            val summary = context.getCrossLayerSummary()

            assertTrue(summary.contains("Called by 1 flow(s)"))
            assertTrue(summary.contains("Enforces 1 business rule(s)"))
            assertTrue(summary.contains("Coordinates with 2 service(s)"))
            assertTrue(summary.contains("with database access"))
        }

        @Test
        fun `should return empty summary when no cross-layer refs`() {
            val context = SymbolContext(
                relatedSymbols = emptyList(),
                moduleDependencies = emptyList(),
                hasDatabaseAccess = false,
                hasExternalCalls = false,
                callingFlows = emptyList(),
                businessRules = emptyList(),
                architecturalLayer = "unknown"
            )

            val summary = context.getCrossLayerSummary()

            assertTrue(summary.isEmpty())
        }
    }

    // Helper function to create test symbols
    private fun createSymbol(
        name: String = "TestSymbol",
        kind: SymbolKind = SymbolKind.FUNCTION,
        filePath: String = "com/example/TestFile.kt",
        lineNumber: Int = 10,
        content: String = ""
    ): Symbol {
        return Symbol(
            name = name,
            kind = kind,
            filePath = filePath,
            lineNumber = lineNumber,
            content = content,
            metadata = emptyMap()
        )
    }

    // Mock implementation of VerbalizationStore for testing
    class MockVerbalizationStore : VerbalizationStore {
        private val verbalizations = mutableMapOf<String, MutableList<VerbalizationResult>>()
        private val hashes = mutableMapOf<String, MutableMap<String, String>>()
        private val contextHashes = mutableMapOf<String, MutableMap<String, String>>()

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

        override suspend fun putContextHashes(clusterId: String, hashes: Map<String, String>): PutResult {
            this.contextHashes[clusterId] = hashes.toMutableMap()
            return PutResult.Success(hashes.size)
        }

        override suspend fun getContextHashes(clusterId: String): Map<String, String>? {
            return contextHashes[clusterId]
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
}
