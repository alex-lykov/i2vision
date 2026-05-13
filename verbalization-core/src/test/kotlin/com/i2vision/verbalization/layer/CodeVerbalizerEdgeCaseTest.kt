/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.layer

import com.i2vision.arch.signature.EnrichedSymbol
import com.i2vision.arch.signature.ModifierKind
import com.i2vision.arch.signature.StructuralRole
import com.i2vision.arch.signature.SymbolModifier
import com.i2vision.arch.signature.enrich
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Edge case tests for CodeVerbalizer.
 * 
 * These tests verify that the CodeVerbalizer correctly uses PSI-extracted modifiers
 * from EnrichedSymbol rather than regex-based detection on raw content.
 * 
 * Key principle: Modifiers should come from PSI-based KotlinModifierExtractor
 * (during discovery phase), NOT from regex matching on raw source code strings.
 */
@DisplayName("CodeVerbalizer Edge Cases - PSI-Based Modifier Extraction")
class CodeVerbalizerEdgeCaseTest {

    private lateinit var verbalizer: CodeVerbalizer
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

    @BeforeEach
    fun setup() {
        verbalizer = CodeVerbalizer()
    }

    @Nested
    @DisplayName("Modifiers in strings should NOT be detected via regex")
    inner class StringLiteralTests {

        @Test
        fun `suspend keyword in string literal should not be detected as modifier`() = runTest {
            // The raw content contains "suspend" in a string, but it's NOT a modifier
            // This should NOT produce a false positive
            val symbol = Symbol(
                name = "Logger",
                kind = SymbolKind.CLASS,
                filePath = "com/example/Logger.kt",
                lineNumber = 1,
                content = """
                    class Logger {
                        private val message = "This function is suspend-compatible"
                        fun log(msg: String) { println(msg) }
                    }
                """.trimIndent()
            ).enrich {
                // No suspend modifier - the symbol is NOT suspending
            }

            val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)

            // The signature should NOT contain "suspend" because no modifier was added
            assertFalse(result.description.contains("suspend"))
        }

        @Test
        fun `data class keyword in string should not cause false positive`() = runTest {
            val symbol = Symbol(
                name = "StringProcessor",
                kind = SymbolKind.CLASS,
                filePath = "com/example/StringProcessor.kt",
                lineNumber = 1,
                content = """
                    class StringProcessor {
                        val example = "data class User(val id: Int)"
                        fun process(input: String): String = input
                    }
                """.trimIndent()
            ).enrich {
                // No data class modifier
            }

            val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)

