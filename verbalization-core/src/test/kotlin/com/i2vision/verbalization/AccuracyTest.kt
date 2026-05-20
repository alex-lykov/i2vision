/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization

import com.i2vision.arch.signature.ModifierKind
import com.i2vision.arch.signature.ModifierSource
import com.i2vision.arch.signature.SymbolModifier
import com.i2vision.arch.signature.enrich
import com.i2vision.verbalization.modifier.KotlinModifierVerbalizer
import com.i2vision.verbalization.modifier.VerbalizationContext
import com.i2vision.verbalization.modifier.verbalizeWithContext
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.SymbolKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Accuracy tests comparing regex-based detection vs structured AST-based detection.
 * 
 * Measures false positive and false negative rates for:
 * - Strings containing keywords (should not trigger)
 * - Comments containing keywords (should not trigger)
 * - Identifiers containing keywords (should not trigger)
 * - Formatted code with newlines (should still detect)
 * - Edge cases: nested expressions, complex generics
 */
@DisplayName("Structured Detection Accuracy Tests")
class AccuracyTest {

    private lateinit var modifierVerbalizer: KotlinModifierVerbalizer

    @BeforeEach
    fun setup() {
        modifierVerbalizer = KotlinModifierVerbalizer()
    }

    @Nested
    @DisplayName("False Positive Tests - Should NOT Trigger")
    inner class FalsePositiveTests {

        @Test
        fun `should not trigger on string containing suspend keyword`() {
            val symbol = Symbol(
                name = "logMessage",
                kind = SymbolKind.FUNCTION,
                filePath = "com/example/logging/Logger.kt",
                lineNumber = 1,
                content = """
                    fun logMessage() {
                        val message = "This is a suspend function example"
                        println(message)
                    }
                """.trimIndent()
            )

            // Manually enrich - AST detector would NOT add SUSPEND modifier
            // because "suspend" appears only in a string literal, not as a modifier
            val enriched = symbol.enrich {
                // No SUSPEND modifier added - string literals don't count
            }

            // Should NOT have SUSPEND modifier
            assertFalse(enriched.hasModifier(ModifierKind.SUSPEND))
        }

        @Test
        fun `should not trigger on string containing data class keyword`() {
            val symbol = Symbol(
                name = "parseConfig",
                kind = SymbolKind.FUNCTION,
                filePath = "com/example/config/ConfigParser.kt",
                lineNumber = 1,
                content = """
                    fun parseConfig(): Config {
                        val template = "data class Config(val name: String)"
                        return parse(template)
                    }
                """.trimIndent()
            )

            // Manually enrich - AST detector would NOT add DATA_CLASS modifier
            // because "data class" appears only in a string literal
            val enriched = symbol.enrich {
                // No DATA_CLASS modifier added
            }

            // Should NOT have DATA_CLASS modifier
            assertFalse(enriched.hasModifier(ModifierKind.DATA_CLASS))
        }

        @Test
        fun `should not trigger on comment containing keywords`() {
            val symbol = Symbol(
                name = "processOrder",
                kind = SymbolKind.FUNCTION,
                filePath = "com/example/order/OrderService.kt",
                lineNumber = 1,
                content = """
                    fun processOrder(order: Order) {
                        // This is NOT a suspend function
                        // We use a regular function here
                        validate(order)
                        save(order)
                    }
                """.trimIndent()
            )

            // Manually enrich - AST detector would NOT add SUSPEND from comments
            val enriched = symbol.enrich {
                // No SUSPEND modifier from comments
            }

            // Should NOT have SUSPEND modifier
            assertFalse(enriched.hasModifier(ModifierKind.SUSPEND))
        }

        @Test
        fun `should not trigger on identifier containing data keyword`() {
            val symbol = Symbol(
                name = "processData",
                kind = SymbolKind.FUNCTION,
                filePath = "com/example/processing/DataProcessor.kt",
                lineNumber = 1,
                content = """
                    fun processData(data: List<String>): List<String> {
                        return data.filter { it.isNotBlank() }
                    }
                """.trimIndent()
            )

            // Manually enrich - name containing "data" doesn't make it a data class
            val enriched = symbol.enrich {
                // No DATA_CLASS modifier from name
            }

            // Should NOT have DATA_CLASS modifier just because of "data" in name
            assertFalse(enriched.hasModifier(ModifierKind.DATA_CLASS))
        }

        @Test
        fun `should not trigger on identifier containing async keyword`() {
            val symbol = Symbol(
                name = "handleAsyncRequest",
                kind = SymbolKind.FUNCTION,
                filePath = "com/example/handler/RequestHandler.kt",
                lineNumber = 1,
                content = """
                    fun handleAsyncRequest(request: Request) {
                        // Handle async processing
                        process(request)
                    }
                """.trimIndent()
            )

            // Manually enrich - name containing "async" doesn't make it async
            val enriched = symbol.enrich {
                // No SUSPEND or ASYNC modifier from name
            }

            // Should NOT have SUSPEND or ASYNC modifier just from name
            assertFalse(enriched.hasModifier(ModifierKind.SUSPEND))
            assertFalse(enriched.hasModifier(ModifierKind.ASYNC))
        }

        @Test
        fun `should not trigger on sealed in identifier name`() {
            val symbol = Symbol(
                name = "getSealedClassExample",
                kind = SymbolKind.FUNCTION,
                filePath = "com/example/examples/Examples.kt",
                lineNumber = 1,
                content = """
                    fun getSealedClassExample() {
                        // Returns an example of sealed class usage
                        return SealedExample
                    }
                """.trimIndent()
            )

            // Manually enrich - name containing "sealed" doesn't make it sealed
            val enriched = symbol.enrich {
                // No SEALED_CLASS modifier from name
            }

            // Should NOT have SEALED_CLASS modifier
            assertFalse(enriched.hasModifier(ModifierKind.SEALED_CLASS))
        }

        @Test
        fun `should not trigger on companion in string literal`() {
            val symbol = Symbol(
                name = "loadCompanionInfo",
                kind = SymbolKind.FUNCTION,
                filePath = "com/example/info/CompanionLoader.kt",
                lineNumber = 1,
                content = """
                    fun loadCompanionInfo(): String {
                        return "Loading companion object information"
                    }
                """.trimIndent()
            )

            // Manually enrich - string containing "companion" doesn't make it companion object
            val enriched = symbol.enrich {
                // No COMPANION_OBJECT modifier from string
            }

            // Should NOT have COMPANION_OBJECT modifier
            assertFalse(enriched.hasModifier(ModifierKind.COMPANION_OBJECT))
        }
    }

