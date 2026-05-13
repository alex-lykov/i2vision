/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization

import com.i2vision.arch.detector.KotlinModifierExtractor
import com.i2vision.arch.signature.EnrichedSymbol
import com.i2vision.arch.signature.ModifierKind
import com.i2vision.arch.signature.StructuralRole
import com.i2vision.arch.signature.enrich
import com.i2vision.arch.signature.toEnriched
import com.i2vision.intent.DiscoveryIntent
import com.i2vision.intent.IntentGoal
import com.i2vision.intent.LayerFocus
import com.i2vision.verbalization.layer.CodeVerbalizer
import com.i2vision.verbalization.layer.FlowVerbalizer
import com.i2vision.verbalization.layer.LayerVerbalizationContext
import com.i2vision.verbalization.layer.LogicVerbalizer
import com.i2vision.verbalization.layer.StructureVerbalizer
import com.i2vision.verbalization.modifier.KotlinModifierVerbalizer
import com.i2vision.verbalization.modifier.ModifierVerbalizer
import com.i2vision.verbalization.modifier.VerbalizationContext
import com.i2vision.verbalization.modifier.verbalizeEnrichedSymbol
import com.i2vision.verbalization.modifier.verbalizeWithContext
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.SymbolKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Integration tests for structured detection system.
 * Validates end-to-end flow from enrichment to verbalization.
 */
@DisplayName("Structured Detection Integration Tests")
class StructuredDetectionIntegrationTest {

    private lateinit var modifierExtractor: KotlinModifierExtractor
    private lateinit var modifierVerbalizer: ModifierVerbalizer
    private lateinit var structureVerbalizer: StructureVerbalizer
    private lateinit var flowVerbalizer: FlowVerbalizer
    private lateinit var logicVerbalizer: LogicVerbalizer

    @BeforeEach
    fun setup() {
        modifierExtractor = KotlinModifierExtractor()
        modifierVerbalizer = KotlinModifierVerbalizer()
        structureVerbalizer = StructureVerbalizer()
        flowVerbalizer = FlowVerbalizer()
        logicVerbalizer = LogicVerbalizer()
    }

    @Nested
    @DisplayName("Modifier Extraction & Verbalization")
    inner class ModifierExtractionTests {

        @Test
        fun `should extract and verbalize suspend modifier`() {
            val symbol = Symbol(
                name = "authenticate",
                kind = SymbolKind.FUNCTION,
                filePath = "com/example/auth/AuthService.kt",
                lineNumber = 10,
                content = """
                    suspend fun authenticate(credentials: Credentials): AuthResult {
                        require(credentials.password.isNotBlank())
                        // Authentication logic
                    }
                """.trimIndent()
            )

            // Enrich with structured data
            val enriched = symbol.enrich {
                addModifier(com.i2vision.arch.signature.SymbolModifier(ModifierKind.SUSPEND))
                addModifier(com.i2vision.arch.signature.SymbolModifier(ModifierKind.BUSINESS_RULE))
                setStructuralRole(StructuralRole.SERVICE)
            }

            // Verbalize using structured data
            val verbalizer = KotlinModifierVerbalizer()
            val description = verbalizer.verbalizeWithContext(
                VerbalizationContext(
                    baseDescription = "Authenticates user credentials",
                    enrichedSymbol = enriched,
                    includeModifiers = true,
                    includeRole = true,
                    includeTechnicalContext = true
                )
            )

            assertTrue(description.contains("suspending"))
            assertTrue(description.contains("service"))
            assertTrue(description.contains("enforces business rules"))
        }

        @Test
        fun `should extract and verbalize data class modifier`() {
            val symbol = Symbol(
                name = "User",
                kind = SymbolKind.CLASS,
                filePath = "com/example/model/User.kt",
                lineNumber = 5,
                content = """
                    data class User(
                        val id: String,
                        val email: String,
                        val name: String
                    )
                """.trimIndent()
            )

            val enriched = symbol.enrich {
                addModifier(com.i2vision.arch.signature.SymbolModifier(ModifierKind.DATA_CLASS))
            }

            val description = modifierVerbalizer.verbalizeEnrichedSymbol(
                enriched = enriched,
                baseDescription = "Represents user entity"
            )

            assertTrue(description.contains("data class"))
        }

        @Test
        fun `should verbalize multiple modifiers correctly`() {
            val enriched = Symbol(
                name = "OrderProcessor",
                kind = SymbolKind.CLASS,
                filePath = "com/example/service/OrderProcessor.kt",
                lineNumber = 1,
                content = ""
            ).enrich {
                addModifier(com.i2vision.arch.signature.SymbolModifier(ModifierKind.SERVICE))
                addModifier(com.i2vision.arch.signature.SymbolModifier(ModifierKind.SUSPEND))
                addModifier(com.i2vision.arch.signature.SymbolModifier(ModifierKind.DATABASE_ACCESS))
            }

            val description = modifierVerbalizer.verbalizeEnrichedSymbol(
                enriched = enriched,
                baseDescription = "Processes orders"
            )

            assertTrue(description.contains("service"))
            assertTrue(description.contains("suspending"))
            assertTrue(description.contains("database access"))
        }
    }

