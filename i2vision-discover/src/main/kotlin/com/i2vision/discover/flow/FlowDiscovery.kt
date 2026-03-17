package com.i2vision.discover.flow

import com.i2vision.index.CustomIndex
import com.i2vision.index.IndexProvider
import com.i2vision.index.SymbolInfo
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Flow discovery using call graph analysis.
 * 
 * Discovers flows by analyzing method calls and building execution paths.
 */
class FlowDiscovery(
    private val projectRoot: String,
    private val indexProvider: IndexProvider = CustomIndex(projectRoot)
) {
    
    private val log = LoggerFactory.getLogger(FlowDiscovery::class.java)
    
    /**
     * Discover flows from a call graph.
     * 
     * @param symbols List of symbols to analyze
     * @param clusterId Optional cluster ID for context
     * @return List of discovered flows
     */
    fun discoverFlows(
        symbols: List<SymbolInfo>,
        clusterId: String? = null
    ): List<Flow> {
        log.info("[FLOW_DISCOVERY] Discovering flows from {} symbols", symbols.size)
        
        // Skip flow discovery for empty symbol lists
        if (symbols.isEmpty()) {
            log.warn("[FLOW_DISCOVERY] No symbols provided for flow discovery, skipping")
            return emptyList()
        }
        
        // Skip flow discovery for large symbol sets to avoid performance issues
        if (symbols.size > 2000) {
            log.debug("[FLOW_DISCOVERY] Skipping flow discovery for {} symbols (too large)", symbols.size)
            return emptyList()
        }
        
        val flows = mutableListOf<Flow>()
        
        // Group symbols by file
        val symbolsByFile = symbols.groupBy { it.file }
        
        // Cache file content to avoid re-reading
        val fileContentCache = mutableMapOf<File, String>()
        
        // Build call graph
        val callGraph = try {
            buildCallGraph(symbols, fileContentCache)
        } catch (e: Exception) {
            log.error("[FLOW_DISCOVERY] Failed to build call graph for cluster '{}': {}", clusterId ?: "unknown", e.message)
            return emptyList()  // Return empty, don't fail the whole cluster
        }
        
        // Skip if call graph is empty
        if (callGraph.isEmpty()) {
            log.warn("[FLOW_DISCOVERY] No call graph nodes found for cluster '{}', skipping flow discovery", clusterId ?: "unknown")
            return emptyList()
        }
        
        // Find entry points (public functions, main functions)
        val entryPoints = findEntryPoints(symbols, fileContentCache)
        
        // Skip if no entry points found
        if (entryPoints.isEmpty()) {
            log.warn("[FLOW_DISCOVERY] No entry points found for cluster '{}', skipping flow discovery", clusterId ?: "unknown")
            return emptyList()
        }
        
        // Trace flows from entry points
        entryPoints.forEach { entryPoint ->
            val flow = traceFlow(entryPoint, callGraph, symbolsByFile)
            if (flow.steps.isNotEmpty()) {
                flows.add(flow)
            }
        }
        
        // Discover flows based on common patterns
        flows.addAll(discoverPatternFlows(symbols, callGraph))
        
        log.info("[FLOW_DISCOVERY] Discovered {} flows", flows.size)
        
        return flows
    }
    
    /**
     * Build a call graph from symbols.
     * 
     * @param symbols List of symbols
     * @param fileContentCache Cache for file content to avoid re-reading
     * @return Map of symbol name to list of called symbols
     */
    private fun buildCallGraph(symbols: List<SymbolInfo>, fileContentCache: MutableMap<File, String>): Map<String, List<String>> {
        val callGraph = mutableMapOf<String, MutableList<String>>()
        
        // For each function symbol, find its callees
        symbols.filter { it.kind == "fun" }.forEach { symbol ->
            val callees = findCallees(symbol, fileContentCache)
            callGraph[symbol.qualifiedName] = callees.toMutableList()
        }
        
        return callGraph
    }
    
    /**
     * Find functions called by a given symbol.
     * 
     * @param symbol The symbol to analyze
     * @param fileContentCache Cache for file content to avoid re-reading
     * @return List of qualified names of called functions
     */
    private fun findCallees(symbol: SymbolInfo, fileContentCache: MutableMap<File, String>): List<String> {
        val callees = mutableListOf<String>()
        
        try {
            // Get or cache file content
            val content = fileContentCache.getOrPut(symbol.file) {
                symbol.file.readText()
            }
            
            // Simple regex-based call detection (can be enhanced with AST analysis)
            val functionCallPattern = Regex("""(\w+)\(""")
            val matches = functionCallPattern.findAll(content)
            
            matches.forEach { match ->
                val functionName = match.groupValues[1]
                // Filter out Kotlin keywords and local variables
                if (isValidFunctionCall(functionName, content)) {
                    // Try to resolve to qualified name
                    val qualifiedName = resolveQualifiedName(functionName, symbol)
                    if (qualifiedName != null) {
                        callees.add(qualifiedName)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("[FLOW_DISCOVERY] Failed to find callees for {}: {}", symbol.qualifiedName, e.message)
        }
        
        return callees
    }
    
    /**
     * Check if a function call is valid (not a keyword or local variable).
     * 
     * @param functionName The function name to check
     * @param content The file content
     * @return true if valid function call
     */
    private fun isValidFunctionCall(functionName: String, content: String): Boolean {
        val keywords = setOf(
            "if", "else", "when", "for", "while", "do", "return",
            "break", "continue", "try", "catch", "finally", "throw",
            "val", "var", "fun", "class", "object", "interface", "enum",
            "package", "import", "as", "is", "in", "super", "this",
            "true", "false", "null", "it", "also", "apply", "let", "run", "with"
        )
        
        if (functionName in keywords) return false
        
        // Check if it's a local variable (simple heuristic)
        val localVarPattern = Regex("""val\s+$functionName\s*=""")
        if (localVarPattern.containsMatchIn(content)) return false
        
        return true
    }
    
    /**
     * Resolve a function name to its qualified name.
     * 
     * @param functionName The function name
     * @param contextSymbol The context symbol
     * @return Qualified name or null if not found
     */
    private fun resolveQualifiedName(functionName: String, contextSymbol: SymbolInfo): String? {
        // Try to find the function in the same file
        val symbolsInFile = indexProvider.symbolsInFile(contextSymbol.file)
        val localMatch = symbolsInFile.find { it.name == functionName && it.kind == "fun" }
        if (localMatch != null) return localMatch.qualifiedName
        
        // Try to find in imports (simplified)
        // In a real implementation, this would parse imports and resolve properly
        
        return null
    }
    
    /**
     * Find entry points in the symbol list.
     * 
     * @param symbols List of symbols
     * @param fileContentCache Cache for file content to avoid re-reading
     * @return List of entry point symbols
     */
    private fun findEntryPoints(symbols: List<SymbolInfo>, fileContentCache: MutableMap<File, String> = mutableMapOf()): List<SymbolInfo> {
        return symbols.filter { symbol ->
            symbol.kind == "fun" && (
                symbol.name == "main" ||
                symbol.name.startsWith("handle") ||
                symbol.name.startsWith("process") ||
                symbol.name.startsWith("execute") ||
                symbol.name.startsWith("run") ||
                isPublicFunction(symbol, fileContentCache)
            )
        }
    }
    
    /**
     * Check if a function is public (no private modifier).
     * 
     * @param symbol The symbol to check
     * @param fileContentCache Cache for file content to avoid re-reading
     * @return true if public
     */
    private fun isPublicFunction(symbol: SymbolInfo, fileContentCache: MutableMap<File, String>): Boolean {
        try {
            val content = fileContentCache.getOrPut(symbol.file) {
                symbol.file.readText()
            }
            val lines = content.lines()
            val functionLine = lines.getOrNull(symbol.line - 1) ?: return false
            return !functionLine.trim().startsWith("private")
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * Trace a flow from an entry point through the call graph.
     * 
     * @param entryPoint The entry point symbol
     * @param callGraph The call graph
     * @param symbolsByFile Symbols grouped by file
     * @return Flow object representing the execution path
     */
    private fun traceFlow(
        entryPoint: SymbolInfo,
        callGraph: Map<String, List<String>>,
        symbolsByFile: Map<File, List<SymbolInfo>>
    ): Flow {
        val steps = mutableListOf<FlowStep>()
        val visited = mutableSetOf<String>()
        
        fun trace(symbolName: String, depth: Int) {
            if (depth > 20 || symbolName in visited) return // Prevent infinite recursion
            visited.add(symbolName)
            
            // Find the symbol
            val symbol = symbolsByFile.values.flatten().find { it.qualifiedName == symbolName }
            if (symbol == null) return
            
            steps.add(FlowStep(
                symbolName = symbol.name,
                qualifiedName = symbol.qualifiedName,
                file = symbol.file.relativeTo(File(projectRoot)).path,
                line = symbol.line
            ))
            
            // Trace callees
            val callees = callGraph[symbolName] ?: emptyList()
            callees.forEach { callee ->
                trace(callee, depth + 1)
            }
        }
        
        trace(entryPoint.qualifiedName, 0)
        
        return Flow(
            id = generateFlowId(entryPoint),
            name = entryPoint.name,
            entryPoint = entryPoint.qualifiedName,
            steps = steps
        )
    }
    
    /**
     * Discover flows based on common patterns.
     * 
     * @param symbols List of symbols
     * @param callGraph The call graph
     * @return List of pattern-based flows
     */
    private fun discoverPatternFlows(
        symbols: List<SymbolInfo>,
        callGraph: Map<String, List<String>>
    ): List<Flow> {
        val flows = mutableListOf<Flow>()
        
        // Pattern 1: Request-Response flows
        val requestHandlers = symbols.filter { 
            it.kind == "fun" && (
                it.name.contains("request") ||
                it.name.contains("handle") ||
                it.name.contains("response")
            )
        }
        
        requestHandlers.forEach { handler ->
            val steps = mutableListOf<FlowStep>()
            steps.add(FlowStep(
                symbolName = handler.name,
                qualifiedName = handler.qualifiedName,
                file = handler.file.relativeTo(File(projectRoot)).path,
                line = handler.line
            ))
            
            flows.add(Flow(
                id = generateFlowId(handler),
                name = "Request: ${handler.name}",
                entryPoint = handler.qualifiedName,
                steps = steps,
                pattern = "request-response"
            ))
        }
        
        // Pattern 2: Data transformation flows
        val transformers = symbols.filter {
            it.kind == "fun" && (
                it.name.contains("transform") ||
                it.name.contains("convert") ||
                it.name.contains("map") ||
                it.name.contains("parse")
            )
        }
        
        transformers.forEach { transformer ->
            val steps = mutableListOf<FlowStep>()
            steps.add(FlowStep(
                symbolName = transformer.name,
                qualifiedName = transformer.qualifiedName,
                file = transformer.file.relativeTo(File(projectRoot)).path,
                line = transformer.line
            ))
            
            flows.add(Flow(
                id = generateFlowId(transformer),
                name = "Transform: ${transformer.name}",
                entryPoint = transformer.qualifiedName,
                steps = steps,
                pattern = "transformation"
            ))
        }
        
        return flows
    }
    
    /**
     * Generate a unique flow ID from a symbol.
     * 
     * @param symbol The symbol
     * @return Unique flow ID
     */
    private fun generateFlowId(symbol: SymbolInfo): String {
        return "flow_${symbol.name.lowercase()}_${symbol.line}"
    }
}

/**
 * Represents a discovered flow.
 */
data class Flow(
    val id: String,
    val name: String,
    val entryPoint: String,
    val steps: List<FlowStep>,
    val pattern: String? = null
)

/**
 * Represents a step in a flow.
 */
data class FlowStep(
    val symbolName: String,
    val qualifiedName: String,
    val file: String,
    val line: Int
)
