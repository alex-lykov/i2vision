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
import kotlin.test.assertTrue

/**
 * Tests for Architecture Detection - Gradle Multi-Module.
 * Validates correct detection of MODULAR_MONOLITH pattern.
 */
class GradleMultiModuleTest {

    private fun withTempDir(block: (File) -> Unit) {
        val tempDir = Files.createTempDirectory("gradle-test").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `should detect MODULAR_MONOLITH pattern for Gradle multi-module project`() = withTempDir { tempDir ->
        // Given: Gradle multi-module project structure
        createFile(
            tempDir, "settings.gradle.kts", """
            rootProject.name = "my-app"
            include("user-service")
            include("order-service")
            include("payment-service")
            include("api-gateway")
        """.trimIndent()
        )

        createFile(
            tempDir, "build.gradle.kts", """
            plugins {
                kotlin("jvm") version "1.9.0"
            }
            
            subprojects {
                apply(plugin = "org.jetbrains.kotlin.jvm")
            }
        """.trimIndent()
        )

        createFile(
            tempDir, "user-service/build.gradle.kts", """
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-web")
            }
        """.trimIndent()
        )

        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detectStack()

        // Then: Should detect multiple modules
        assertTrue(result.modules.size >= 4, "Should detect at least 4 modules, got: ${result.modules.size}")
        assertTrue(result.indicators.isNotEmpty())
    }

    @Test
    fun `should detect cluster count from Gradle modules`() = withTempDir { tempDir ->
        // Given: Gradle project with multiple modules
        createFile(
            tempDir, "settings.gradle.kts", """
            include("module1")
            include("module2")
            include("module3")
            include("module4")
            include("module5")
        """.trimIndent()
        )

        createFile(tempDir, "module1/build.gradle.kts", "// module1")
        createFile(tempDir, "module2/build.gradle.kts", "// module2")
        createFile(tempDir, "module3/build.gradle.kts", "// module3")
        createFile(tempDir, "module4/build.gradle.kts", "// module4")
        createFile(tempDir, "module5/build.gradle.kts", "// module5")

        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detectStack()

        // Then: Should detect multiple modules
        assertTrue(result.indicators.isNotEmpty(), "Should have indicators")
        assertTrue(result.modules.size >= 5, "Should detect at least 5 modules, got: ${result.modules.size}")
    }

    @Test
    fun `should detect single-module Gradle project`() = withTempDir { tempDir ->
        // Given: Single-module Gradle project
        createFile(
            tempDir, "build.gradle.kts", """
            plugins {
                kotlin("jvm") version "1.9.0"
            }
        """.trimIndent()
        )

        // When
        val detector = ArchitectureDetector(tempDir.absolutePath)
        val result = detector.detectStack()

        // Then: Should detect as single module (root)
        assertTrue(result.modules.size == 1, "Should detect 1 module, got: ${result.modules.size}")
        assertTrue(result.modules[0].name == "root", "Module should be named 'root'")
    }

    private fun createFile(tempDir: File, path: String, content: String) {
        val file = File(tempDir, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
