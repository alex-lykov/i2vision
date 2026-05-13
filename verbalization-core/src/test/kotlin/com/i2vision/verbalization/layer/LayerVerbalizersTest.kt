/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.layer

import com.i2vision.intent.DiscoveryIntent
import com.i2vision.intent.IntentDepth
import com.i2vision.intent.IntentGoal
import com.i2vision.intent.LayerFocus
import com.i2vision.intent.QualityFocus
import com.i2vision.intent.VerbalizationConfig
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.SymbolKind
import com.i2vision.vslfc.VerbalizationStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Integration tests for all VSLFC layer verbalizers.
 */
class LayerVerbalizersTest {
    
    private val testIntent = DiscoveryIntent(
        goal = IntentGoal.FULL_DISCOVERY,
        focus = LayerFocus.ALL,
        depth = IntentDepth.STANDARD,
        quality = QualityFocus.BALANCED,
        verbalization = VerbalizationConfig(
            enabled = true,
            strategy = VerbalizationStrategy.INCREMENTAL
        ),
        constraints = mapOf("clusterId" to "test-cluster")
    )
    
    @Test
    fun `VisionVerbalizer handles documentation symbols`() = runTest {
        val verbalizer = VisionVerbalizer()
        val symbol = Symbol(
            name = "README",
            kind = SymbolKind.UNKNOWN,
            filePath = "README.md",
            lineNumber = 1,
            content = """
                # Project Vision
                
                ## Requirements
                - High performance
                - Scalability
                - Security
                
                ## Constraints
                - Must support 1000 concurrent users
            """.trimIndent()
        )
        
        assertTrue(verbalizer.canHandle(symbol))
        
        val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)
        
