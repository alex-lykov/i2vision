/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.layer

import com.i2vision.intent.DiscoveryIntent
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.SymbolKind
import com.i2vision.vslfc.VerbalizationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Flow layer verbalizer - transforms call graphs into sequence descriptions.
 * 
 * This verbalizer analyzes function calls, API endpoints, and interactions
 * to produce sequence diagrams and flow descriptions.
 */
class FlowVerbalizer : LayerVerbalizer {
    
    override val layerName: String = "Flow"
    
    override fun canHandle(symbol: Symbol): Boolean {
        // Flow layer handles functions, methods, and API endpoints
        return symbol.kind in listOf(SymbolKind.FUNCTION, SymbolKind.CLASS, SymbolKind.INTERFACE) ||
               symbol.filePath.contains("/api/") ||
               symbol.filePath.contains("/controller/") ||
               symbol.filePath.contains("/handler/") ||
               symbol.filePath.contains("/service/") ||
               symbol.name.contains("Controller") ||
               symbol.name.contains("Handler") ||
               symbol.name.contains("Service") ||
               symbol.name.contains("Endpoint")
    }
    
    override suspend fun verbalize(
        symbol: Symbol,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): VerbalizationResult = withContext(Dispatchers.Default) {
        val sequences = extractSequences(symbol)
        val apiEndpoints = extractApiEndpoints(symbol)
        val interactions = extractInteractions(symbol)
        
        val flowContext = FlowContext(
            sequences = sequences,
            apiEndpoints = apiEndpoints,
            interactions = interactions
        )
        
        val description = generateFlowDescription(flowContext)
        
        VerbalizationResult(
            symbol = symbol,
            description = description,
            confidence = calculateConfidence(symbol, flowContext),
            strategy = intent.verbalization.strategy,
            metadata = mapOf(
                "layer" to "FLOW",
                "sequences_count" to sequences.size.toString(),
                "api_endpoints_count" to apiEndpoints.size.toString(),
                "interactions_count" to interactions.size.toString()
            )
        )
    }
    