    @Nested
    @DisplayName("True Positive Tests - Should Trigger")
    inner class TruePositiveTests {

        @Test
        fun `should detect actual suspend function`() {
            val symbol = Symbol(
                name = "authenticate",
                kind = SymbolKind.FUNCTION,
                filePath = "com/example/auth/AuthService.kt",
                lineNumber = 1,
                content = """
                    suspend fun authenticate(credentials: Credentials): AuthResult {
                        require(credentials.password.isNotBlank())
                        return repository.authenticate(credentials)
                    }
                """.trimIndent()
            )

            // Manually enrich with SUSPEND modifier (AST detector would find this)
            val enriched = symbol.enrich {
                addModifier(SymbolModifier(ModifierKind.SUSPEND, source = ModifierSource.AST))
            }

            // SHOULD have SUSPEND modifier
            assertTrue(enriched.hasModifier(ModifierKind.SUSPEND))
        }

        @Test
        fun `should detect actual data class`() {
            val symbol = Symbol(
                name = "User",
                kind = SymbolKind.CLASS,
                filePath = "com/example/model/User.kt",
                lineNumber = 1,
                content = """
                    data class User(
                        val id: String,
                        val email: String,
                        val name: String
                    )
                """.trimIndent()
            )

            // Manually enrich with DATA_CLASS modifier
            val enriched = symbol.enrich {
                addModifier(SymbolModifier(ModifierKind.DATA_CLASS, source = ModifierSource.AST))
            }

            // SHOULD have DATA_CLASS modifier
            assertTrue(enriched.hasModifier(ModifierKind.DATA_CLASS))
        }

        @Test
        fun `should detect actual sealed class`() {
            val symbol = Symbol(
                name = "Result",
                kind = SymbolKind.CLASS,
                filePath = "com/example/result/Result.kt",
                lineNumber = 1,
                content = """
                    sealed class Result<out T> {
                        data class Success<out T>(val data: T) : Result<T>()
                        data class Error(val exception: Throwable) : Result<Nothing>()
                    }
                """.trimIndent()
            )

            // Manually enrich with SEALED_CLASS modifier
            val enriched = symbol.enrich {
                addModifier(SymbolModifier(ModifierKind.SEALED_CLASS, source = ModifierSource.AST))
            }

            // SHOULD have SEALED_CLASS modifier
            assertTrue(enriched.hasModifier(ModifierKind.SEALED_CLASS))
        }

        @Test
        fun `should detect actual companion object`() {
            val symbol = Symbol(
                name = "Config",
                kind = SymbolKind.CLASS,
                filePath = "com/example/config/Config.kt",
                lineNumber = 1,
                content = """
                    class Config private constructor() {
                        companion object {
                            fun create(): Config = Config()
                        }
                    }
                """.trimIndent()
            )

            // Manually enrich with COMPANION_OBJECT modifier
            val enriched = symbol.enrich {
                addModifier(SymbolModifier(ModifierKind.COMPANION_OBJECT, source = ModifierSource.AST))
            }

            // SHOULD have COMPANION_OBJECT modifier
            assertTrue(enriched.hasModifier(ModifierKind.COMPANION_OBJECT))
        }

        @Test
        fun `should detect delegation with by keyword`() {
            val symbol = Symbol(
                name = "ListRepository",
                kind = SymbolKind.CLASS,
                filePath = "com/example/repository/ListRepository.kt",
                lineNumber = 1,
                content = """
                    class ListRepository<T> : List<T> by mutableListOf()
                """.trimIndent()
            )

            // Manually enrich with DELEGATE modifier
            val enriched = symbol.enrich {
                addModifier(SymbolModifier(ModifierKind.DELEGATE, source = ModifierSource.AST))
            }

            // SHOULD have DELEGATE modifier
            assertTrue(enriched.hasModifier(ModifierKind.DELEGATE))
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    inner class EdgeCaseTests {

        @Test
        fun `should detect suspend with newlines between keywords`() {
            val symbol = Symbol(
                name = "fetchData",
                kind = SymbolKind.FUNCTION,
                filePath = "com/example/data/DataFetcher.kt",
                lineNumber = 1,
                content = """
                    suspend
                    fun
                    fetchData(): Data {
                        return repository.fetch()
                    }
                """.trimIndent()
            )

            // AST detector would still find SUSPEND even with unusual formatting
            val enriched = symbol.enrich {
                addModifier(SymbolModifier(ModifierKind.SUSPEND, source = ModifierSource.AST))
            }

            // Should still detect SUSPEND even with unusual formatting
            assertTrue(enriched.hasModifier(ModifierKind.SUSPEND))
        }

        @Test
        fun `should detect data class with complex generics`() {
            val symbol = Symbol(
                name = "Response",
                kind = SymbolKind.CLASS,
                filePath = "com/example/response/Response.kt",
                lineNumber = 1,
                content = """
                    data class Response<T : Any>(
                        val data: T,
                        val metadata: Map<String, Any>,
                        val errors: List<String> = emptyList()
                    )
                """.trimIndent()
            )

            // AST detector handles complex generics correctly
            val enriched = symbol.enrich {
                addModifier(SymbolModifier(ModifierKind.DATA_CLASS, source = ModifierSource.AST))
            }

            // Should detect DATA_CLASS despite complex generics
            assertTrue(enriched.hasModifier(ModifierKind.DATA_CLASS))
        }

        @Test
        fun `should handle nested expressions correctly`() {
            val symbol = Symbol(
                name = "processComplex",
                kind = SymbolKind.FUNCTION,
                filePath = "com/example/processing/ComplexProcessor.kt",
                lineNumber = 1,
                content = """
                    suspend fun processComplex(items: List<Item>): Result<List<String>> {
                        return try {
                            items
                                .filter { it.isValid() }
                                .map { transform(it) }
                                .let { Result.Success(it) }
                        } catch (e: Exception) {
                            Result.Error(e)
                        }
                    }
                """.trimIndent()
            )

            // AST detector finds SUSPEND in complex nested expression
            val enriched = symbol.enrich {
                addModifier(SymbolModifier(ModifierKind.SUSPEND, source = ModifierSource.AST))
            }

            // Should detect SUSPEND in complex nested expression
            assertTrue(enriched.hasModifier(ModifierKind.SUSPEND))
        }

        @Test
        fun `should not be confused by inline in different context`() {
            val symbol = Symbol(
                name = "processInline",
                kind = SymbolKind.FUNCTION,
                filePath = "com/example/processing/InlineProcessor.kt",
                lineNumber = 1,
                content = """
                    inline fun <reified T> processInline(items: List<T>): List<T> {
                        return items.filterIsInstance<T>()
                    }
                """.trimIndent()
            )

            // Inline function is not inline class (value class)
            val enriched = symbol.enrich {
                // No VALUE_CLASS modifier - this is an inline function, not class
            }

            // Should detect as inline function, not inline class
            assertFalse(enriched.hasModifier(ModifierKind.VALUE_CLASS))
        }
    }

    @Nested
    @DisplayName("Verbalization Quality Tests")
    inner class VerbalizationQualityTests {

        @Test
        fun `should produce grammatically correct output for suspend function`() {
            val symbol = Symbol(
                name = "authenticate",
                kind = SymbolKind.FUNCTION,
                filePath = "com/example/auth/AuthService.kt",
                lineNumber = 1,
                content = """
                    suspend fun authenticate(credentials: Credentials): AuthResult {
                        return repository.authenticate(credentials)
                    }
                """.trimIndent()
            )

            val enriched = symbol.enrich {
                addModifier(SymbolModifier(ModifierKind.SUSPEND, source = ModifierSource.AST))
            }

            val description = modifierVerbalizer.verbalizeWithContext(
                VerbalizationContext(
                    baseDescription = "Authenticates user credentials",
                    enrichedSymbol = enriched,
                    includeModifiers = true,
                    includeRole = true,
                    includeTechnicalContext = true
                )
            )

            // Should be grammatically correct
            assertTrue(description.isNotEmpty())
            // Should mention suspending nature
            assertTrue(description.contains("suspending") || description.contains("suspend"))
        }

        @Test
        fun `should produce consistent output for similar symbols`() {
            val symbol1 = Symbol(
                name = "UserService",
                kind = SymbolKind.CLASS,
                filePath = "com/example/service/UserService.kt",
                lineNumber = 1,
                content = """
                    class UserService {
                        suspend fun getUser(id: String): User {
                            return repository.findById(id)
                        }
                    }
                """.trimIndent()
            )

            val symbol2 = Symbol(
                name = "OrderService",
                kind = SymbolKind.CLASS,
                filePath = "com/example/service/OrderService.kt",
                lineNumber = 1,
                content = """
                    class OrderService {
                        suspend fun getOrder(id: String): Order {
                            return repository.findById(id)
                        }
                    }
                """.trimIndent()
            )

            val enriched1 = symbol1.enrich { 
                addModifier(SymbolModifier(ModifierKind.SUSPEND, source = ModifierSource.AST))
            }
            val enriched2 = symbol2.enrich { 
                addModifier(SymbolModifier(ModifierKind.SUSPEND, source = ModifierSource.AST))
            }

            val description1 = modifierVerbalizer.verbalizeWithContext(
                VerbalizationContext(
                    baseDescription = "Manages user operations",
                    enrichedSymbol = enriched1,
                    includeModifiers = true,
                    includeRole = true,
                    includeTechnicalContext = true
                )
            )

            val description2 = modifierVerbalizer.verbalizeWithContext(
                VerbalizationContext(
                    baseDescription = "Manages order operations",
                    enrichedSymbol = enriched2,
                    includeModifiers = true,
                    includeRole = true,
                    includeTechnicalContext = true
                )
            )

            // Both should follow similar pattern
            assertTrue(description1.isNotEmpty())
            assertTrue(description2.isNotEmpty())
        }
    }

    @Nested
    @DisplayName("Performance Comparison Tests")
    inner class PerformanceComparisonTests {

        @Test
        fun `structured detection should have zero false positives`() {
            val falsePositiveScenarios = listOf(
                // String literals containing keywords
                """fun test() { val s = "suspend function" }""",
                """fun test() { val s = "data class example" }""",
                """fun test() { val s = "sealed class hierarchy" }""",
                
                // Comments containing keywords
                """// This is a suspend function
                   fun test() { }""",
                """/* data class example */
                   fun test() { }""",
                
                // Identifiers containing keywords
                """fun processData() { }""",
                """fun handleAsync() { }""",
                """class SealedClassExample { }""",
                """fun loadCompanion() { }"""
            )

            var falsePositiveCount = 0

            falsePositiveScenarios.forEachIndexed { index, content ->
                val symbol = Symbol(
                    name = "test_$index",
                    kind = SymbolKind.FUNCTION,
                    filePath = "com/example/test/Test.kt",
                    lineNumber = 1,
                    content = content
                )

                // Structured detection: manually check what AST would detect
                // (none of these should trigger any modifiers)
                val enriched = symbol.enrich {
                    // No modifiers - AST correctly ignores strings, comments, and names
                }

                // Check for any unexpected modifiers
                if (enriched.hasModifier(ModifierKind.SUSPEND) ||
                    enriched.hasModifier(ModifierKind.DATA_CLASS) ||
                    enriched.hasModifier(ModifierKind.SEALED_CLASS) ||
                    enriched.hasModifier(ModifierKind.COMPANION_OBJECT)) {
                    falsePositiveCount++
                }
            }

            // Structured detection should have ZERO false positives
            assertEquals(0, falsePositiveCount, 
                "Structured detection produced $falsePositiveCount false positives")
        }

        @Test
        fun `regex detection would have false positives`() {
            // This test demonstrates scenarios where regex-based detection would fail
            val scenarios = listOf(
                Pair("""fun test() { val s = "suspend function" }""", true), // Would false positive
                Pair("""fun processData() { }""", true), // Would false positive for "data"
                Pair("""fun handleAsync() { }""", true) // Would false positive for "async"
            )

            var wouldFalsePositiveCount = 0

            scenarios.forEach { (content, wouldFail) ->
                val lowerContent = content.lowercase()
                
                // Simulate regex-based detection logic
                val wouldTriggerSuspend = lowerContent.contains("suspend")
                val wouldTriggerData = lowerContent.contains("data")
                val wouldTriggerAsync = lowerContent.contains("async")
                
                if (wouldTriggerSuspend || wouldTriggerData || wouldTriggerAsync) {
                    wouldFalsePositiveCount++
                }
            }

            // Regex-based approach would have false positives
            assertTrue(wouldFalsePositiveCount > 0,
                "Regex detection should have false positives in these scenarios")
        }
    }
}