        assertNotNull(result)
        assertEquals(symbol, result.symbol)
        assertTrue(result.description.contains("Purpose"))
        assertTrue(result.metadata["layer"] == "VISION")
        assertTrue(result.confidence > 0.0)
    }
    
    @Test
    fun `VisionVerbalizer extracts requirements from markdown`() = runTest {
        val verbalizer = VisionVerbalizer()
        val symbol = Symbol(
            name = "DOCS",
            kind = SymbolKind.UNKNOWN,
            filePath = "docs/INDEX.md",
            lineNumber = 1,
            content = """
                # System Documentation
                
                ## Features
                - User authentication
                - Data processing
                - Report generation
            """.trimIndent()
        )
        
        val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)
        
        assertTrue(result.description.contains("requirements"))
        assertTrue(result.description.contains("User authentication") || 
                   result.description.contains("Data processing"))
    }
    
    @Test
    fun `StructureVerbalizer handles class symbols`() = runTest {
        val verbalizer = StructureVerbalizer()
        val symbol = Symbol(
            name = "UserService",
            kind = SymbolKind.CLASS,
            filePath = "src/main/kotlin/com/example/service/UserService.kt",
            lineNumber = 10,
            content = """
                package com.example.service
                
                import com.example.repository.UserRepository
                import com.example.model.User
                
                class UserService(
                    private val userRepository: UserRepository
                ) {
                    fun getUser(id: String): User {
                        return userRepository.findById(id)
                    }
                }
            """.trimIndent()
        )
        
        assertTrue(verbalizer.canHandle(symbol))
        
        val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)
        
        assertNotNull(result)
        assertEquals(symbol, result.symbol)
        assertTrue(result.metadata["layer"] == "STRUCTURE")
        assertTrue(result.description.contains("Components") || 
                   result.description.contains("Dependencies"))
    }
    
    @Test
    fun `StructureVerbalizer detects architecture pattern`() = runTest {
        val verbalizer = StructureVerbalizer()
        val symbol = Symbol(
            name = "UserController",
            kind = SymbolKind.CLASS,
            filePath = "src/main/kotlin/com/example/controller/UserController.kt",
            lineNumber = 1,
            content = """
                @RestController
                class UserController {
                    @GetMapping("/users")
                    fun getUsers(): List<User> { ... }
                }
            """.trimIndent()
        )
        
        val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)
        
        assertTrue(result.description.contains("MVC") || 
                   result.metadata["architecture_pattern"] == "MVC")
    }
    
    @Test
    fun `LogicVerbalizer handles validation symbols`() = runTest {
        val verbalizer = LogicVerbalizer()
        val symbol = Symbol(
            name = "UserValidator",
            kind = SymbolKind.CLASS,
            filePath = "src/main/kotlin/com/example/validation/UserValidator.kt",
            lineNumber = 1,
            content = """
                class UserValidator {
                    fun validate(user: User) {
                        require(user.name.isNotEmpty()) { "Name cannot be empty" }
                        require(user.age >= 18) { "Must be 18 or older" }
                        check(user.email.contains("@"))
                    }
                }
            """.trimIndent()
        )
        
        assertTrue(verbalizer.canHandle(symbol))
        
        val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)
        
        assertNotNull(result)
        assertTrue(result.metadata["layer"] == "LOGIC")
        assertTrue(result.metadata["invariants_count"]?.toInt() ?: 0 >= 2)
    }
    
    @Test
    fun `LogicVerbalizer extracts business rules from when expressions`() = runTest {
        val verbalizer = LogicVerbalizer()
        val symbol = Symbol(
            name = "OrderProcessor",
            kind = SymbolKind.CLASS,
            filePath = "src/main/kotlin/com/example/domain/OrderProcessor.kt",
            lineNumber = 1,
            content = """
                class OrderProcessor {
                    fun process(order: Order) {
                        when (order.status) {
                            OrderStatus.PENDING -> handlePending(order)
                            OrderStatus.APPROVED -> handleApproved(order)
                            OrderStatus.REJECTED -> handleRejected(order)
                        }
                    }
                }
            """.trimIndent()
        )
        
        val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)
        
        assertTrue(result.metadata["rules_count"]?.toInt() ?: 0 >= 1)
    }
    
    @Test
    fun `FlowVerbalizer handles function symbols`() = runTest {
        val verbalizer = FlowVerbalizer()
        val symbol = Symbol(
            name = "authenticate",
            kind = SymbolKind.FUNCTION,
            filePath = "src/main/kotlin/com/example/service/AuthService.kt",
            lineNumber = 25,
            content = """
                suspend fun authenticate(credentials: Credentials): Token {
                    val user = userRepository.findByEmail(credentials.email)
                    val validated = passwordEncoder.verify(credentials.password, user.password)
                    return tokenGenerator.generate(user)
                }
            """.trimIndent()
        )
        
        assertTrue(verbalizer.canHandle(symbol))
        
        val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)
        
        assertNotNull(result)
        assertTrue(result.metadata["layer"] == "FLOW")
        assertTrue(result.description.contains("sequence") || 
                   result.description.contains("Executes") ||
                   (result.metadata["sequences_count"]?.toInt() ?: 0 >= 1))
    }
    
    @Test
    fun `FlowVerbalizer extracts API endpoints`() = runTest {
        val verbalizer = FlowVerbalizer()
        val symbol = Symbol(
            name = "UserController",
            kind = SymbolKind.CLASS,
            filePath = "src/main/kotlin/com/example/controller/UserController.kt",
            lineNumber = 1,
            content = """
                @RestController
                class UserController {
                    @GetMapping("/api/users")
                    fun getUsers(): List<User> { ... }
                    
                    @PostMapping("/api/users")
                    fun createUser(@RequestBody user: User): User { ... }
                }
            """.trimIndent()
        )
        
        val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)
        
        assertTrue(result.metadata["api_endpoints_count"]?.toInt() ?: 0 >= 2)
    }
    
    @Test
    fun `CodeVerbalizer handles all symbol types`() = runTest {
        val verbalizer = CodeVerbalizer()
        
        val classSymbol = Symbol(
            name = "TestClass",
            kind = SymbolKind.CLASS,
            filePath = "src/main/kotlin/com/example/TestClass.kt",
            lineNumber = 1,
            content = """
                /**
                 * A test class for demonstration
                 */
                class TestClass {
                    fun doSomething() { }
                }
            """.trimIndent()
        )
        
        assertTrue(verbalizer.canHandle(classSymbol))
        
        val result = verbalizer.verbalize(classSymbol, LayerVerbalizationContext(), testIntent)
        
        assertNotNull(result)
        assertTrue(result.metadata["layer"] == "CODE")
        assertTrue(result.description.contains("Signature"))
        assertTrue(result.description.contains("Documentation") || 
                   result.description.contains("test class"))
    }
    
    @Test
    fun `CodeVerbalizer extracts function signature`() = runTest {
        val verbalizer = CodeVerbalizer()
        val symbol = Symbol(
            name = "calculate",
            kind = SymbolKind.FUNCTION,
            filePath = "src/main/kotlin/com/example/Calculator.kt",
            lineNumber = 10,
            content = """
                suspend fun calculate(a: Int, b: Int): Int {
                    return a + b
                }
            """.trimIndent()
        )
        
        val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)
        
        assertTrue(result.description.contains("fun calculate") || 
                   result.description.contains("Function"))
    }
    
    @Test
    fun `MultiLayerVerbalizer coordinates all verbalizers`() = runTest {
        val multiLayerVerbalizer = MultiLayerVerbalizer()
        val symbols = listOf(
            Symbol(
                name = "README",
                kind = SymbolKind.UNKNOWN,
                filePath = "README.md",
                lineNumber = 1,
                content = "# Project\n\n## Requirements\n- Feature A"
            ),
            Symbol(
                name = "UserService",
                kind = SymbolKind.CLASS,
                filePath = "src/main/kotlin/UserService.kt",
                lineNumber = 1,
                content = "class UserService { }"
            )
        )
        
        val results = multiLayerVerbalizer.verbalize(symbols, testIntent)
        
        assertTrue(results.isNotEmpty())
        
        // Check that we have results from multiple layers
        val layers = results.mapNotNull { it.metadata["layer"] }.toSet()
        assertTrue(layers.size >= 2, "Expected results from at least 2 layers, got: $layers")
    }
    
    @Test
    fun `MultiLayerVerbalizer groups results by layer`() = runTest {
        val multiLayerVerbalizer = MultiLayerVerbalizer()
        val symbol = Symbol(
            name = "TestSymbol",
            kind = SymbolKind.CLASS,
            filePath = "src/main/kotlin/Test.kt",
            lineNumber = 1,
            content = "class TestSymbol { }"
        )
        
        val groupedResults = multiLayerVerbalizer.verbalizeGroupedByLayer(listOf(symbol), testIntent)
        
        assertTrue(groupedResults.isNotEmpty())
        assertTrue(groupedResults.keys.any { it in listOf("VISION", "STRUCTURE", "LOGIC", "FLOW", "CODE") })
    }
    
    @Test
    fun `LayerVerbalizerFactory provides all verbalizers`() {
        val verbalizers = LayerVerbalizerFactory.getAllVerbalizers()
        
        assertEquals(5, verbalizers.size)
        assertTrue(verbalizers.any { it.layerName == "Vision" })
        assertTrue(verbalizers.any { it.layerName == "Structure" })
        assertTrue(verbalizers.any { it.layerName == "Logic" })
        assertTrue(verbalizers.any { it.layerName == "Flow" })
        assertTrue(verbalizers.any { it.layerName == "Code" })
    }
    
    @Test
    fun `LayerVerbalizerFactory gets verbalizer by layer`() {
        val visionVerbalizer = LayerVerbalizerFactory.getVerbalizerForLayer(VSLFCLayer.VISION)
        assertTrue(visionVerbalizer is VisionVerbalizer)
        
        val structureVerbalizer = LayerVerbalizerFactory.getVerbalizerForLayer(VSLFCLayer.STRUCTURE)
        assertTrue(structureVerbalizer is StructureVerbalizer)
        
        val logicVerbalizer = LayerVerbalizerFactory.getVerbalizerForLayer(VSLFCLayer.LOGIC)
        assertTrue(logicVerbalizer is LogicVerbalizer)
        
        val flowVerbalizer = LayerVerbalizerFactory.getVerbalizerForLayer(VSLFCLayer.FLOW)
        assertTrue(flowVerbalizer is FlowVerbalizer)
        
        val codeVerbalizer = LayerVerbalizerFactory.getVerbalizerForLayer(VSLFCLayer.CODE)
        assertTrue(codeVerbalizer is CodeVerbalizer)
    }
}
