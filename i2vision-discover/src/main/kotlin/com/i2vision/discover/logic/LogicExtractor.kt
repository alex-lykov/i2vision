/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.logic

import com.i2vision.index.IndexProvider
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Logic extraction using pattern matching.
 * 
 * Discovers business rules by analyzing code patterns like:
 * - require() statements
 * - check() statements
 * - validate() methods
 * - if-else conditions
 * - when expressions
 */
class LogicExtractor(
    projectRoot: String,
    private val indexProvider: IndexProvider
) {

    private val log = LoggerFactory.getLogger(LogicExtractor::class.java)

    // Normalize projectRoot to absolute path to avoid path normalization issues
    private val projectRoot = File(projectRoot).absolutePath

    /**
     * Extract business rules from source files.
     * 
     * @param files List of source files to analyze
     * @return List of discovered business rules
     */
    fun extractBusinessRules(files: List<File>): List<BusinessRule> {
        log.info("[LOGIC_EXTRACTOR] Extracting business rules from {} files", files.size)

        val rules = mutableListOf<BusinessRule>()

        files.forEach { file ->
            val fileRules = extractRulesFromFile(file)
            rules.addAll(fileRules)
        }

        log.info("[LOGIC_EXTRACTOR] Extracted {} business rules", rules.size)

        return rules
    }

    /**
     * Extract business rules from a single file.
     * 
     * @param file The source file
     * @return List of business rules from this file
     */
    private fun extractRulesFromFile(file: File): List<BusinessRule> {
        val rules = mutableListOf<BusinessRule>()

        try {
            val content = file.readText()
            val lines = content.lines()

            // Pattern 1: require() statements
            rules.addAll(extractRequireStatements(file, lines))

            // Pattern 2: check() statements
            rules.addAll(extractCheckStatements(file, lines))

            // Pattern 3: validate() methods
            rules.addAll(extractValidateMethods(file, lines))

            // Pattern 4: if-else conditions with business meaning
            rules.addAll(extractIfElseConditions(file, lines))

            // Pattern 5: when expressions
            rules.addAll(extractWhenExpressions(file, lines))

            // Pattern 6: assert statements
            rules.addAll(extractAssertStatements(file, lines))

        } catch (e: Exception) {
            log.warn("[LOGIC_EXTRACTOR] Failed to extract rules from {}: {}", file.path, e.message)
        }

        return rules
    }

    /**
     * Extract require() statements.
     * 
     * @param file The source file
     * @param lines File content lines
     * @return List of business rules
     */
    private fun extractRequireStatements(file: File, lines: List<String>): List<BusinessRule> {
        val rules = mutableListOf<BusinessRule>()

        lines.forEachIndexed { index, line ->
            val requirePattern = Regex("""require\s*\(\s*([^)]+)\)""")
            val match = requirePattern.find(line)

            if (match != null) {
                val condition = match.groupValues[1].trim()
                rules.add(
                    BusinessRule(
                        id = generateRuleId(file, index, "require"),
                        name = "Requirement",
                        type = "require",
                        condition = condition,
                        file = file.relativeTo(File(projectRoot)).path,
                        line = index + 1
                    )
                )
            }
        }

        return rules
    }

    /**
     * Extract check() statements.
     * 
     * @param file The source file
     * @param lines File content lines
     * @return List of business rules
     */
    private fun extractCheckStatements(file: File, lines: List<String>): List<BusinessRule> {
        val rules = mutableListOf<BusinessRule>()

        lines.forEachIndexed { index, line ->
            val checkPattern = Regex("""check\s*\(\s*([^)]+)\)""")
            val match = checkPattern.find(line)

            if (match != null) {
                val condition = match.groupValues[1].trim()
                rules.add(
                    BusinessRule(
                        id = generateRuleId(file, index, "check"),
                        name = "Check",
                        type = "check",
                        condition = condition,
                        file = file.relativeTo(File(projectRoot)).path,
                        line = index + 1
                    )
                )
            }
        }

        return rules
    }

    /**
     * Extract validate() methods.
     * 
     * @param file The source file
     * @param lines File content lines
     * @return List of business rules
     */
    private fun extractValidateMethods(file: File, lines: List<String>): List<BusinessRule> {
        val rules = mutableListOf<BusinessRule>()

        val validatePattern = Regex("""fun\s+validate(\w+)\s*\([^)]*\)\s*:\s*\w+""")

        lines.forEachIndexed { index, line ->
            val match = validatePattern.find(line)

            if (match != null) {
                val methodName = "validate${match.groupValues[1]}"
                rules.add(
                    BusinessRule(
                        id = generateRuleId(file, index, "validate"),
                        name = "Validation",
                        type = "validate",
                        condition = "Method: $methodName",
                        file = file.relativeTo(File(projectRoot)).path,
                        line = index + 1
                    )
                )
            }
        }

        return rules
    }

    /**
     * Extract if-else conditions with business meaning.
     * 
     * @param file The source file
     * @param lines File content lines
     * @return List of business rules
     */
    private fun extractIfElseConditions(file: File, lines: List<String>): List<BusinessRule> {
        val rules = mutableListOf<BusinessRule>()

        lines.forEachIndexed { index, line ->
            // Look for if statements with business-related keywords
            val businessKeywords = listOf(
                "should", "must", "cannot", "allowed", "required",
                "valid", "invalid", "authorized", "permission",
                "exists", "empty", "null", "blank", "positive"
            )

            if (line.trim().startsWith("if (") || line.trim().startsWith("if(")) {
                val hasBusinessKeyword = businessKeywords.any { keyword ->
                    line.lowercase().contains(keyword)
                }

                if (hasBusinessKeyword) {
                    val ifPattern = Regex("""if\s*\(\s*([^)]+)\)""")
                    val match = ifPattern.find(line)

                    if (match != null) {
                        val condition = match.groupValues[1].trim()
                        rules.add(
                            BusinessRule(
                                id = generateRuleId(file, index, "if"),
                                name = "Conditional Rule",
                                type = "if-else",
                                condition = condition,
                                file = file.relativeTo(File(projectRoot)).path,
                                line = index + 1
                            )
                        )
                    }
                }
            }
        }

        return rules
    }

    /**
     * Extract when expressions.
     * 
     * @param file The source file
     * @param lines File content lines
     * @return List of business rules
     */
    private fun extractWhenExpressions(file: File, lines: List<String>): List<BusinessRule> {
        val rules = mutableListOf<BusinessRule>()

        lines.forEachIndexed { index, line ->
            if (line.trim().startsWith("when (") || line.trim().startsWith("when(")) {
                val whenPattern = Regex("""when\s*\(\s*([^)]+)\)""")
                val match = whenPattern.find(line)

                if (match != null) {
                    val condition = match.groupValues[1].trim()
                    rules.add(
                        BusinessRule(
                            id = generateRuleId(file, index, "when"),
                            name = "Branching Rule",
                            type = "when",
                            condition = condition,
                            file = file.relativeTo(File(projectRoot)).path,
                            line = index + 1
                        )
                    )
                }
            }
        }

        return rules
    }

    /**
     * Extract assert statements.
     * 
     * @param file The source file
     * @param lines File content lines
     * @return List of business rules
     */
    private fun extractAssertStatements(file: File, lines: List<String>): List<BusinessRule> {
        val rules = mutableListOf<BusinessRule>()

        lines.forEachIndexed { index, line ->
            val assertPattern = Regex("""assert\s*\(\s*([^)]+)\)""")
            val match = assertPattern.find(line)

            if (match != null) {
                val condition = match.groupValues[1].trim()
                rules.add(
                    BusinessRule(
                        id = generateRuleId(file, index, "assert"),
                        name = "Assertion",
                        type = "assert",
                        condition = condition,
                        file = file.relativeTo(File(projectRoot)).path,
                        line = index + 1
                    )
                )
            }
        }

        return rules
    }

    /**
     * Generate a unique rule ID.
     * 
     * @param file The source file
     * @param line The line number
     * @param type The rule type
     * @return Unique rule ID
     */
    private fun generateRuleId(file: File, line: Int, type: String): String {
        val fileName = file.nameWithoutExtension.lowercase()
        return "rule_${fileName}_${type}_${line}"
    }
}

/**
 * Represents a discovered business rule.
 */
data class BusinessRule(
    val id: String,
    val name: String,
    val type: String,           // require, check, validate, if-else, when, assert
    val condition: String,
    val file: String,
    val line: Int
)