    @Nested
    @DisplayName("Layer Verbalizers with Structured Data")
    inner class LayerVerbalizerTests {

        @Test
        fun `should verbalize structure layer with enriched symbol`() = runBlocking {
            val enriched = Symbol(
                name = "UserService",
                kind = SymbolKind.CLASS,
                filePath = "com/example/service/UserService.kt",
                lineNumber = 1,
                content = ""
            ).enrich {
                addModifier(com.i2vision.arch.signature.SymbolModifier(ModifierKind.SERVICE))
                addModifier(com.i2vision.arch.signature.SymbolModifier(ModifierKind.REPOSITORY))
                setStructuralRole(StructuralRole.SERVICE)
                addDependency("UserRepository")
                addDependency("EmailService")
            }

            val result = structureVerbalizer.verbalize(
                symbol = enriched,
                context = LayerVerbalizationContext(
                    clusterId = "com/example/service"
                ),
                intent = DiscoveryIntent(
                    goal = IntentGoal.FULL_DISCOVERY,
                    focus = LayerFocus.ALL
                )
            )

            assertTrue(result.description.contains("SERVICE") || result.description.contains("business logic"))
            assertTrue(result.description.contains("Depends on 2 component(s)"))
            assertEquals("SERVICE", result.metadata["structural_role"])
        }

        @Test
        fun `should verbalize flow layer with enriched symbol`() = runBlocking {
            val enriched = Symbol(
                name = "CheckoutController",
                kind = SymbolKind.CLASS,
                filePath = "com/example/controller/CheckoutController.kt",
                lineNumber = 1,
                content = ""
            ).enrich {
                setStructuralRole(StructuralRole.CONTROLLER)
                addFlow("CheckoutFlow")
                addFlow("PaymentFlow")
                addModifier(com.i2vision.arch.signature.SymbolModifier(ModifierKind.EVENT_PUBLISHER))
            }

            val result = flowVerbalizer.verbalize(
                symbol = enriched,
                context = LayerVerbalizationContext(
                    clusterId = "com/example/controller"
                ),
                intent = DiscoveryIntent(
                    goal = IntentGoal.FULL_DISCOVERY,
                    focus = LayerFocus.ALL
                )
            )

            assertTrue(result.description.contains("CheckoutFlow") || result.description.contains("flow"))
            assertTrue(result.description.contains("event interaction") || result.metadata["flows"]?.contains("CheckoutFlow") == true)
            assertEquals("2", result.metadata["sequences_count"])
        }

        @Test
        fun `should verbalize logic layer with enriched symbol`() = runBlocking {
            val enriched = Symbol(
                name = "OrderValidator",
                kind = SymbolKind.CLASS,
                filePath = "com/example/validation/OrderValidator.kt",
                lineNumber = 1,
                content = ""
            ).enrich {
                setStructuralRole(StructuralRole.VALIDATOR)
                addBusinessRule("Order must have items")
                addBusinessRule("Order total must be positive")
                addBusinessRule("Customer must be authenticated")
                addModifier(com.i2vision.arch.signature.SymbolModifier(ModifierKind.VALIDATION))
                addModifier(com.i2vision.arch.signature.SymbolModifier(ModifierKind.BUSINESS_RULE))
            }

            val result = logicVerbalizer.verbalize(
                symbol = enriched,
                context = LayerVerbalizationContext(
                    clusterId = "com/example/validation"
                ),
                intent = DiscoveryIntent(
                    goal = IntentGoal.FULL_DISCOVERY,
                    focus = LayerFocus.ALL
                )
            )

            assertTrue(result.description.contains("VALIDATOR") || result.description.contains("business rule"))
            assertTrue(result.description.contains("3 business rule(s)"))
            assertEquals("3", result.metadata["rules_count"])
        }

        @Test
        fun `should verbalize code layer with symbol`() = runBlocking {
            val symbol = Symbol(
                name = "UserService",
                kind = SymbolKind.CLASS,
                filePath = "com/example/service/UserService.kt",
                lineNumber = 1,
                content = """
                    class UserService {
                        fun getUser(id: String): User {
                            // Implementation
                        }
                    }
                """.trimIndent()
            ).toEnriched()

            val codeVerbalizer = CodeVerbalizer()
            val result = codeVerbalizer.verbalize(
                symbol = symbol,
                context = LayerVerbalizationContext(
                    clusterId = "com/example/service"
                ),
                intent = DiscoveryIntent(
                    goal = IntentGoal.FULL_DISCOVERY,
                    focus = LayerFocus.ALL
                )
            )

            assertTrue(result.description.contains("class UserService"))
            assertTrue(result.metadata.containsKey("symbol_kind"))
            assertEquals("CLASS", result.metadata["symbol_kind"])
        }
    }

    @Nested
    @DisplayName("End-to-End Integration")
    inner class EndToEndIntegrationTests {

        @Test
        fun `should process complete symbol through enrichment and verbalization`() = runBlocking {
            val symbol = Symbol(
                name = "OrderService",
                kind = SymbolKind.CLASS,
                filePath = "com/example/service/OrderService.kt",
                lineNumber = 1,
                content = """
                    class OrderService(
                        private val repository: OrderRepository,
                        private val validator: OrderValidator
                    ) {
                        suspend fun createOrder(order: Order): Order {
                            validator.validate(order)
                            return repository.save(order)
                        }
                    }
                """.trimIndent()
            )

            // Enrich
            val enriched = symbol.enrich {
                setStructuralRole(StructuralRole.SERVICE)
                addModifier(com.i2vision.arch.signature.SymbolModifier(ModifierKind.SERVICE))
                addModifier(com.i2vision.arch.signature.SymbolModifier(ModifierKind.SUSPEND))
                addDependency("OrderRepository")
                addDependency("OrderValidator")
            }

            // Verbalize
            val result = structureVerbalizer.verbalize(
                symbol = enriched,
                context = LayerVerbalizationContext(clusterId = "com/example/service"),
                intent = DiscoveryIntent(
                    goal = IntentGoal.FULL_DISCOVERY,
                    focus = LayerFocus.ALL
                )
            )

            assertNotNull(result)
            assertTrue(result.description.contains("SERVICE") || result.description.contains("business logic"))
            assertTrue(result.description.contains("Depends on 2 component(s)"))
        }
    }
}