    override suspend fun verbalizeAll(
        symbols: List<Symbol>,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {
        return symbols.filter { canHandle(it) }
            .map { verbalize(it, context, intent) }
    }
    
    /**
     * Extract sequence information from function calls.
     */
    private fun extractSequences(symbol: Symbol): List<SequenceInfo> {
        val sequences = mutableListOf<SequenceInfo>()
        
        // Extract function call sequences
        val functionCalls = extractFunctionCalls(symbol.content)
        
        if (functionCalls.isNotEmpty()) {
            val steps = functionCalls.mapIndexed { index, call ->
                SequenceStep(
                    order = index + 1,
                    from = symbol.name,
                    to = call.target,
                    action = call.method,
                    type = if (call.isSynchronous) StepType.SYNCHRONOUS else StepType.ASYNCHRONOUS
                )
            }
            
            sequences.add(
                SequenceInfo(
                    name = "${symbol.name} Execution Flow",
                    steps = steps,
                    participants = (listOf(symbol.name) + steps.map { it.to }).distinct(),
                    description = "Executes ${steps.size} operations in sequence"
                )
            )
        }
        
        // Look for @Sequence annotations
        val sequenceRegex = Regex("""@Sequence\s*\(\s*name\s*=\s*"([^"]+)"\s*\)""")
        sequenceRegex.findAll(symbol.content)
            .forEach { match ->
                sequences.add(
                    SequenceInfo(
                        name = match.groupValues[1],
                        steps = emptyList(),
                        participants = listOf(symbol.name),
                        description = "Defined sequence"
                    )
                )
            }
        
        return sequences
    }
    
    /**
     * Extract function calls from code.
     */
    private fun extractFunctionCalls(content: String): List<FunctionCall> {
        val calls = mutableListOf<FunctionCall>()
        
        // Look for method calls: object.method() or method()
        val callRegex = Regex("""(\w+)\.(\w+)\s*\(([^)]*)\)""")
        callRegex.findAll(content)
            .take(10)
            .forEach { match ->
                val target = match.groupValues[1]
                val method = match.groupValues[2]
                val args = match.groupValues[3]
                
                // Skip common Kotlin/Java methods
                if (method !in listOf("toString", "equals", "hashCode", "println", "print")) {
                    calls.add(
                        FunctionCall(
                            target = target,
                            method = method,
                            arguments = args,
                            isSynchronous = !method.startsWith("async") && !method.startsWith("launch")
                        )
                    )
                }
            }
        
        // Look for suspend function calls (coroutines)
        val suspendRegex = Regex("""(\w+)\s*\(([^)]*)\)""")
        suspendRegex.findAll(content)
            .filter { match ->
                val methodName = match.groupValues[1]
                methodName.first().isLowerCase() && 
                methodName !in listOf("if", "when", "for", "while", "return", "throw")
            }
            .take(5)
            .forEach { match ->
                calls.add(
                    FunctionCall(
                        target = "self",
                        method = match.groupValues[1],
                        arguments = match.groupValues[2],
                        isSynchronous = false
                    )
                )
            }
        
        return calls.distinctBy { "${it.target}.${it.method}" }
    }
    
    /**
     * Data class for function calls.
     */
    private data class FunctionCall(
        val target: String,
        val method: String,
        val arguments: String,
        val isSynchronous: Boolean
    )
    
    /**
     * Extract API endpoint information.
     */
    private fun extractApiEndpoints(symbol: Symbol): List<ApiEndpointInfo> {
        val endpoints = mutableListOf<ApiEndpointInfo>()
        
        // Look for Spring @RequestMapping annotations
        val requestMappingRegex = Regex("""@(?:GetMapping|PostMapping|PutMapping|DeleteMapping|RequestMapping)\s*\(\s*"([^"]+)"\s*\)""")
        requestMappingRegex.findAll(symbol.content)
            .forEach { match ->
                val path = match.groupValues[1]
                val method = when {
                    match.value.contains("GetMapping") -> "GET"
                    match.value.contains("PostMapping") -> "POST"
                    match.value.contains("PutMapping") -> "PUT"
                    match.value.contains("DeleteMapping") -> "DELETE"
                    else -> "ANY"
                }
                
                endpoints.add(
                    ApiEndpointInfo(
                        method = method,
                        path = path,
                        handler = symbol.name,
                        description = "API endpoint: $method $path"
                    )
                )
            }
        
        // Look for Ktor routing
        val ktorRegex = Regex("""(?:get|post|put|delete)\s*\(\s*"([^"]+)"\s*\)\s*\{""")
        ktorRegex.findAll(symbol.content)
            .forEach { match ->
                val path = match.groupValues[1]
                val method = match.value.substringBefore("(").trim().uppercase()
                
                endpoints.add(
                    ApiEndpointInfo(
                        method = method,
                        path = path,
                        handler = symbol.name,
                        description = "Ktor endpoint: $method $path"
                    )
                )
            }
        
        // Look for @Path annotations (JAX-RS)
        val pathRegex = Regex("""@Path\s*\(\s*"([^"]+)"\s*\)""")
        pathRegex.findAll(symbol.content)
            .forEach { match ->
                endpoints.add(
                    ApiEndpointInfo(
                        method = "ANY",
                        path = match.groupValues[1],
                        handler = symbol.name,
                        description = "JAX-RS endpoint: ${match.groupValues[1]}"
                    )
                )
            }
        
        return endpoints
    }
    
    /**
     * Extract interaction information.
     */
    private fun extractInteractions(symbol: Symbol): List<InteractionInfo> {
        val interactions = mutableListOf<InteractionInfo>()
        
        // Look for event publishing
        val eventRegex = Regex("""(?:publish|emit|send)\s*\(\s*(\w+Event|\w+Message)\s*\)""")
        eventRegex.findAll(symbol.content)
            .forEach { match ->
                interactions.add(
                    InteractionInfo(
                        type = "EVENT",
                        participants = listOf(symbol.name, match.groupValues[1]),
                        description = "Publishes ${match.groupValues[1]}"
                    )
                )
            }
        
        // Look for callback/listener patterns
        val callbackRegex = Regex("""(?:callback|listener|observer)\s*\.\s*(\w+)\s*\(""")
        callbackRegex.findAll(symbol.content)
            .forEach { match ->
                interactions.add(
                    InteractionInfo(
                        type = "CALLBACK",
                        participants = listOf(symbol.name, "callback"),
                        description = "Invokes ${match.groupValues[1]} callback"
                    )
                )
            }
        
        // Look for dependency injection
        val injectRegex = Regex("""@(?:Inject|Autowired)\s+(?:lateinit\s+)?var\s+(\w+)\s*:\s*(\w+)""")
        injectRegex.findAll(symbol.content)
            .forEach { match ->
                interactions.add(
                    InteractionInfo(
                        type = "DEPENDENCY",
                        participants = listOf(symbol.name, match.groupValues[2]),
                        description = "Uses ${match.groupValues[2]} via ${match.groupValues[1]}"
                    )
                )
            }
        
        return interactions
    }
    
    /**
     * Generate natural language description from flow context.
     */
    private fun generateFlowDescription(context: FlowContext): String {
        val sb = StringBuilder()
        
        if (context.sequences.isNotEmpty()) {
            val seqSummary = context.sequences
                .map { "${it.name}(${it.steps.size} steps)" }
                .joinToString(", ")
            sb.append("Sequences: $seqSummary. ")
        }
        
        if (context.apiEndpoints.isNotEmpty()) {
            val endpointSummary = context.apiEndpoints
                .map { "${it.method} ${it.path}" }
                .take(3)
                .joinToString(", ")
            sb.append("Endpoints: $endpointSummary. ")
        }
        
        if (context.interactions.isNotEmpty()) {
            val interactionTypes = context.interactions
                .groupBy { it.type }
                .mapValues { it.value.size }
                .map { "${it.value} ${it.key}" }
                .joinToString(", ")
            sb.append("Interactions: $interactionTypes. ")
        }
        
        if (context.sequences.isEmpty() && context.apiEndpoints.isEmpty() && context.interactions.isEmpty()) {
            sb.append("Implements standard execution flow with method interactions.")
        }
        
        return sb.toString().trim()
    }
    
    /**
     * Calculate confidence based on flow analysis quality.
     */
    private fun calculateConfidence(symbol: Symbol, context: FlowContext): Double {
        var confidence = 0.5
        
        // Higher confidence if we found sequences
        if (context.sequences.isNotEmpty()) {
            confidence += 0.2
        }
        
        // Higher confidence if we found API endpoints
        if (context.apiEndpoints.isNotEmpty()) {
            confidence += 0.15
        }
        
        // Higher confidence if we found interactions
        if (context.interactions.isNotEmpty()) {
            confidence += 0.15
        }
        
        return minOf(confidence, 0.95)
    }
}
