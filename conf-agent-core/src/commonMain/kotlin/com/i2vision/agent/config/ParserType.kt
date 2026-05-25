/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.config

import kotlinx.serialization.Serializable

/**
 * Parser types for agent output parsing.
 * 
 * These define the format strategies the OutputParser uses to extract
 * structured data (tool calls, reasoning, completion signals) from
 * raw LLM output.
 * 
 * Multiple parsers can be enabled and will be tried in order until
 * one successfully parses the output.
 * 
 * Example configuration:
 * ```yaml
 * parsing:
 *   enabledParsers:
 *     - HEADER
 *     - NAKED_JSON
 *     - XML_INVOKE
 *   strictJsonParsing: true
 *   allowMarkdownCodeBlocks: true
 * ```
 */
enum class ParserType {
    /**
     * Header-based format with labeled sections.
     * 
     * Expected format:
     * ```
     * reasoning: I need to read the file first to understand the structure
     * tool_call: {"tool": "read_file", "args": {"path": "src/main.kt"}}
     * ```
     * 
     * Or with multiple lines:
     * ```
     * reasoning: The user wants to refactor this code.
     * I should start by reading the current implementation.
     * 
     * tool_call: {"tool": "read_file", "args": {"path": "src/main.kt"}}
     * ```
     */
    HEADER,
    
    /**
     * Naked JSON format without any wrapper.
     * 
     * Expected format:
     * ```
     * {"tool": "read_file", "args": {"path": "src/main.kt"}}
     * ```
     * 
     * May be preceded by prose text:
     * ```
     * I'll read the file first.
     * {"tool": "read_file", "args": {"path": "src/main.kt"}}
     * ```
     */
    NAKED_JSON,
    
    /**
     * XML-style invoke tags.
     * 
     * Expected format:
     * ```
     * <invoke name="read_file">
     *   <arg name="path">src/main.kt</arg>
     * </invoke>
     * ```
     * 
     * May be preceded by reasoning text.
     */
    XML_INVOKE,
    
    /**
     * Quoted JSON string (escaped JSON within quotes).
     * 
     * Expected format:
     * ```
     * "{\"tool\": \"read_file\", \"args\": {\"path\": \"src/main.kt\"}}"
     * ```
     * 
     * This format is sometimes produced by models that are trained to
     * output JSON as a string literal.
     */
    QUOTED_JSON,
    
    /**
     * Markdown code block with JSON.
     * 
     * Expected format:
     * ```
     * ```json
     * {"tool": "read_file", "args": {"path": "src/main.kt"}}
     * ```
     * ```
     */
    MARKDOWN_JSON
}

/**
 * Parsing configuration for agent output.
 * 
 * @property enabledParsers List of parsers to try (in order)
 * @property strictJsonParsing Require valid JSON for JSON-based parsers
 * @property allowMarkdownCodeBlocks Extract JSON from markdown code blocks
 * @property fallbackToPlainText Fall back to plain text if parsing fails
 * @property maxParseAttempts Maximum attempts to parse malformed output
 */
@Serializable
data class ParsingConfig(
    val enabledParsers: List<ParserType> = listOf(ParserType.HEADER, ParserType.NAKED_JSON, ParserType.XML_INVOKE),
    val strictJsonParsing: Boolean = true,
    val allowMarkdownCodeBlocks: Boolean = true,
    val fallbackToPlainText: Boolean = true,
    val maxParseAttempts: Int = 3
)
