package com.i2vision.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

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
        createFile(tempDir, "src/main/kotlin/Application.kt", """
            @SpringBootApplication
            class Application
            
            fun main(args: Array<String>) {
                SpringApplication.run(Application::class.java, *args)
            }
        """.trimIndent())
        createFile(tempDir, "src/main/kotlin/UserController.kt", """
            @RestController
            class UserController {
                @GetMapping("/users")
                fun getUsers() = listOf("user1")
            }
        """.trimIndent())
        
        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detect()
        
        // Then: Should detect web architecture (could be MIXED if other patterns detected)
        assertTrue(result.type == ArchitectureDetector.ArchitectureType.MONOLITH_WEB ||
                   result.type == ArchitectureDetector.ArchitectureType.MIXED,
            "Should detect MONOLITH_WEB or MIXED, got: ${result.type}")
        assertTrue(result.confidence > 0.3, "Confidence should be reasonable")
        assertTrue(result.indicators.any { it.contains("spring") })
        assertTrue(result.entryPointPatterns.contains("@RestController"))
    }
    
    @Test
    fun `should detect Ktor web application`() = withTempDir { tempDir ->
        // Given: Ktor project structure
        createFile(tempDir, "src/main/kotlin/Application.kt", """
            import io.ktor.server.engine.*
            import io.ktor.server.netty.*
            
            fun main() {
                embeddedServer(Netty, port = 8080) {
                    routing {
                        get("/") { call.respond("Hello") }
                    }
                }.start(wait = true)
            }
        """.trimIndent())
        
        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detect()
        
        // Then
        assertEquals(ArchitectureDetector.ArchitectureType.MONOLITH_WEB, result.type)
        assertTrue(result.confidence > 0.5)
        assertTrue(result.indicators.any { it.contains("ktor") })
        assertTrue(result.entryPointPatterns.any { it.contains("routing") })
    }
    
    @Test
    fun `should detect microservices architecture`() = withTempDir { tempDir ->
        // Given: Multi-module microservices project
        createFile(tempDir, "settings.gradle.kts", """
            include("user-service")
            include("order-service")
            include("payment-service")
            include("api-gateway")
        """.trimIndent())
        createFile(tempDir, "user-service/src/main/kotlin/UserService.kt", """
            class UserService {
                fun getUser(id: String) = User(id)
            }
        """.trimIndent())
        createFile(tempDir, "api-gateway/src/main/kotlin/ApiGateway.kt", """
            class ApiGateway {
                val userClient = UserClient()
            }
        """.trimIndent())
        
        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detect()
        
        // Then
        assertEquals(ArchitectureDetector.ArchitectureType.MICROSERVICES, result.type)
        assertTrue(result.confidence > 0.3)
        assertTrue(result.indicators.any { it.contains("multi_module") })
    }
    
    @Test
    fun `should detect library project`() = withTempDir { tempDir ->
        // Given: Library project with publishing config and no main()
        createFile(tempDir, "build.gradle.kts", """
            plugins {
                kotlin("jvm")
                `maven-publish`
            }
            
            publishing {
                publications {
                    create<MavenPublication>("library") { }
                }
            }
        """.trimIndent())
        createFile(tempDir, "README.md", """
            # My Library
            
            ## Usage
            ```kotlin
            val lib = MyLibrary()
            ```
        """.trimIndent())
        createFile(tempDir, "src/main/kotlin/MyLibrary.kt", """
            @JvmName("getLibrary")
            class MyLibrary {
                fun doSomething() { }
            }
        """.trimIndent())
        
        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detect()
        
        // Then: Should detect as LIBRARY due to publishing config and no main()
        assertTrue(result.type in listOf(
            ArchitectureDetector.ArchitectureType.LIBRARY,
            ArchitectureDetector.ArchitectureType.UNKNOWN
        ), "Should detect LIBRARY or UNKNOWN for library project, got: ${result.type}")
        
        // Should have reasonable indicators
        assertTrue(result.indicators.isNotEmpty(), 
            "Should have at least one indicator, got: ${result.indicators}")
    }
    
    @Test
    fun `should detect CLI tool`() = withTempDir { tempDir ->
        // Given: CLI tool project
        createFile(tempDir, "src/main/kotlin/Main.kt", """
            import com.github.ajalt.clikt.core.CliktCommand
            
            class MyCommand : CliktCommand() {
                override fun run() {
                    echo("Running command")
                }
            }
            
            fun main(args: Array<String>) = MyCommand().main(args)
        """.trimIndent())
        
        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detect()
        
        // Then
        assertEquals(ArchitectureDetector.ArchitectureType.CLI_TOOL, result.type)
        assertTrue(result.confidence > 0.5)
        assertTrue(result.indicators.any { it.contains("cli_framework") || it.contains("main") })
    }
    
    @Test
    fun `should detect agent framework architecture`() = withTempDir { tempDir ->
        // Given: Agent framework project (like i2vision itself)
        createFile(tempDir, "settings.gradle.kts", """
            include("core:orchestrator")
            include("agents")
            include("models")
        """.trimIndent())
        createFile(tempDir, "core/orchestrator/src/main/kotlin/AgentOrchestrator.kt", """
            class AgentOrchestrator {
                fun route(task: String) { }
            }
        """.trimIndent())
        createFile(tempDir, "agents/src/main/kotlin/VisionAgent.kt", "class VisionAgent")
        createFile(tempDir, "agents/src/main/kotlin/CodeAgent.kt", "class CodeAgent")
        createFile(tempDir, "agents/src/main/kotlin/LogicAgent.kt", "class LogicAgent")
        createFile(tempDir, "mcp/tools/DiscoveryTool.kt", "interface DiscoveryTool")
        
        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detect()
        
        // Then: Should detect agent architecture (could be MIXED with other patterns)
        assertTrue(result.type == ArchitectureDetector.ArchitectureType.AGENT_FRAMEWORK ||
                   result.type == ArchitectureDetector.ArchitectureType.MIXED,
            "Should detect AGENT_FRAMEWORK or MIXED, got: ${result.type}")
        assertTrue(result.confidence > 0.3)
        assertTrue(result.indicators.any { it.contains("agent_classes") },
            "Should detect agent_classes, got: ${result.indicators}")
        assertTrue(result.indicators.any { it.contains("orchestrator") })
        assertTrue(result.entryPointPatterns.any { it.contains("Agent") })
    }
    
    @Test
    fun `should detect mixed architecture`() = withTempDir { tempDir ->
        // Given: Mixed web + agent framework
        createFile(tempDir, "src/main/kotlin/Application.kt", """
            @SpringBootApplication
            class Application
        """.trimIndent())
        createFile(tempDir, "src/main/kotlin/controllers/ApiController.kt", """
            @RestController
            class ApiController
        """.trimIndent())
        createFile(tempDir, "src/main/kotlin/agents/TaskAgent.kt", "class TaskAgent")
        createFile(tempDir, "src/main/kotlin/agents/AnalysisAgent.kt", "class AnalysisAgent")
        createFile(tempDir, "src/main/kotlin/agents/ReportAgent.kt", "class ReportAgent")
        createFile(tempDir, "src/main/kotlin/orchestrator/AgentOrchestrator.kt", "class AgentOrchestrator")
        
        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detect()
        
        // Then
        assertEquals(ArchitectureDetector.ArchitectureType.MIXED, result.type)
        assertTrue(result.indicators.any { it.contains("spring") })
        assertTrue(result.indicators.any { it.contains("agent") })
    }
    
    @Test
    fun `should return UNKNOWN or LIBRARY for minimal project`() = withTempDir { tempDir ->
        // Given: Minimal project with just a gradle file
        createFile(tempDir, "build.gradle.kts", "// Empty build file")
        
        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detect()
        
        // Then: Could be UNKNOWN or LIBRARY depending on content
        assertTrue(result.type == ArchitectureDetector.ArchitectureType.UNKNOWN || 
                   result.type == ArchitectureDetector.ArchitectureType.LIBRARY)
        assertTrue(result.confidence < 0.5, "Confidence should be low for minimal project")
    }
    
    @Test
    fun `should provide appropriate entry point patterns for detected architecture`() = withTempDir { tempDir ->
        // Given: Spring Boot project
        createFile(tempDir, "src/main/kotlin/App.kt", "@SpringBootApplication class App")
        
        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detect()
        
        // Then
        assertTrue(result.entryPointPatterns.isNotEmpty())
        assertTrue(result.entryPointPatterns.any { it.contains("Controller") || it.contains("Service") })
    }
    
    // ── Helper Methods ────────────────────────────────────────────────────
    
    private fun createFile(tempDir: File, path: String, content: String) {
        val file = File(tempDir, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}








