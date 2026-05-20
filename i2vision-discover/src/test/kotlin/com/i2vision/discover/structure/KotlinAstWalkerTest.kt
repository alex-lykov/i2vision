/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.structure

import com.i2vision.arch.signature.ModifierKind
import com.i2vision.arch.signature.ModifierSource
import com.i2vision.arch.signature.StructuralRole
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for KotlinAstWalker — validates that AST-based modifier extraction
 * works correctly without regex matching on raw source content.
 *
 * Key test scenarios:
 * - Suspend modifier detection from PSI
 * - Data class, value class, sealed class detection
 * - Companion object detection
 * - Delegation (by keyword) detection
 * - Extension function detection
 * - Structural role detection from annotations and naming
 * - Technical context extraction
 * - Edge cases: strings containing keywords, comments containing keywords
 */
class KotlinAstWalkerTest {

    private val walker = KotlinAstWalker()

    private fun withTempDir(block: (File) -> Unit) {
        val tempDir = java.nio.file.Files.createTempDirectory("ast-walker-test").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createKtFile(dir: File, name: String, content: String): File {
        val file = File(dir, name)
        file.parentFile.mkdirs()
        file.writeText(content)
        return file
    }

    // ── Suspend modifier detection ────────────────────────────────────────────

    @Test
    fun `should extract suspend modifier from function`() = withTempDir { dir ->
        val file = createKtFile(dir, "SuspendService.kt", """
            package com.example
            
            suspend fun fetchData(): String {
                return "data"
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val suspendFn = result.enrichedSymbols.find { it.symbol.name == "fetchData" }
        assertNotNull(suspendFn, "Should find fetchData function")
        assertTrue(
            suspendFn.hasModifier(ModifierKind.SUSPEND),
            "Should detect suspend modifier"
        )
        assertEquals(ModifierSource.AST, suspendFn.modifiers.first { it.kind == ModifierKind.SUSPEND }.source)
    }

    @Test
    fun `should not detect suspend in string content`() = withTempDir { dir ->
        // This is the key test: regex would falsely detect "suspend" in the string,
        // but PSI-based detection should NOT
        val file = createKtFile(dir, "StringService.kt", """
            package com.example
            
            fun describe(): String {
                return "This function uses suspend keyword in a string"
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val describeFn = result.enrichedSymbols.find { it.symbol.name == "describe" }
        assertNotNull(describeFn, "Should find describe function")
        // Should NOT have SUSPEND modifier because "suspend" is in a string, not a modifier
        assertTrue(
            describeFn.modifiers.none { it.kind == ModifierKind.SUSPEND },
            "Should NOT detect suspend from string content"
        )
    }

    @Test
    fun `should not detect suspend in comment`() = withTempDir { dir ->
        // Regex would falsely detect "suspend" in a comment,
        // but PSI-based detection should NOT
        val file = createKtFile(dir, "CommentService.kt", """
            package com.example
            
            // This is a suspend function that does something
            fun regularFunction(): Int {
                return 42
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val regularFn = result.enrichedSymbols.find { it.symbol.name == "regularFunction" }
        assertNotNull(regularFn, "Should find regularFunction")
        assertTrue(
            regularFn.modifiers.none { it.kind == ModifierKind.SUSPEND },
            "Should NOT detect suspend from comment"
        )
    }

    // ── Data class detection ──────────────────────────────────────────────────

    @Test
    fun `should extract data class modifier`() = withTempDir { dir ->
        val file = createKtFile(dir, "Models.kt", """
            package com.example
            
            data class User(val name: String, val age: Int)
        """.trimIndent())

        val result = walker.walkFile(file)
        val userClass = result.enrichedSymbols.find { it.symbol.name == "User" }
        assertNotNull(userClass, "Should find User class")
        assertTrue(
            userClass.hasModifier(ModifierKind.DATA_CLASS),
            "Should detect data class modifier"
        )
    }

    @Test
    fun `should not detect data class from string content`() = withTempDir { dir ->
        // Regex would match "data class" in a string, PSI should not
        val file = createKtFile(dir, "Description.kt", """
            package com.example
            
            class Description {
                fun getText(): String {
                    return "This is a data class example"
                }
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val descClass = result.enrichedSymbols.find { it.symbol.name == "Description" }
        assertNotNull(descClass, "Should find Description class")
        assertTrue(
            descClass.modifiers.none { it.kind == ModifierKind.DATA_CLASS },
            "Should NOT detect data class from string content"
        )
    }

    // ── Sealed class detection ─────────────────────────────────────────────────

    @Test
    fun `should extract sealed class modifier`() = withTempDir { dir ->
        val file = createKtFile(dir, "Result.kt", """
            package com.example
            
            sealed class Result {
                data class Success(val value: String) : Result()
                data class Error(val message: String) : Result()
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val resultClass = result.enrichedSymbols.find { it.symbol.name == "Result" }
        assertNotNull(resultClass, "Should find Result class")
        assertTrue(
            resultClass.hasModifier(ModifierKind.SEALED_CLASS),
            "Should detect sealed class modifier"
        )
    }

    // ── Value class detection ─────────────────────────────────────────────────

    @Test
    fun `should extract value class (inline class) modifier`() = withTempDir { dir ->
        val file = createKtFile(dir, "Types.kt", """
            package com.example
            
            @JvmInline
            value class UserId(val value: String)
        """.trimIndent())

        val result = walker.walkFile(file)
        val userIdClass = result.enrichedSymbols.find { it.symbol.name == "UserId" }
        assertNotNull(userIdClass, "Should find UserId class")
        assertTrue(
            userIdClass.hasModifier(ModifierKind.VALUE_CLASS),
            "Should detect value class modifier"
        )
    }

    // ── Companion object detection ────────────────────────────────────────────

    @Test
    fun `should extract companion object modifier`() = withTempDir { dir ->
        val file = createKtFile(dir, "Factory.kt", """
            package com.example
            
            class Factory {
                companion object {
                    fun create(): Factory = Factory()
                }
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val companionObj = result.enrichedSymbols.find { it.symbol.name == "Companion" }
        assertNotNull(companionObj, "Should find Companion object")
        assertTrue(
            companionObj.hasModifier(ModifierKind.COMPANION_OBJECT),
            "Should detect companion object modifier"
        )
    }

    // ── Extension function detection ──────────────────────────────────────────

    @Test
    fun `should extract extension function modifier`() = withTempDir { dir ->
        val file = createKtFile(dir, "Extensions.kt", """
            package com.example
            
            fun String.toSlug(): String {
                return this.lowercase().replace(" ", "-")
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val toSlugFn = result.enrichedSymbols.find { it.symbol.name == "toSlug" }
        assertNotNull(toSlugFn, "Should find toSlug function")
        assertTrue(
            toSlugFn.hasModifier(ModifierKind.EXTENSION),
            "Should detect extension function modifier"
        )
    }

    // ── Delegation detection ──────────────────────────────────────────────────

    @Test
    fun `should extract delegation modifier`() = withTempDir { dir ->
        val file = createKtFile(dir, "Delegated.kt", """
            package com.example
            
            interface Printable {
                fun print()
            }
            
            class DelegatedPrinter(private val delegate: Printable) : Printable by delegate
        """.trimIndent())

        val result = walker.walkFile(file)
        val delegatedClass = result.enrichedSymbols.find { it.symbol.name == "DelegatedPrinter" }
        assertNotNull(delegatedClass, "Should find DelegatedPrinter class")
        assertTrue(
            delegatedClass.hasModifier(ModifierKind.DELEGATE),
            "Should detect delegation modifier"
        )
    }

    // ── Structural role detection ─────────────────────────────────────────────

    @Test
    fun `should detect repository structural role from naming`() = withTempDir { dir ->
        val file = createKtFile(dir, "UserRepository.kt", """
            package com.example
            
            class UserRepository {
                fun findById(id: String): User? = null
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val repoClass = result.enrichedSymbols.find { it.symbol.name == "UserRepository" }
        assertNotNull(repoClass, "Should find UserRepository class")
        assertEquals(StructuralRole.REPOSITORY, repoClass.structuralRole)
    }

    @Test
    fun `should detect controller structural role from annotation`() = withTempDir { dir ->
        val file = createKtFile(dir, "ApiController.kt", """
            package com.example
            
            @RestController
            class ApiController {
                fun getItems(): List<String> = emptyList()
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val controllerClass = result.enrichedSymbols.find { it.symbol.name == "ApiController" }
        assertNotNull(controllerClass, "Should find ApiController class")
        assertEquals(StructuralRole.CONTROLLER, controllerClass.structuralRole)
    }

    @Test
    fun `should detect service structural role from annotation`() = withTempDir { dir ->
        val file = createKtFile(dir, "PaymentService.kt", """
            package com.example
            
            @Service
            class PaymentService {
                fun processPayment(amount: Double): Boolean = true
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val serviceClass = result.enrichedSymbols.find { it.symbol.name == "PaymentService" }
        assertNotNull(serviceClass, "Should find PaymentService class")
        assertEquals(StructuralRole.SERVICE, serviceClass.structuralRole)
    }

    // ── Technical context extraction ───────────────────────────────────────────

    @Test
    fun `should extract technical context with database access`() = withTempDir { dir ->
        val file = createKtFile(dir, "OrderRepository.kt", """
            package com.example
            
            @Repository
            class OrderRepository {
                fun findOrders(): List<Order> {
                    val entityManager = getEntityManager()
                    return entityManager.createQuery("SELECT o FROM Order o").resultList
                }
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val repoClass = result.enrichedSymbols.find { it.symbol.name == "OrderRepository" }
        assertNotNull(repoClass, "Should find OrderRepository class")
        assertTrue(
            repoClass.technicalContext.hasDatabaseAccess,
            "Should detect database access"
        )
    }

    @Test
    fun `should extract technical context with async annotation`() = withTempDir { dir ->
        val file = createKtFile(dir, "AsyncService.kt", """
            package com.example
            
            @Service
            class AsyncService {
                @Async
                suspend fun processData(): String {
                    return "processed"
                }
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val asyncFn = result.enrichedSymbols.find { it.symbol.name == "processData" }
        assertNotNull(asyncFn, "Should find processData function")
        assertTrue(
            asyncFn.hasModifier(ModifierKind.SUSPEND),
            "Should detect suspend modifier"
        )
    }

    // ── Package extraction ────────────────────────────────────────────────────

    @Test
    fun `should extract package name from file`() = withTempDir { dir ->
        val file = createKtFile(dir, "Service.kt", """
            package com.example.service
            
            class Service
        """.trimIndent())

        val result = walker.walkFile(file)
        assertEquals("com.example.service", result.packageName)
    }

    @Test
    fun `should handle file without package declaration`() = withTempDir { dir ->
        val file = createKtFile(dir, "NoPackage.kt", """
            class NoPackageClass
        """.trimIndent())

        val result = walker.walkFile(file)
        assertEquals("", result.packageName)
    }

    // ── Multiple declarations in one file ──────────────────────────────────────

    @Test
    fun `should extract all declarations from file`() = withTempDir { dir ->
        val file = createKtFile(dir, "Mixed.kt", """
            package com.example
            
            data class DataModel(val id: String)
            
            sealed class Event {
                data class Click(val x: Int, val y: Int) : Event()
                data class KeyPress(val key: String) : Event()
            }
            
            suspend fun processEvent(event: Event): Boolean = true
            
            fun String.toUpper(): String = this.uppercase()
        """.trimIndent())

        val result = walker.walkFile(file)
        assertTrue(result.enrichedSymbols.size >= 3, "Should find at least 3 declarations")

        val dataModel = result.enrichedSymbols.find { it.symbol.name == "DataModel" }
        assertNotNull(dataModel)
        assertTrue(dataModel.hasModifier(ModifierKind.DATA_CLASS))

        val eventClass = result.enrichedSymbols.find { it.symbol.name == "Event" }
        assertNotNull(eventClass)
        assertTrue(eventClass.hasModifier(ModifierKind.SEALED_CLASS))

        val processFn = result.enrichedSymbols.find { it.symbol.name == "processEvent" }
        assertNotNull(processFn)
        assertTrue(processFn.hasModifier(ModifierKind.SUSPEND))
    }

    // ── Edge case: keyword in string should not be detected ────────────────────

    @Test
    fun `should not detect data class from comment`() = withTempDir { dir ->
        val file = createKtFile(dir, "CommentedClass.kt", """
            package com.example
            
            // This is NOT a data class, it's a regular class
            class CommentedClass {
                fun describe(): String = "I am not a data class"
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val commentedClass = result.enrichedSymbols.find { it.symbol.name == "CommentedClass" }
        assertNotNull(commentedClass, "Should find CommentedClass")
        assertTrue(
            commentedClass.modifiers.none { it.kind == ModifierKind.DATA_CLASS },
            "Should NOT detect data class from comment"
        )
    }

    // ── File type detection ────────────────────────────────────────────────────

    @Test
    fun `should identify Kotlin files correctly`() {
        val ktFile = File("Test.kt")
        val ktsFile = File("Script.kts")
        val javaFile = File("Test.java")
        val txtFile = File("Test.txt")

        assertTrue(KotlinAstWalker.isKotlinFile(ktFile))
        assertTrue(KotlinAstWalker.isKotlinFile(ktsFile))
        assertTrue(KotlinAstWalker.isJavaFile(javaFile))
        assertTrue(!KotlinAstWalker.isKotlinFile(txtFile))
        assertTrue(!KotlinAstWalker.isJavaFile(ktFile))
    }

    // ── Walk multiple files ────────────────────────────────────────────────────

    @Test
    fun `should walk multiple files`() = withTempDir { dir ->
        val file1 = createKtFile(dir, "Model.kt", """
            package com.example.model
            data class Model(val id: String)
        """.trimIndent())

        val file2 = createKtFile(dir, "Service.kt", """
            package com.example.service
            suspend fun fetchModel(): Model = Model("1")
        """.trimIndent())

        val results = walker.walkFiles(listOf(file1, file2))
        assertEquals(2, results.size)

        val modelResult = results.find { it.enrichedSymbols.any { s -> s.symbol.name == "Model" } }
        assertNotNull(modelResult)
        val model = modelResult.enrichedSymbols.find { it.symbol.name == "Model" }!!
        assertTrue(model.hasModifier(ModifierKind.DATA_CLASS))

        val serviceResult = results.find { it.enrichedSymbols.any { s -> s.symbol.name == "fetchModel" } }
        assertNotNull(serviceResult)
        val fetchFn = serviceResult!!.enrichedSymbols.find { it.symbol.name == "fetchModel" }!!
        assertTrue(fetchFn.hasModifier(ModifierKind.SUSPEND))
    }

    // ── Annotation-based modifier detection ────────────────────────────────────

    @Test
    fun `should detect repository annotation modifier`() = withTempDir { dir ->
        val file = createKtFile(dir, "JpaRepo.kt", """
            package com.example
            
            @Repository
            class JpaRepo {
                fun findAll(): List<Any> = emptyList()
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val repo = result.enrichedSymbols.find { it.symbol.name == "JpaRepo" }
        assertNotNull(repo)
        assertTrue(repo.hasModifier(ModifierKind.REPOSITORY))
        assertEquals(ModifierSource.ANNOTATION, repo.modifiers.first { it.kind == ModifierKind.REPOSITORY }.source)
    }

    @Test
    fun `should detect controller annotation modifier`() = withTempDir { dir ->
        val file = createKtFile(dir, "ApiCtrl.kt", """
            package com.example
            
            @RestController
            class ApiCtrl {
                fun handle(): String = "ok"
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val ctrl = result.enrichedSymbols.find { it.symbol.name == "ApiCtrl" }
        assertNotNull(ctrl)
        assertTrue(ctrl.hasModifier(ModifierKind.CONTROLLER))
    }

    // ── Combined modifiers ────────────────────────────────────────────────────

    @Test
    fun `should detect multiple modifiers on same declaration`() = withTempDir { dir ->
        val file = createKtFile(dir, "ComplexService.kt", """
            package com.example
            
            @Service
            class ComplexService {
                @Async
                suspend fun processData(): String = "done"
            }
        """.trimIndent())

        val result = walker.walkFile(file)
        val processFn = result.enrichedSymbols.find { it.symbol.name == "processData" }
        assertNotNull(processFn)
        assertTrue(processFn.hasModifier(ModifierKind.SUSPEND), "Should have SUSPEND modifier")
    }
}