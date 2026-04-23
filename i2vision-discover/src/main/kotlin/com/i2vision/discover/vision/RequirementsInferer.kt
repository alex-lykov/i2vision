package com.i2vision.discover.vision

import com.i2vision.discover.pipeline.VisionCodeEvidence
import com.i2vision.discover.pipeline.Source
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Requirements Inferred from Code
 * 
 * Analyzes source code to infer requirements based on patterns like:
 * - `require(token.isNotBlank())` → "Token validation is required"
 * - `@Versioned` annotation → "API versioning is enforced"
 * - `class AuthService` → "Authentication service exists"
 */
class RequirementsInferer(
    private val projectRoot: File
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Infer requirements from source code patterns.
     */
    fun inferRequirements(sourceFiles: List<File>): List<InferredRequirement> {
        val requirements = mutableListOf<InferredRequirement>()

        sourceFiles.forEach { file ->
            try {
                val lines = file.readLines()
                val relativePath = file.relativeTo(projectRoot).path

                lines.forEachIndexed { index, line ->
                    val inferred = inferFromLine(line, relativePath, index + 1)
                    if (inferred != null) {
                        requirements.add(inferred)
                    }
                }
            } catch (e: Exception) {
                log.debug("[INFER] Error reading ${file.name}: ${e.message}")
            }
        }

        log.info("[INFER] Inferred ${requirements.size} requirements from code")
        return requirements
    }

    /**
     * Infer a requirement from a single line of code.
     */
    private fun inferFromLine(line: String, file: String, lineNumber: Int): InferredRequirement? {
        val trimmed = line.trim()

        // Pattern 1: require() calls
        val requireMatch = Regex("""require\s*\(\s*([^)]+)\)""").find(trimmed)
        if (requireMatch != null) {
            val condition = requireMatch.groupValues[1]
            val description = describeRequirement(condition)
            return InferredRequirement(
                id = "REQ-${sanitizeId(description)}",
                title = description,
                description = "Inferred from require() call",
                priority = "MEDIUM",
                source = Source.CODE_PATTERN,
                evidence = listOf(
                    VisionCodeEvidence(
                        file = file,
                        line = lineNumber,
                        pattern = trimmed,
                        description = "require() call"
                    )
                )
            )
        }

        // Pattern 2: check() calls
        val checkMatch = Regex("""check\s*\(\s*([^)]+)\)""").find(trimmed)
        if (checkMatch != null) {
            val condition = checkMatch.groupValues[1]
            val description = describeCheck(condition)
            return InferredRequirement(
                id = "REQ-${sanitizeId(description)}",
                title = description,
                description = "Inferred from check() call",
                priority = "HIGH",
                source = Source.CODE_PATTERN,
                evidence = listOf(
                    VisionCodeEvidence(
                        file = file,
                        line = lineNumber,
                        pattern = trimmed,
                        description = "check() call"
                    )
                )
            )
        }

        // Pattern 3: Annotation-based requirements
        val annotationMatch = Regex("""@(\w+)""").find(trimmed)
        if (annotationMatch != null) {
            val annotation = annotationMatch.groupValues[1]
            val description = describeAnnotation(annotation)
            if (description != null) {
                return InferredRequirement(
                    id = "REQ-${sanitizeId(description)}",
                    title = description,
                    description = "Inferred from @$annotation annotation",
                    priority = "MEDIUM",
                    source = Source.CODE_PATTERN,
                    evidence = listOf(
                        VisionCodeEvidence(
                            file = file,
                            line = lineNumber,
                            pattern = trimmed,
                            description = "@$annotation annotation"
                        )
                    )
                )
            }
        }

        // Pattern 4: Class-based requirements
        val classMatch = Regex("""(class|interface|object)\s+(\w+)""").find(trimmed)
        if (classMatch != null) {
            val type = classMatch.groupValues[1]
            val name = classMatch.groupValues[2]
            val description = describeClass(name)
            return InferredRequirement(
                id = "REQ-${sanitizeId(description)}",
                title = description,
                description = "Inferred from $type $name",
                priority = "MEDIUM",
                source = Source.CODE_PATTERN,
                evidence = listOf(
                    VisionCodeEvidence(
                        file = file,
                        line = lineNumber,
                        pattern = trimmed,
                        description = "$type definition"
                    )
                )
            )
        }

        return null
    }

    /**
     * Describe a requirement based on a require() condition.
     */
    private fun describeRequirement(condition: String): String {
        return when {
            condition.contains("notNull") || condition.contains("!= null") -> "Null check required"
            condition.contains("notBlank") || condition.contains("isNotEmpty") -> "Non-empty validation required"
            condition.contains("isNotBlank") -> "Non-blank validation required"
            condition.contains("isPositive") || condition.contains("> 0") -> "Positive value required"
            condition.contains("isNegative") || condition.contains("< 0") -> "Negative value required"
            condition.contains("isValid") -> "Validation required"
            condition.contains("isAuthenticated") || condition.contains("auth") -> "Authentication required"
            condition.contains("isAuthorized") || condition.contains("permission") -> "Authorization required"
            else -> "Validation: $condition"
        }
    }

    /**
     * Describe a requirement based on a check() condition.
     */
    private fun describeCheck(condition: String): String {
        return when {
            condition.contains("state") -> "State check enforced"
            condition.contains("condition") -> "Condition check enforced"
            condition.contains("precondition") -> "Precondition check enforced"
            condition.contains("postcondition") -> "Postcondition check enforced"
            else -> "Invariant check: $condition"
        }
    }

    /**
     * Describe a requirement based on an annotation.
     */
    private fun describeAnnotation(annotation: String): String? {
        return when (annotation) {
            "Versioned" -> "API versioning enforced"
            "Deprecated" -> "Deprecation warning required"
            "Experimental" -> "Experimental feature flag required"
            "RequiresOptIn" -> "Opt-in permission required"
            "Suppress" -> "Warning suppression required"
            "Throws" -> "Exception handling required"
            "Synchronized" -> "Thread synchronization required"
            "Volatile" -> "Thread visibility required"
            else -> null
        }
    }

    /**
     * Describe a requirement based on a class name.
     */
    private fun describeClass(name: String): String {
        return when {
            name.contains("Service") || name.contains("Manager") -> "${name} service exists"
            name.contains("Repository") || name.contains("Dao") -> "${name} data access exists"
            name.contains("Controller") || name.contains("Handler") -> "${name} endpoint exists"
            name.contains("Validator") -> "${name} validation exists"
            name.contains("Config") || name.contains("Settings") -> "${name} configuration exists"
            name.contains("Auth") || name.contains("Security") -> "${name} security exists"
            name.contains("Cache") || name.contains("Storage") -> "${name} storage exists"
            else -> "${name} component exists"
        }
    }

    /**
     * Sanitize a string to create a valid ID.
     */
    private fun sanitizeId(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9]"), "-")
            .take(20)
    }
}

/**
 * Inferred requirement from code analysis.
 */
data class InferredRequirement(
    val id: String,
    val title: String,
    val description: String,
    val priority: String,
    val source: Source,
    val evidence: List<VisionCodeEvidence>
)