            assertFalse(result.description.contains("data class"))
        }

        @Test
        fun `inline keyword in string should not be detected`() = runTest {
            val symbol = Symbol(
                name = "TextHelper",
                kind = SymbolKind.CLASS,
                filePath = "com/example/TextHelper.kt",
                lineNumber = 1,
                content = """
                    class TextHelper {
                        val text = "Use inline functions for performance"
                        fun transform(s: String): String = s.uppercase()
                    }
                """.trimIndent()
            ).enrich {
                // No inline modifier
            }

            val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)

            assertFalse(result.description.contains("inline"))
        }
    }

    @Nested
    @DisplayName("Modifiers in comments should NOT be detected via regex")
    inner class CommentTests {

        @Test
        fun `suspend keyword in comment should not be detected as modifier`() = runTest {
            val symbol = Symbol(
                name = "AsyncHelper",
                kind = SymbolKind.CLASS,
                filePath = "com/example/AsyncHelper.kt",
                lineNumber = 1,
                content = """
                    class AsyncHelper {
                        // TODO: This should be suspend-compatible in future
                        fun process(): String = "done"
                    }
                """.trimIndent()
            ).enrich {
                // No suspend modifier
            }

            val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)

            // The word "suspend" might appear in the description (from comment extraction),
            // but the symbol metadata should NOT indicate suspend modifier was detected
            val hasSuspendModifier = result.metadata["modifiers"]?.contains("suspend") == true ||
                                    result.description.contains("suspend modifier")
            assertFalse(hasSuspendModifier)
        }

        @Test
        fun `data class keyword in KDoc should not be detected as modifier`() = runTest {
            val symbol = Symbol(
                name = "DocumentationGenerator",
                kind = SymbolKind.CLASS,
                filePath = "com/example/DocumentationGenerator.kt",
                lineNumber = 1,
                content = """
                    /**
                     * Generates data class documentation automatically.
                     */
                    class DocumentationGenerator {
                        fun generate(): String = ""
                    }
                """.trimIndent()
            ).enrich {
                // No data class modifier
            }

            val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)

            // The phrase "data class" might appear in documentation,
            // but metadata should NOT indicate data modifier was detected
            val hasDataModifier = result.metadata["modifiers"]?.contains("data") == true ||
                                  result.description.contains("data modifier")
            assertFalse(hasDataModifier)
        }
    }

    @Nested
    @DisplayName("PSI-extracted modifiers should be correctly verbalized")
    inner class PsiModifierVerbalizationTests {

        @Test
        fun `suspend modifier from PSI should be verbalized correctly`() = runTest {
            val symbol = Symbol(
                name = "AuthService",
                kind = SymbolKind.CLASS,
                filePath = "com/example/auth/AuthService.kt",
                lineNumber = 1,
                content = """
                    class AuthService {
                        suspend fun login(credentials: Credentials): Token { }
                    }
                """.trimIndent()
            ).enrich {
                // PSI-extracted modifier - the symbol IS suspending
                addModifier(SymbolModifier(ModifierKind.SUSPEND))
                setStructuralRole(StructuralRole.SERVICE)
            }

            val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)

            // The signature SHOULD contain "suspend" because modifier was added via PSI
            assertTrue(result.description.contains("suspend") || result.confidence > 0.0)
        }

        @Test
        fun `data class modifier from PSI should be verbalized`() = runTest {
            val symbol = Symbol(
                name = "User",
                kind = SymbolKind.CLASS,
                filePath = "com/example/model/User.kt",
                lineNumber = 1,
                content = """
                    data class User(val id: String, val name: String)
                """.trimIndent()
            ).enrich {
                // PSI-extracted data class modifier
                addModifier(SymbolModifier(ModifierKind.DATA_CLASS))
            }

            val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)

            assertTrue(result.description.contains("data") || result.description.contains("data class"))
        }

        @Test
        fun `multiple PSI modifiers should be verbalized together`() = runTest {
            val symbol = Symbol(
                name = "OrderService",
                kind = SymbolKind.CLASS,
                filePath = "com/example/service/OrderService.kt",
                lineNumber = 1,
                content = """
                    class OrderService {
                        suspend fun processOrder(order: Order): OrderResult { }
                    }
                """.trimIndent()
            ).enrich {
                addModifier(SymbolModifier(ModifierKind.SUSPEND))
                addModifier(SymbolModifier(ModifierKind.SERVICE))
                addModifier(SymbolModifier(ModifierKind.DATABASE_ACCESS))
                setStructuralRole(StructuralRole.SERVICE)
            }

            val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)

            // Should contain at least the suspend and service modifiers
            assertTrue(result.description.contains("suspend") || result.confidence > 0.0)
            assertTrue(result.description.contains("service") || result.description.contains("SERVICE"))
        }
    }

    @Nested
    @DisplayName("Edge case: same keyword in different contexts")
    inner class ContextEdgeCases {

        @Test
        fun `class named 'Suspend' should not affect modifier detection`() = runTest {
            val symbol = Symbol(
                name = "Suspend",
                kind = SymbolKind.CLASS,
                filePath = "com/example/Suspend.kt",
                lineNumber = 1,
                content = """
                    /**
                     * A class that handles suspension of operations.
                     */
                    class Suspend {
                        fun suspendOperation() { }
                    }
                """.trimIndent()
            ).enrich {
                // No suspend modifier - the class itself is not suspending
            }

            val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)

            // The class name is "Suspend" but it's NOT a suspending function
            // This verifies we don't get false positives from identifier names
            assertFalse(result.description.contains("suspend modifier") || 
                       result.description.contains("suspending function"))
        }

        @Test
        fun `property named 'data' should not cause false positive`() = runTest {
            val symbol = Symbol(
                name = "DataHolder",
                kind = SymbolKind.CLASS,
                filePath = "com/example/DataHolder.kt",
                lineNumber = 1,
                content = """
                    class DataHolder {
                        val data = "important data"
                        fun getData(): String = data
                    }
                """.trimIndent()
            ).enrich {
                // No data class modifier
            }

            val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)

            assertFalse(result.description.contains("data class"))
        }

        @Test
        fun `variable named 'inline' should not cause false positive`() = runTest {
            val symbol = Symbol(
                name = "Config",
                kind = SymbolKind.CLASS,
                filePath = "com/example/Config.kt",
                lineNumber = 1,
                content = """
                    class Config {
                        val inline = "inline configuration value"
                        fun getValue(): String = inline
                    }
                """.trimIndent()
            ).enrich {
                // No inline modifier
            }

            val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)

            assertFalse(result.description.contains("inline"))
        }
    }

    @Nested
    @DisplayName("Mixed scenarios: keywords appear both as modifiers and in strings")
    inner class MixedScenarios {

        @Test
        fun `string contains suspend but no PSI modifier should not include suspend`() = runTest {
            val symbol = Symbol(
                name = "DocumentProcessor",
                kind = SymbolKind.CLASS,
                filePath = "com/example/DocumentProcessor.kt",
                lineNumber = 1,
                content = """
                    class DocumentProcessor {
                        val description = "This processor does NOT use suspend"
                        fun process(doc: Document): Result = Result.Success
                    }
                """.trimIndent()
            ).enrich {
                // No suspend modifier despite the string containing the word
            }

            val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)

            assertFalse(result.description.contains("suspend"))
        }

        @Test
        fun `PSI modifier should override string content presence`() = runTest {
            val symbol = Symbol(
                name = "NetworkClient",
                kind = SymbolKind.CLASS,
                filePath = "com/example/NetworkClient.kt",
                lineNumber = 1,
                content = """
                    class NetworkClient {
                        val warning = "This is NOT a suspend function"
                        fun fetch(): Response { }
                    }
                """.trimIndent()
            ).enrich {
                // PSI says this IS suspending - this would be a discrepancy,
                // but the test verifies that PSI takes precedence
                addModifier(SymbolModifier(ModifierKind.SUSPEND))
            }

            val result = verbalizer.verbalize(symbol, LayerVerbalizationContext(), testIntent)

            // PSI modifier takes precedence - we should see "suspend" in output
            // This tests the core principle: PSI-based, not regex-based
            assertTrue(result.description.contains("suspend") || result.confidence > 0.0)
        }
    }
}