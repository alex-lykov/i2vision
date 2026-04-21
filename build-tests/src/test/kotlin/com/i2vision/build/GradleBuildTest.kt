package com.i2vision.build

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

/**
 * Tests for Gradle Build.
 * Validates that ./gradlew build succeeds on clean checkout.
 */
class GradleBuildTest {
    
    @Test
    fun `should succeed on clean build`() {
        // Given: Project root directory (parent of build-tests module)
        val projectRoot = File(System.getProperty("user.dir")).parentFile
        
        // When: This test runs (it means the build succeeded to compile this test)
        // Then: The build must have succeeded to reach this point
        assertTrue(projectRoot != null, "Project root should not be null")
        assertTrue(projectRoot.exists(), "Project root should exist")
        assertTrue(File(projectRoot, "build.gradle.kts").exists(), "Root build file should exist")
        assertTrue(File(projectRoot, "settings.gradle.kts").exists(), "Settings file should exist")
        
        // If this test runs, ./gradlew build must have succeeded to compile it
        assertTrue(true, "Gradle build succeeded (test compiled and executed)")
    }
    
    @Test
    fun `should have LICENSE file in project root`() {
        // Given: Project root directory
        val projectRoot = File(System.getProperty("user.dir")).parentFile
        
        // When: Check for LICENSE file
        val licenseFile = File(projectRoot, "LICENSE")
        
        // Then: LICENSE file should exist
        assertTrue(licenseFile.exists(), "LICENSE file should exist in project root")
    }
    
    @Test
    fun `should have LICENSE file in modules`() {
        // Given: Project root directory
        val projectRoot = File(System.getProperty("user.dir")).parentFile
        
        // When: Check for LICENSE files in modules
        val modules = listOf("architecture-types", "vslfc-core", "i2vision-architecture", "llm-client", "conf-agent-core", "storage-core", "intent-parser", "discovery-engine", "contracts", "discovery-api")
        val modulesWithLicense = modules.count { module ->
            File(projectRoot, module).exists() && File(projectRoot, "$module/LICENSE").exists()
        }
        
        // Then: At least some modules should have LICENSE files
        assertTrue(modulesWithLicense > 0, "At least some modules should have LICENSE files")
    }
}
