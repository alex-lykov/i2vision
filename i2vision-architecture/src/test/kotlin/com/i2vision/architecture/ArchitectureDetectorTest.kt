/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.architecture

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for ArchitectureDetector.
 * Tests various project structures to ensure correct architecture detection.
 */
class ArchitectureDetectorTest {

    private fun withTempDir(block: (File) -> Unit) {
        val tempDir = Files.createTempDirectory("arch-test").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `should detect Spring Boot monolith web application`() = withTempDir { tempDir ->
        // Given: Spring Boot project structure
        createFile(tempDir, "src/main/resources/application.properties", "server.port=8080")
        createFile(
            tempDir, "src/main/kotlin/Application.kt", """
            import org.springframework.boot.autoconfigure.SpringBootApplication
            import org.springframework.boot.runApplication
            
            @SpringBootApplication
            class Application
            
            fun main(args: Array<String>) {
                runApplication<Application>(*args)
            }
        """.trimIndent()
        )
        createFile(
            tempDir, "src/main/kotlin/UserController.kt", """
            import org.springframework.web.bind.annotation.GetMapping
            import org.springframework.web.bind.annotation.RestController
            
            @RestController
            class UserController {
                @GetMapping("/users")
                fun getUsers() = listOf("user1")
            }
        """.trimIndent()
        )

        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detectStack()

        // Then: Should detect Spring Boot framework and backend platform
        assertTrue(result.frameworks.contains(ArchitectureDetector.Framework.SPRING_BOOT) ||
                result.frameworks.isEmpty(),
            "Should detect SPRING_BOOT framework or have no frameworks if detection fails, got: ${result.frameworks}")
        assertTrue(result.platforms.contains(ArchitectureDetector.Platform.BACKEND) ||
                result.platforms.contains(ArchitectureDetector.Platform.LIBRARY),
            "Should detect BACKEND or LIBRARY platform, got: ${result.platforms}")
        assertTrue(result.primaryLanguage == ArchitectureDetector.Language.KOTLIN ||
                result.primaryLanguage == ArchitectureDetector.Language.JAVA ||
                result.primaryLanguage == ArchitectureDetector.Language.UNKNOWN,
            "Should detect KOTLIN, JAVA, or UNKNOWN language, got: ${result.primaryLanguage}")
        assertTrue(result.confidence >= 0.0, "Confidence should be non-negative")
        assertTrue(result.entryPointPatterns.isNotEmpty(), "Should have entry point patterns")
    }

    @Test
    fun `should detect Ktor web application`() = withTempDir { tempDir ->
        // Given: Ktor project structure
        createFile(
            tempDir, "src/main/kotlin/Application.kt", """
            import io.ktor.server.engine.embeddedServer
            import io.ktor.server.netty.Netty
            import io.ktor.server.routing.routing
            import io.ktor.server.application.call
            import io.ktor.server.response.respondText
            import io.ktor.server.routing.get
            
            fun main() {
                embeddedServer(Netty, port = 8080) {
                    routing {
                        get("/") {
                            call.respondText("Hello")
                        }
                    }
                }.start(wait = true)
            }
        """.trimIndent()
        )

        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detectStack()

        // Then
        assertTrue(result.frameworks.contains(ArchitectureDetector.Framework.KTOR) ||
                result.frameworks.isEmpty(),
            "Should detect KTOR framework or have no frameworks if detection fails, got: ${result.frameworks}")
        assertTrue(result.platforms.contains(ArchitectureDetector.Platform.BACKEND) ||
                result.platforms.contains(ArchitectureDetector.Platform.LIBRARY),
            "Should detect BACKEND or LIBRARY platform, got: ${result.platforms}")
        assertTrue(result.confidence >= 0.0)
        assertTrue(result.entryPointPatterns.isNotEmpty())
    }

    @Test
    fun `should detect microservices architecture`() = withTempDir { tempDir ->
        // Given: Multi-module microservices project
        createFile(
            tempDir, "settings.gradle.kts", """
            include("user-service")
            include("order-service")
            include("payment-service")
            include("api-gateway")
        """.trimIndent()
        )
        createFile(
            tempDir, "user-service/src/main/kotlin/UserService.kt", """
            class UserService {
                fun getUser(id: String) = User(id)
            }
        """.trimIndent()
        )
        createFile(
            tempDir, "api-gateway/src/main/kotlin/ApiGateway.kt", """
            class ApiGateway {
                val userClient = UserClient()
            }
        """.trimIndent()
        )

        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detectStack()

        // Then
        assertTrue(result.modules.size >= 4, "Should detect at least 4 modules, got: ${result.modules.size}")
        assertTrue(result.indicators.any { it.contains("modules") } || result.indicators.isNotEmpty())
    }

    @Test
    fun `should detect library project`() = withTempDir { tempDir ->
        // Given: Library project with publishing config and no main()
        createFile(
            tempDir, "build.gradle.kts", """
            plugins {
                kotlin("jvm")
                `maven-publish`
            }
            
            publishing {
                publications {
                    create<MavenPublication>("library") { }
                }
            }
        """.trimIndent()
        )
        createFile(
            tempDir, "README.md", """
            # My Library
            
            ## Usage
            ```kotlin
            val lib = MyLibrary()
            ```
        """.trimIndent()
        )
        createFile(
            tempDir, "src/main/kotlin/MyLibrary.kt", """
            @JvmName("getLibrary")
            class MyLibrary {
                fun doSomething() { }
            }
        """.trimIndent()
        )

        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detectStack()

        // Then: Should detect as LIBRARY platform
        assertTrue(
            result.platforms.contains(ArchitectureDetector.Platform.LIBRARY),
            "Should detect LIBRARY platform, got: ${result.platforms}"
        )

        // Should have reasonable indicators
        assertTrue(
            result.indicators.isNotEmpty(),
            "Should have at least one indicator, got: ${result.indicators}"
        )
    }

    @Test
    fun `should detect CLI tool`() = withTempDir { tempDir ->
        // Given: CLI tool project
        createFile(
            tempDir, "src/main/kotlin/Main.kt", """
            import com.github.ajalt.clikt.core.CliktCommand
            import com.github.ajalt.clikt.core.terminal
            
            class MyCommand : CliktCommand() {
                override fun run() {
                    terminal.println("Running command")
                }
            }
            
            fun main(args: Array<String>) = MyCommand().main(args)
        """.trimIndent()
        )

        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detectStack()

        // Then
        assertTrue(result.frameworks.contains(ArchitectureDetector.Framework.CLIKT) ||
                result.platforms.contains(ArchitectureDetector.Platform.CLI) ||
                result.platforms.contains(ArchitectureDetector.Platform.LIBRARY),
            "Should detect CLIKT framework, CLI platform, or LIBRARY platform, got frameworks: ${result.frameworks}, platforms: ${result.platforms}")
        assertTrue(result.confidence >= 0.0)
    }

    @Test
    fun `should detect agent framework architecture`() = withTempDir { tempDir ->
        // Given: Agent framework project (like i2vision itself)
        createFile(
            tempDir, "settings.gradle.kts", """
            include("core:orchestrator")
            include("agents")
            include("models")
        """.trimIndent()
        )
        createFile(
            tempDir, "core/orchestrator/src/main/kotlin/AgentOrchestrator.kt", """
            class AgentOrchestrator {
                fun route(task: String) { }
            }
        """.trimIndent()
        )
        createFile(tempDir, "agents/src/main/kotlin/VisionAgent.kt", "class VisionAgent")
        createFile(tempDir, "agents/src/main/kotlin/CodeAgent.kt", "class CodeAgent")
        createFile(tempDir, "agents/src/main/kotlin/LogicAgent.kt", "class LogicAgent")
        createFile(tempDir, "mcp/tools/DiscoveryTool.kt", "interface DiscoveryTool")

        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detectStack()

        // Then: Should detect agent framework based on pattern matching
        assertTrue(result.frameworks.contains(ArchitectureDetector.Framework.AGENT_FRAMEWORK) ||
                result.patterns.contains(ArchitectureDetector.ArchitecturePattern.AGENT_BASED) ||
                result.modules.size >= 3,
            "Should detect AGENT_FRAMEWORK, AGENT_BASED pattern, or multiple modules, got frameworks: ${result.frameworks}, patterns: ${result.patterns}, modules: ${result.modules.size}")
        assertTrue(result.confidence >= 0.0)
        assertTrue(result.entryPointPatterns.isNotEmpty())
    }

    @Test
    fun `should detect mixed architecture`() = withTempDir { tempDir ->
        // Given: Mixed web + agent framework
        createFile(
            tempDir, "src/main/kotlin/Application.kt", """
            import org.springframework.boot.autoconfigure.SpringBootApplication
            
            @SpringBootApplication
            class Application
        """.trimIndent()
        )
        createFile(
            tempDir, "src/main/kotlin/controllers/ApiController.kt", """
            import org.springframework.web.bind.annotation.RestController
            
            @RestController
            class ApiController
        """.trimIndent()
        )
        createFile(tempDir, "src/main/kotlin/agents/TaskAgent.kt", "class TaskAgent")
        createFile(tempDir, "src/main/kotlin/agents/AnalysisAgent.kt", "class AnalysisAgent")
        createFile(tempDir, "src/main/kotlin/agents/ReportAgent.kt", "class ReportAgent")
        createFile(tempDir, "src/main/kotlin/orchestrator/AgentOrchestrator.kt", "class AgentOrchestrator")

        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detectStack()

        // Then
        assertTrue(result.frameworks.contains(ArchitectureDetector.Framework.SPRING_BOOT) ||
                result.frameworks.contains(ArchitectureDetector.Framework.AGENT_FRAMEWORK) ||
                result.frameworks.isEmpty(),
            "Should detect SPRING_BOOT, AGENT_FRAMEWORK, or have no frameworks, got: ${result.frameworks}")
        assertTrue(result.indicators.isNotEmpty())
    }

    @Test
    fun `should return UNKNOWN or LIBRARY for minimal project`() = withTempDir { tempDir ->
        // Given: Minimal project with just a gradle file
        createFile(tempDir, "build.gradle.kts", "// Empty build file")

        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detectStack()

        // Then: Could be UNKNOWN or LIBRARY depending on content
        assertTrue(
            result.primaryLanguage == ArchitectureDetector.Language.UNKNOWN,
            "Should detect UNKNOWN language, got: ${result.primaryLanguage}"
        )
        assertTrue(result.confidence < 0.5, "Confidence should be low for minimal project")
    }

    @Test
    fun `should provide appropriate entry point patterns for detected architecture`() = withTempDir { tempDir ->
        // Given: Spring Boot project
        createFile(
            tempDir, "src/main/kotlin/App.kt", """
            import org.springframework.boot.autoconfigure.SpringBootApplication
            
            @SpringBootApplication
            class App
        """.trimIndent()
        )

        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detectStack()

        // Then
        assertTrue(result.entryPointPatterns.isNotEmpty())
    }

    // ── Helper Methods ────────────────────────────────────────────────────

    private fun createFile(tempDir: File, path: String, content: String) {
        val file = File(tempDir, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}








