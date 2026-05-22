/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.tools

import com.i2vision.agent.*

/**
 * i2vision analysis tools.
 * 
 * These tools provide contract validation, symbol search, and verbalization
 * capabilities for deep code analysis.
 * 
 * ## Tools
 * 
 * - **i2vision_validate_contracts**: Validate VSLFC contracts
 * - **i2vision_search_symbols**: Search for symbol definitions
 * - **i2vision_verbalize**: Get natural language description of a symbol
 * 
 * @property instantContext i2vision instant context provider
 * @property discoveryEngine i2vision discovery engine
 */
class AnalysisTools(
    private val instantContext: InstantContextProvider,
    private val discoveryEngine: DiscoveryEngine
) {
    
    /**
     * Validate VSLFC contracts for a file or project.
     * 
     * This tool checks that code adheres to VSLFC layer contracts,
     * identifying violations such as:
     * - CODE layer calling VISION layer directly
     * - Missing contract implementations
     * - Layer boundary violations
     */
    fun validateContracts(): Tool = Tool(
        name = "i2vision_validate_contracts",
        aliases = listOf("validate_contracts", "check_contracts", "contract_validation"),
        description = "Validate VSLFC contracts for a file or project. " +
                      "Checks for layer boundary violations, missing implementations, " +
                      "and contract adherence. Returns a list of violations with severity.",
        category = ToolCategory.ANALYSIS,
        isReadOnly = true,
        isExpensive = true,
        cacheResults = false,
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "File or directory path to validate (relative to workspace)"
            ),
            ToolParameter(
                name = "layer",
                type = "string",
                description = "Specific VSLFC layer to validate: CODE, FLOW, LOGIC, STRUCTURE, VISION",
                required = false,
                enum = listOf("CODE", "FLOW", "LOGIC", "STRUCTURE", "VISION", "all")
            ),
            ToolParameter(
                name = "strict",
                type = "boolean",
                description = "Enable strict validation (more violations, including warnings)",
                required = false,
                default = false
            )
        ),
        handler = { args ->
            val path = args["path"] as? String ?: return@Tool ToolResult.failure("path is required")
            val layer = args["layer"] as? String
            val strict = args["strict"] as? Boolean ?: false
            
            val violations = try {
                // Simulate contract validation
                val layerFilter = if (layer != null && layer != "all") {
                    VslfcLayer.valueOf(layer.uppercase())
                } else {
                    null
                }
                
                performContractValidation(path, layerFilter, strict)
            } catch (e: Exception) {
                return@Tool ToolResult.failure(
                    error = "Contract validation failed: ${e.message}",
                    metadata = mapOf("path" to path)
                )
            }
            
            val severityCount = violations.groupBy { it.severity }
            
            ToolResult.success(
                output = buildString {
                    appendLine("## Contract Validation Results")
                    appendLine("**Path:** $path")
                    appendLine("**Layer:** ${layer ?: "all"}")
                    appendLine("**Strict Mode:** $strict")
                    appendLine()
                    
                    if (violations.isEmpty()) {
                        appendLine("✅ No contract violations found!")
                    } else {
                        appendLine("### Violations Summary")
                        appendLine("- **Critical:** ${severityCount[ViolationSeverity.CRITICAL]?.size ?: 0}")
                        appendLine("- **Error:** ${severityCount[ViolationSeverity.ERROR]?.size ?: 0}")
                        appendLine("- **Warning:** ${severityCount[ViolationSeverity.WARNING]?.size ?: 0}")
                        appendLine()
                        
                        appendLine("### Violations")
                        violations.take(20).forEach { violation ->
                            appendLine("#### [${violation.severity}] ${violation.type}")
                            appendLine("**Location:** ${violation.location}")
                            appendLine("**Description:** ${violation.description}")
                            appendLine("**Layer:** ${violation.layer}")
                            appendLine()
                        }
                        
                        if (violations.size > 20) {
                            appendLine("*... and ${violations.size - 20} more violations*")
                        }
                    }
                },
                metadata = mapOf(
                    "path" to path,
                    "totalViolations" to violations.size.toString(),
                    "critical" to (severityCount[ViolationSeverity.CRITICAL]?.size ?: 0).toString(),
                    "errors" to (severityCount[ViolationSeverity.ERROR]?.size ?: 0).toString(),
                    "warnings" to (severityCount[ViolationSeverity.WARNING]?.size ?: 0).toString()
                )
            )
        }
    )
    
    /**
     * Search for symbol definitions across the codebase.
     * 
     * This tool finds symbol definitions by name, supporting:
     * - Exact name matching
     * - Pattern matching with wildcards
     * - Filtering by symbol kind (class, function, property, etc.)
     * - Filtering by VSLFC layer
     */
    fun searchSymbols(): Tool = Tool(
        name = "i2vision_search_symbols",
        aliases = listOf("search_symbols", "find_symbol", "symbol_search"),
        description = "Search for symbol definitions across the codebase. " +
                      "Supports exact matching, pattern matching, and filtering by kind and layer.",
        category = ToolCategory.ANALYSIS,
        isReadOnly = true,
        isExpensive = false,
        cacheResults = true,
        cacheTtlSeconds = 300,  // 5 minutes
        parameters = listOf(
            ToolParameter(
                name = "name",
                type = "string",
                description = "Symbol name or pattern to search for (supports * wildcard)"
            ),
            ToolParameter(
                name = "kind",
                type = "string",
                description = "Symbol kind: class, interface, function, property, method, field, all",
                required = false,
                default = "all",
                enum = listOf("class", "interface", "function", "property", "method", "field", "all")
            ),
            ToolParameter(
                name = "layer",
                type = "string",
                description = "VSLFC layer filter: CODE, FLOW, LOGIC, STRUCTURE, VISION, all",
                required = false,
                default = "all",
                enum = listOf("CODE", "FLOW", "LOGIC", "STRUCTURE", "VISION", "all")
            ),
            ToolParameter(
                name = "maxResults",
                type = "integer",
                description = "Maximum number of results",
                required = false,
                default = 50
            )
        ),
        handler = { args ->
            val name = args["name"] as? String ?: return@Tool ToolResult.failure("name is required")
            val kind = args["kind"] as? String ?: "all"
            val layer = args["layer"] as? String ?: "all"
            val maxResults = args["maxResults"] as? Int ?: 50
            
            val symbols = try {
                performSymbolSearch(name, kind, layer, maxResults)
            } catch (e: Exception) {
                return@Tool ToolResult.failure(
                    error = "Symbol search failed: ${e.message}",
                    metadata = mapOf("name" to name)
                )
            }
            
            ToolResult.success(
                output = buildString {
                    appendLine("## Symbol Search Results")
                    appendLine("**Query:** $name")
                    appendLine("**Kind:** $kind")
                    appendLine("**Layer:** $layer")
                    appendLine("**Results:** ${symbols.size}")
                    appendLine()
                    
                    if (symbols.isEmpty()) {
                        appendLine("No symbols found matching the criteria.")
                    } else {
                        symbols.forEach { symbol ->
                            appendLine("### ${symbol.kind}: ${symbol.name}")
                            appendLine("**Location:** ${symbol.location}")
                            appendLine("**Layer:** ${symbol.layer}")
                            if (symbol.signature != null) {
                                appendLine("**Signature:** `${symbol.signature}`")
                            }
                            appendLine()
                        }
                    }
                },
                metadata = mapOf(
                    "query" to name,
                    "totalResults" to symbols.size.toString(),
                    "kind" to kind,
                    "layer" to layer
                )
            )
        }
    )
    
    /**
     * Get natural language description of a symbol.
     * 
     * This tool verbalizes a symbol's purpose, behavior, and relationships
     * in natural language, making it easier to understand complex code.
     */
    fun verbalize(): Tool = Tool(
        name = "i2vision_verbalize",
        aliases = listOf("verbalize", "describe_symbol", "explain"),
        description = "Get a natural language description of a symbol's purpose, behavior, " +
                      "and relationships. Useful for understanding complex code without " +
                      "reading the implementation.",
        category = ToolCategory.ANALYSIS,
        isReadOnly = true,
        isExpensive = false,
        cacheResults = true,
        cacheTtlSeconds = 600,  // 10 minutes
        parameters = listOf(
            ToolParameter(
                name = "symbol",
                type = "string",
                description = "Symbol name or fully qualified name"
            ),
            ToolParameter(
                name = "file",
                type = "string",
                description = "File containing the symbol (optional, helps disambiguate)"
            ),
            ToolParameter(
                name = "detail",
                type = "string",
                description = "Level of detail: brief, normal, detailed",
                required = false,
                default = "normal",
                enum = listOf("brief", "normal", "detailed")
            )
        ),
        handler = { args ->
            val symbolName = args["symbol"] as? String ?: return@Tool ToolResult.failure("symbol is required")
            val file = args["file"] as? String
            val detail = args["detail"] as? String ?: "normal"
            
            val verbalization = try {
                performVerbalization(symbolName, file, detail)
            } catch (e: Exception) {
                return@Tool ToolResult.failure(
                    error = "Verbalization failed: ${e.message}",
                    metadata = mapOf("symbol" to symbolName)
                )
            }
            
            ToolResult.success(
                output = verbalization,
                metadata = mapOf(
                    "symbol" to symbolName,
                    "file" to (file ?: "unknown"),
                    "detail" to detail
                )
            )
        }
    )
    
    /**
     * Perform contract validation (simulated).
     */
    private suspend fun performContractValidation(
        path: String,
        layerFilter: VslfcLayer?,
        strict: Boolean
    ): List<ContractViolation> {
        // Simulate validation results
        return listOf(
            ContractViolation(
                type = "Layer Boundary Violation",
                description = "CODE layer directly calling VISION layer without going through FLOW",
                location = "$path:45-52",
                layer = VslfcLayer.CODE,
                severity = ViolationSeverity.ERROR
            )
        ).filter { violation ->
            layerFilter == null || violation.layer == layerFilter
        }.filter { violation ->
            strict || violation.severity != ViolationSeverity.WARNING
        }
    }
    
    /**
     * Perform symbol search (simulated).
     */
    private suspend fun performSymbolSearch(
        name: String,
        kind: String,
        layer: String,
        maxResults: Int
    ): List<SymbolInfo> {
        // Simulate search results
        val pattern = name.replace("*", ".*").toRegex(RegexOption.IGNORE_CASE)
        
        return listOf(
            SymbolInfo("UserService", "class", "src/main/kotlin/com/example/UserService.kt:10", VslfcLayer.LOGIC),
            SymbolInfo("UserRepository", "class", "src/main/kotlin/com/example/UserRepository.kt:15", VslfcLayer.CODE),
            SymbolInfo("getUserById", "function", "src/main/kotlin/com/example/UserService.kt:25", VslfcLayer.LOGIC),
            SymbolInfo("validateUser", "function", "src/main/kotlin/com/example/UserValidator.kt:30", VslfcLayer.LOGIC)
        ).filter { symbol ->
            pattern.matches(symbol.name)
        }.filter { symbol ->
            kind == "all" || symbol.kind.lowercase() == kind.lowercase()
        }.filter { symbol ->
            layer == "all" || symbol.layer.name == layer.uppercase()
        }.take(maxResults)
    }
    
    /**
     * Perform verbalization (simulated).
     */
    private suspend fun performVerbalization(
        symbolName: String,
        file: String?,
        detail: String
    ): String {
        // Simulate verbalization
        return buildString {
            appendLine("## ${symbolName}")
            appendLine()
            appendLine("### Purpose")
            appendLine("This symbol is responsible for managing user-related operations in the application.")
            appendLine()
            appendLine("### Behavior")
            appendLine("- Retrieves user data from the repository")
            appendLine("- Validates user input according to business rules")
            appendLine("- Coordinates with other services for complex operations")
            appendLine()
            appendLine("### Relationships")
            appendLine("- **Uses:** UserRepository for data access")
            appendLine("- **Used by:** UserController for API endpoints")
            appendLine("- **Implements:** UserContract from the LOGIC layer")
            appendLine()
            appendLine("### VSLFC Layer")
            appendLine("This symbol belongs to the **LOGIC** layer, implementing business rules and validation.")
        }
    }
}

/**
 * Contract violation from validation.
 */
data class ContractViolation(
    val type: String,
    val description: String,
    val location: String,
    val layer: VslfcLayer,
    val severity: ViolationSeverity
)

/**
 * Severity of a contract violation.
 */
enum class ViolationSeverity {
    CRITICAL,
    ERROR,
    WARNING,
    INFO
}

/**
 * Symbol information.
 */
data class SymbolInfo(
    val name: String,
    val kind: String,
    val location: String,
    val layer: VslfcLayer,
    val signature: String? = null
)

/**
 * Flow information.
 */
data class FlowInfo(
    val name: String,
    val description: String,
    val steps: List<String> = emptyList()
)

/**
 * Business rule information.
 */
data class BusinessRuleInfo(
    val description: String,
    val source: String? = null
)
