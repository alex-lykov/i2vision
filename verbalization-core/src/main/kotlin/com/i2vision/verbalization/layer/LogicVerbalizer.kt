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
 * Logic layer verbalizer - transforms invariants/rules into decision tables.
 * 
 * This verbalizer analyzes business logic, validation rules, and state machines
 * to produce decision tables and rule descriptions.
 */
class LogicVerbalizer : LayerVerbalizer {
    
    override val layerName: String = "Logic"
    
    override fun canHandle(symbol: Symbol): Boolean {
        // Logic layer handles business rules and validation
        return symbol.kind in listOf(SymbolKind.CLASS, SymbolKind.OBJECT, SymbolKind.FUNCTION) ||
               symbol.filePath.contains("/rule/") ||
               symbol.filePath.contains("/validation/") ||
               symbol.filePath.contains("/business/") ||
               symbol.filePath.contains("/domain/") ||
               symbol.name.contains("Rule") ||
               symbol.name.contains("Validator") ||
               symbol.name.contains("Policy")
    }
    
    override suspend fun verbalize(
        symbol: Symbol,
        context: LayerVerbalizationContext,
        intent: DiscoveryIntent
    ): VerbalizationResult = withContext(Dispatchers.Default) {
        val invariants = extractInvariants(symbol)
        val rules = extractRules(symbol)
        val stateMachines = extractStateMachines(symbol)
        
        val logicContext = LogicContext(
            invariants = invariants,
            rules = rules,
            stateMachines = stateMachines
        )
        
        val description = generateLogicDescription(logicContext)
        
        VerbalizationResult(
            symbol = symbol,
            description = description,
            confidence = calculateConfidence(symbol, logicContext),
            strategy = intent.verbalization.strategy,
            metadata = mapOf(
                "layer" to "LOGIC",
                "invariants_count" to invariants.size.toString(),
                "rules_count" to rules.size.toString(),
                "state_machines_count" to stateMachines.size.toString()
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
     * Extract invariants from code (conditions that must always be true).
     */
    private fun extractInvariants(symbol: Symbol): List<InvariantInfo> {
        val invariants = mutableListOf<InvariantInfo>()
        
        // Look for assert statements
        val assertRegex = Regex("""assert\(([^)]+)\)(?:\s*:\s*"([^"]+)")?""")
        assertRegex.findAll(symbol.content)
            .forEach { match ->
                invariants.add(
                    InvariantInfo(
                        condition = match.groupValues[1].trim(),
                        description = match.groupValues[2].ifEmpty { "Invariant must hold" },
                        severity = InvariantSeverity.MANDATORY
                    )
                )
            }
        
        // Look for require/ensure statements (Kotlin contracts)
        val requireRegex = Regex("""require\(([^)]+)\)(?:\s*:\s*"([^"]+)")?""")
        requireRegex.findAll(symbol.content)
            .forEach { match ->
                invariants.add(
                    InvariantInfo(
                        condition = match.groupValues[1].trim(),
                        description = match.groupValues[2].ifEmpty { "Precondition must be satisfied" },
                        severity = InvariantSeverity.MANDATORY
                    )
                )
            }
        
        // Look for check statements
        val checkRegex = Regex("""check\(([^)]+)\)(?:\s*:\s*"([^"]+)")?""")
        checkRegex.findAll(symbol.content)
            .forEach { match ->
                invariants.add(
                    InvariantInfo(
                        condition = match.groupValues[1].trim(),
                        description = match.groupValues[2].ifEmpty { "Condition must be true" },
                        severity = InvariantSeverity.RECOMMENDED
                    )
                )
            }
        
        // Look for @Invariant annotations
        val annotationRegex = Regex("""@Invariant\(([^)]+)\)""")
        annotationRegex.findAll(symbol.content)
            .forEach { match ->
                invariants.add(
                    InvariantInfo(
                        condition = match.groupValues[1].trim(),
                        description = "Domain invariant",
                        severity = InvariantSeverity.MANDATORY
                    )
                )
            }
        
        // Infer invariants from validation logic
        val ifRegex = Regex("""if\s*\(([^)]+)\)\s*\{\s*(?:throw|return\s+false|error)""")
        ifRegex.findAll(symbol.content)
            .take(3)
            .forEach { match ->
                invariants.add(
                    InvariantInfo(
                        condition = match.groupValues[1].trim(),
                        description = "Validation rule",
                        severity = InvariantSeverity.RECOMMENDED
                    )
                )
            }
        
        return invariants
    }
    
    /**
     * Extract business rules from code.
     */
    private fun extractRules(symbol: Symbol): List<RuleInfo> {
        val rules = mutableListOf<RuleInfo>()
        var priority = 1
        
        // Look for when/switch statements (decision logic)
        val whenRegex = Regex("""when\s*\(([^)]+)\)\s*\{((?:[^}]+\})+)""", RegexOption.DOT_MATCHES_ALL)
        whenRegex.findAll(symbol.content)
            .forEach { match ->
                val subject = match.groupValues[1].trim()
                val branches = match.groupValues[2]
                
                // Extract individual branches as rules
                val branchRegex = Regex("""(\w+)\s*->\s*([^}]+)""")
                branchRegex.findAll(branches)
                    .take(5)
                    .forEach { branch ->
                        rules.add(
                            RuleInfo(
                                name = "Decision: $subject",
                                condition = "If $subject is ${branch.groupValues[1]}",
                                action = branch.groupValues[2].trim().take(100),
                                priority = priority++
                            )
                        )
                    }
            }
        
        // Look for if-else chains (business rules)
        val ifElseRegex = Regex("""if\s*\(([^)]+)\)\s*\{([^}]+)\}(?:\s*else\s+if\s*\(([^)]+)\)\s*\{([^}]+)\})*""")
        ifElseRegex.findAll(symbol.content)
            .take(3)
            .forEach { match ->
                rules.add(
                    RuleInfo(
                        name = "Business Rule ${rules.size + 1}",
                        condition = match.groupValues[1].trim(),
                        action = match.groupValues[2].trim().take(100),
                        priority = priority++
                    )
                )
                
                // Handle else-if branches
                if (match.groupValues[3].isNotEmpty()) {
                    rules.add(
                        RuleInfo(
                            name = "Business Rule ${rules.size + 1}",
                            condition = match.groupValues[3].trim(),
                            action = match.groupValues[4].trim().take(100),
                            priority = priority++
                        )
                    )
                }
            }
        
        // Look for @Rule annotations
        val ruleAnnotationRegex = Regex("""@Rule\s*\(\s*name\s*=\s*"([^"]+)"\s*,\s*condition\s*=\s*"([^"]+)"\s*\)""")
        ruleAnnotationRegex.findAll(symbol.content)
            .forEach { match ->
                rules.add(
                    RuleInfo(
                        name = match.groupValues[1],
                        condition = match.groupValues[2],
                        action = "Execute rule logic",
                        priority = priority++
                    )
                )
            }
        
        return rules.ifEmpty { 
            listOf(
                RuleInfo(
                    name = "${symbol.name} Logic",
                    condition = "When ${symbol.name} is invoked",
                    action = "Execute business logic",
                    priority = 1
                )
            )
        }
    }
    
    /**
     * Extract state machines from code.
     */
    private fun extractStateMachines(symbol: Symbol): List<StateMachineInfo> {
        val stateMachines = mutableListOf<StateMachineInfo>()
        
        // Look for enum classes that represent states
        val enumRegex = Regex("""enum\s+class\s+(\w+)\s*\{([^}]+)\}""", RegexOption.DOT_MATCHES_ALL)
        enumRegex.findAll(symbol.content)
            .forEach { match ->
                val name = match.groupValues[1]
                val states = match.groupValues[2]
                    .split(",")
                    .map { it.trim().split("(").first().trim() }
                    .filter { it.isNotEmpty() }
                
                if (states.isNotEmpty() && name.contains("State", ignoreCase = true)) {
                    stateMachines.add(
                        StateMachineInfo(
                            name = name,
                            states = states,
                            transitions = extractTransitions(symbol.content, states)
                        )
                    )
                }
            }
        
        // Look for sealed classes representing states
        val sealedRegex = Regex("""sealed\s+(?:class|interface)\s+(\w+)""")
        sealedRegex.findAll(symbol.content)
            .forEach { match ->
                val name = match.groupValues[1]
                if (name.contains("State", ignoreCase = true)) {
                    // Extract subclasses as states
                    val subclassRegex = Regex("""(?:data\s+)?class\s+(\w+)\s*:\s*$name""")
                    val states = subclassRegex.findAll(symbol.content)
                        .map { it.groupValues[1] }
                        .distinct()
                        .toList()
                    
                    if (states.isNotEmpty()) {
                        stateMachines.add(
                            StateMachineInfo(
                                name = name,
                                states = states,
                                transitions = extractTransitions(symbol.content, states)
                            )
                        )
                    }
                }
            }
        
        return stateMachines
    }
    
    /**
     * Extract state transitions from code.
     */
    private fun extractTransitions(content: String, states: List<String>): List<TransitionInfo> {
        val transitions = mutableListOf<TransitionInfo>()
        
        // Look for state transition patterns
        states.forEach { fromState ->
            states.filter { it != fromState }.forEach { toState ->
                // Look for transition methods
                val transitionRegex = Regex("""fun\s+\w*(?:$fromState\w*${toState}\w*|\w*${toState}\w*)\s*\(""")
                if (transitionRegex.find(content) != null) {
                    transitions.add(
                        TransitionInfo(
                            from = fromState,
                            to = toState,
                            trigger = "Transition triggered",
                            guard = null
                        )
                    )
                }
            }
        }
        
        return transitions.ifEmpty { emptyList() }
    }
    
    /**
     * Generate natural language description from logic context.
     */
    private fun generateLogicDescription(context: LogicContext): String {
        val sb = StringBuilder()
        
        if (context.invariants.isNotEmpty()) {
            sb.append("Invariants: ${context.invariants.size} constraints defined. ")
            sb.append("Key: ${context.invariants.take(2).map { it.description }.joinToString(", ")}. ")
        }
        
        if (context.rules.isNotEmpty()) {
            sb.append("Rules: ${context.rules.size} business rules. ")
            sb.append("Examples: ${context.rules.take(2).map { it.name }.joinToString(", ")}. ")
        }
        
        if (context.stateMachines.isNotEmpty()) {
            val stateSummary = context.stateMachines
                .map { "${it.name}(${it.states.size} states)" }
                .joinToString(", ")
            sb.append("State machines: $stateSummary.")
        }
        
        if (context.invariants.isEmpty() && context.rules.isEmpty() && context.stateMachines.isEmpty()) {
            sb.append("Implements business logic with standard validation and processing rules.")
        }
        
        return sb.toString().trim()
    }
    
    /**
     * Calculate confidence based on logic analysis quality.
     */
    private fun calculateConfidence(symbol: Symbol, context: LogicContext): Double {
        var confidence = 0.5
        
        // Higher confidence if we found invariants
        if (context.invariants.isNotEmpty()) {
            confidence += 0.2
        }
        
        // Higher confidence if we found rules
        if (context.rules.isNotEmpty()) {
            confidence += 0.15
        }
        
        // Higher confidence if we found state machines
        if (context.stateMachines.isNotEmpty()) {
            confidence += 0.15
        }
        
        return minOf(confidence, 0.95)
    }
}
