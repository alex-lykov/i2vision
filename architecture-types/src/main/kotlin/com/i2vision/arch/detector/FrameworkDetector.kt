package com.i2vision.arch.detector

import com.i2vision.arch.signature.Framework
import java.io.File

/**
 * Detects frameworks from project structure
 */
class FrameworkDetector(private val projectRoot: String) {

    /**
     * Detect frameworks from project root
     */
    fun detect(): FrameworkDetector.Result {
        val root = File(projectRoot)
        val frameworks = mutableListOf<Framework>()
        val confidences = mutableMapOf<String, Double>()

        // Detect framework-specific files and directories
        val buildFiles = root.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
        val dirs =
            root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name } ?: emptyList()

        // Kotlin/JVM frameworks
        if (detectKtor(root)) {
            frameworks.add(Framework.KTOR)
            confidences["ktor"] = 0.90
        }
        if (detectSpringBoot(root)) {
            frameworks.add(Framework.SPRING_BOOT)
            confidences["spring_boot"] = 0.90
        }
        if (detectComposeDesktop(root)) {
            frameworks.add(Framework.COMPOSE_DESKTOP)
            confidences["compose_desktop"] = 0.85
        }

        // Web frameworks
        if (detectReact(root)) {
            frameworks.add(Framework.REACT)
            confidences["react"] = 0.90
        }
        if (detectAngular(root)) {
            frameworks.add(Framework.ANGULAR)
            confidences["angular"] = 0.90
        }
        if (detectVue(root)) {
            frameworks.add(Framework.VUE)
            confidences["vue"] = 0.85
        }

        // Desktop frameworks
        if (detectSwing(root)) {
            frameworks.add(Framework.SWING)
            confidences["swing"] = 0.75
        }
        if (detectJavaFx(root)) {
            frameworks.add(Framework.JAVA_FX)
            confidences["javafx"] = 0.75
        }

        return Result(frameworks, confidences)
    }

    private fun detectKtor(root: File): Boolean {
        val buildFile = File(root, "build.gradle.kts")
        if (buildFile.exists()) {
            val content = buildFile.readText()
            return content.contains("ktor") || content.contains("io.ktor")
        }
        return false
    }

    private fun detectSpringBoot(root: File): Boolean {
        val buildFile = File(root, "build.gradle.kts")
        if (buildFile.exists()) {
            val content = buildFile.readText()
            return content.contains("spring-boot") || content.contains("org.springframework.boot")
        }
        val pomFile = File(root, "pom.xml")
        if (pomFile.exists()) {
            val content = pomFile.readText()
            return content.contains("spring-boot")
        }
        return false
    }

    private fun detectComposeDesktop(root: File): Boolean {
        val buildFile = File(root, "build.gradle.kts")
        if (buildFile.exists()) {
            val content = buildFile.readText()
            return content.contains("compose.desktop") || content.contains("org.jetbrains.compose")
        }
        return false
    }

    private fun detectReact(root: File): Boolean {
        val packageFile = File(root, "package.json")
        if (packageFile.exists()) {
            val content = packageFile.readText()
            return content.contains("react") || content.contains("\"react\"")
        }
        return false
    }

    private fun detectAngular(root: File): Boolean {
        val packageFile = File(root, "package.json")
        if (packageFile.exists()) {
            val content = packageFile.readText()
            return content.contains("@angular") || content.contains("angular")
        }
        return false
    }

    private fun detectVue(root: File): Boolean {
        val packageFile = File(root, "package.json")
        if (packageFile.exists()) {
            val content = packageFile.readText()
            return content.contains("vue") || content.contains("\"vue\"")
        }
        return false
    }

    private fun detectSwing(root: File): Boolean {
        val srcDirs = listOf(
            File(root, "src/main/java"),
            File(root, "src/main/kotlin")
        )
        for (srcDir in srcDirs) {
            if (srcDir.exists()) {
                val hasSwing = srcDir.walkTopDown()
                    .filter { it.isFile && it.extension in setOf("java", "kt") }
                    .any { file ->
                        val content = file.readText()
                        content.contains("javax.swing") || content.contains("java.awt")
                    }
                if (hasSwing) return true
            }
        }
        return false
    }

    private fun detectJavaFx(root: File): Boolean {
        val srcDirs = listOf(
            File(root, "src/main/java"),
            File(root, "src/main/kotlin")
        )
        for (srcDir in srcDirs) {
            if (srcDir.exists()) {
                val hasJavaFx = srcDir.walkTopDown()
                    .filter { it.isFile && it.extension in setOf("java", "kt") }
                    .any { file ->
                        val content = file.readText()
                        content.contains("javafx") || content.contains("JavaFX")
                    }
                if (hasJavaFx) return true
            }
        }
        return false
    }

    data class Result(
        val frameworks: List<Framework>,
        val confidences: Map<String, Double>
    )
}
