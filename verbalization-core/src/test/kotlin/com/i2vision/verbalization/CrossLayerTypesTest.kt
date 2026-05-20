/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization

import com.i2vision.arch.signature.StructuralRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Test suite for cross-layer enrichment types.
 * Validates ExtendedStructureContext, ExtendedFlowContext, ExtendedLogicContext,
 * and CrossLayerEnrichment service.
 */
@DisplayName("Cross-Layer Enrichment Types Tests")
class CrossLayerTypesTest {

    @Nested
    @DisplayName("DependencyGraph Tests")
    inner class DependencyGraphTests {

        @Test
        fun `should detect no cycle in simple graph`() {
            val graph = DependencyGraph(
                nodes = listOf("A", "B", "C"),
                edges = listOf(
                    DependencyEdge("A", "B"),
                    DependencyEdge("B", "C")
                )
            )

            assertFalse(graph.hasCycle())
        }

        @Test
        fun `should detect cycle in graph`() {
            val graph = DependencyGraph(
                nodes = listOf("A", "B", "C"),
                edges = listOf(
                    DependencyEdge("A", "B"),
                    DependencyEdge("B", "C"),
                    DependencyEdge("C", "A")
                )
            )

            assertTrue(graph.hasCycle())
        }

        @Test
        fun `should detect self-referential cycle`() {
            val graph = DependencyGraph(
                nodes = listOf("A"),
                edges = listOf(
                    DependencyEdge("A", "A")
                )
            )

            assertTrue(graph.hasCycle())
        }

        @Test
        fun `should get transitive closure`() {
            val graph = DependencyGraph(
                nodes = listOf("A", "B", "C", "D"),
                edges = listOf(
                    DependencyEdge("A", "B"),
                    DependencyEdge("B", "C"),
                    DependencyEdge("C", "D")
                )
            )

            val closure = graph.getTransitiveClosure("A")
            assertEquals(setOf("B", "C", "D"), closure)
        }

        @Test
        fun `should return empty set for isolated node`() {
            val graph = DependencyGraph(
                nodes = listOf("A", "B"),
                edges = listOf(DependencyEdge("B", "C"))
            )

            val closure = graph.getTransitiveClosure("A")
            assertTrue(closure.isEmpty())
        }
    }

    @Nested
    @DisplayName("ExtendedStructureContext Tests")
    inner class ExtendedStructureContextTests {

        @Test
        fun `should get dependents of component`() {
            val context = ExtendedStructureContext(
                components = listOf(
                    ComponentInfo("ServiceA", "component", StructuralRole.SERVICE, emptyList(), ""),
                    ComponentInfo("ServiceB", "component", StructuralRole.SERVICE, emptyList(), ""),
                    ComponentInfo("Controller", "component", StructuralRole.CONTROLLER, emptyList(), "")
                ),
                dependencies = listOf(
                    DependencyInfo("ServiceA", "ServiceB", "internal", DependencyStrength.MODERATE),
                    DependencyInfo("Controller", "ServiceA", "internal", DependencyStrength.MODERATE)
                ),
                dependencyGraph = DependencyGraph(
                    nodes = listOf("ServiceA", "ServiceB", "Controller"),
                    edges = listOf(
                        DependencyEdge("ServiceA", "ServiceB"),
                        DependencyEdge("Controller", "ServiceA")
                    )
                )
            )

            val dependents = context.getDependentsOf("ServiceA")
            assertTrue(dependents.contains("Controller"))
            assertFalse(dependents.contains("ServiceB"))
        }

        @Test
        fun `should get dependencies of component`() {
            val context = ExtendedStructureContext(
                components = listOf(
                    ComponentInfo("ServiceA", "component", StructuralRole.SERVICE, emptyList(), ""),
                    ComponentInfo("ServiceB", "component", StructuralRole.SERVICE, emptyList(), "")
                ),
                dependencies = listOf(
                    DependencyInfo("ServiceA", "ServiceB", "internal", DependencyStrength.MODERATE)
                ),
                dependencyGraph = DependencyGraph(
                    nodes = listOf("ServiceA", "ServiceB"),
                    edges = listOf(DependencyEdge("ServiceA", "ServiceB"))
                )
            )

            val dependencies = context.getDependenciesOf("ServiceA")
            assertTrue(dependencies.contains("ServiceB"))
        }

        @Test
        fun `should detect component layer`() {
            val context = ExtendedStructureContext(
                components = listOf(
                    ComponentInfo("UserController", "component", StructuralRole.CONTROLLER, emptyList(), ""),
                    ComponentInfo("OrderService", "component", StructuralRole.SERVICE, emptyList(), ""),
                    ComponentInfo("UserRepository", "component", StructuralRole.REPOSITORY, emptyList(), "")
                )
            )

            assertEquals("presentation", context.getComponentLayer("UserController"))
            assertEquals("domain", context.getComponentLayer("OrderService"))
            assertEquals("data", context.getComponentLayer("UserRepository"))
        }

        @Test
        fun `should return null for unknown component`() {
            val context = ExtendedStructureContext()

            assertNull(context.getComponentLayer("UnknownComponent"))
        }
    }

    @Nested
    @DisplayName("ExtendedFlowContext Tests")
    inner class ExtendedFlowContextTests {

        private fun createSampleSequences(): List<ExtendedSequenceInfo> {
            return listOf(
                ExtendedSequenceInfo(
                    name = "LoginFlow",
                    steps = emptyList(),
                    participants = listOf("AuthService", "UserRepository"),
                    description = "User authentication flow",
                    calledBy = listOf("ApiGateway"),
                    callsTo = listOf("AuthService", "UserRepository")
                ),
                ExtendedSequenceInfo(
                    name = "AuthService",
                    steps = emptyList(),
                    participants = listOf("TokenValidator"),
                    description = "Authentication service",
                    calledBy = listOf("LoginFlow"),
                    callsTo = listOf("TokenValidator")
                )
            )
        }

        @Test
        fun `should get flows called by given flow`() {
            val sequences = createSampleSequences()
            val context = sequences.withCallingSequences()

            val callingFlows = context.getFlowsCalling("LoginFlow")
            assertTrue(callingFlows.contains("AuthService"))
            assertTrue(callingFlows.contains("UserRepository"))
        }

        @Test
        fun `should get flows that call given flow`() {
            val sequences = createSampleSequences()
            val context = sequences.withCallingSequences()

            val calledByFlows = context.getFlowsCalledBy("AuthService")
            assertTrue(calledByFlows.contains("LoginFlow"))
        }

        @Test
        fun `should return empty list for unknown flow`() {
            val context = ExtendedFlowContext()

            val callingFlows = context.getFlowsCalling("UnknownFlow")
            assertTrue(callingFlows.isEmpty())
        }
    }

    @Nested
    @DisplayName("ExtendedLogicContext Tests")
    inner class ExtendedLogicContextTests {

        @Test
        fun `should get rules enforced by symbol`() {
            val ruleReferences = mapOf(
                "UserService" to listOf(
                    RuleReference(
                        ruleName = "auth_required",
                        description = "User must be authenticated",
                        enforcedBy = listOf("UserService"),
                        severity = RuleSeverity.CRITICAL
                    )
                )
            )

            val context = ExtendedLogicContext(
                ruleReferences = ruleReferences
            )

            val rules = context.getRulesEnforcedBy("UserService")
            assertEquals(1, rules.size)
            assertEquals("auth_required", rules[0].ruleName)
        }

        @Test
        fun `should get enforcers of rule`() {
            val ruleReferences = mapOf(
                "UserService" to listOf(
                    RuleReference(
                        ruleName = "auth_required",
                        description = "User must be authenticated",
                        severity = RuleSeverity.CRITICAL
                    )
                ),
                "AdminService" to listOf(
                    RuleReference(
                        ruleName = "auth_required",
                        description = "User must be authenticated",
                        severity = RuleSeverity.CRITICAL
                    )
                )
            )

            val context = ExtendedLogicContext(
                ruleReferences = ruleReferences
            )

            val enforcers = context.getEnforcersOfRule("auth_required")
            assertEquals(2, enforcers.size)
            assertTrue(enforcers.contains("UserService"))
            assertTrue(enforcers.contains("AdminService"))
        }

        @Test
        fun `should get rules by severity`() {
            val ruleReferences = mapOf(
                "ServiceA" to listOf(
                    RuleReference(
                        ruleName = "critical_rule",
                        description = "Critical rule",
                        severity = RuleSeverity.CRITICAL
                    ),
                    RuleReference(
                        ruleName = "low_rule",
                        description = "Low rule",
                        severity = RuleSeverity.LOW
                    )
                )
            )

            val context = ExtendedLogicContext(
                ruleReferences = ruleReferences
            )

            val criticalRules = context.getRulesBySeverity(RuleSeverity.CRITICAL)
            assertEquals(1, criticalRules.size)
            assertEquals("critical_rule", criticalRules[0].ruleName)
        }

        @Test
        fun `should check for critical rules`() {
            val ruleReferences = mapOf(
                "ServiceA" to listOf(
                    RuleReference(
                        ruleName = "non_critical",
                        description = "Non-critical",
                        severity = RuleSeverity.LOW
                    )
                ),
                "ServiceB" to listOf(
                    RuleReference(
                        ruleName = "critical",
                        description = "Critical",
                        severity = RuleSeverity.CRITICAL
                    )
                )
            )

            val context = ExtendedLogicContext(ruleReferences = ruleReferences)

            assertFalse(context.hasCriticalRules("ServiceA"))
            assertTrue(context.hasCriticalRules("ServiceB"))
        }
    }

    @Nested
    @DisplayName("CrossLayerEnrichment Tests")
    inner class CrossLayerEnrichmentTests {

        private val enrichment = CrossLayerEnrichment()

        @Test
        fun `should enrich description with flow info`() {
            val flowContext = ExtendedFlowContext(
                sequences = listOf(
                    ExtendedSequenceInfo(
                        name = "LoginFlow",
                        steps = emptyList(),
                        participants = emptyList(),
                        description = "Login flow",
                        calledBy = listOf("ApiGateway"),
                        callsTo = emptyList()
                    )
                )
            )

            val result = enrichment.enrich(
                symbolName = "LoginFlow",
                baseDescription = "Authenticates user",
                flowContext = flowContext
            )

            assertTrue(result.enrichedDescription.contains("Called by"))
            assertTrue(result.isEnriched)
            assertEquals(1, result.addedFlowInfo.size)
        }

        @Test
        fun `should enrich description with rule info`() {
            val logicContext = ExtendedLogicContext(
                ruleReferences = mapOf(
                    "AuthService" to listOf(
                        RuleReference(
                            ruleName = "auth_required",
                            description = "Authentication required",
                            severity = RuleSeverity.CRITICAL
                        )
                    )
                )
            )

            val result = enrichment.enrich(
                symbolName = "AuthService",
                baseDescription = "Authenticates user",
                logicContext = logicContext
            )

            assertTrue(result.enrichedDescription.contains("Enforces"))
            assertTrue(result.enrichedDescription.contains("auth_required"))
        }

        @Test
        fun `should enrich description with dependency info`() {
            val structureContext = ExtendedStructureContext(
                dependencyGraph = DependencyGraph(
                    nodes = listOf("AuthService", "TokenValidator"),
                    edges = listOf(DependencyEdge("AuthService", "TokenValidator"))
                )
            )

            val result = enrichment.enrich(
                symbolName = "AuthService",
                baseDescription = "Authenticates user",
                structureContext = structureContext
            )

            assertTrue(result.enrichedDescription.contains("Depends on"))
            assertTrue(result.enrichedDescription.contains("TokenValidator"))
        }

        @Test
        fun `should enrich with multiple layers`() {
            val structureContext = ExtendedStructureContext(
                dependencyGraph = DependencyGraph(
                    nodes = listOf("AuthService", "TokenValidator", "UserRepository"),
                    edges = listOf(
                        DependencyEdge("AuthService", "TokenValidator"),
                        DependencyEdge("AuthService", "UserRepository")
                    )
                )
            )

            val flowContext = ExtendedFlowContext(
                sequences = listOf(
                    ExtendedSequenceInfo(
                        name = "AuthService",
                        steps = emptyList(),
                        participants = emptyList(),
                        description = "Auth service",
                        calledBy = listOf("ApiGateway", "AdminPanel"),
                        callsTo = emptyList()
                    )
                )
            )

            val logicContext = ExtendedLogicContext(
                ruleReferences = mapOf(
                    "AuthService" to listOf(
                        RuleReference(
                            ruleName = "auth_required",
                            description = "Must authenticate",
                            severity = RuleSeverity.CRITICAL
                        )
                    )
                )
            )

            val result = enrichment.enrich(
                symbolName = "AuthService",
                baseDescription = "Authenticates user credentials",
                structureContext = structureContext,
                flowContext = flowContext,
                logicContext = logicContext
            )

            assertTrue(result.isEnriched)
            assertTrue(result.enrichedDescription.contains("Called by"))
            assertTrue(result.enrichedDescription.contains("Enforces"))
            assertTrue(result.enrichedDescription.contains("Depends on"))
            assertEquals(3, result.enrichmentCount)
        }

        @Test
        fun `should return original when no enrichment available`() {
            val result = enrichment.enrich(
                symbolName = "UnknownService",
                baseDescription = "Does something"
            )

            assertFalse(result.isEnriched)
            assertEquals("Does something", result.enrichedDescription)
            assertEquals(0, result.enrichmentCount)
        }

        @Test
        fun `should generate before-after example`() {
            val structureContext = ExtendedStructureContext(
                dependencyGraph = DependencyGraph(
                    nodes = listOf("AuthService", "TokenValidator"),
                    edges = listOf(DependencyEdge("AuthService", "TokenValidator"))
                )
            )

            val example = enrichment.generateBeforeAfterExample(
                symbolName = "AuthService",
                baseDescription = "Authenticates user credentials",
                structureContext = structureContext,
                flowContext = ExtendedFlowContext(),
                logicContext = ExtendedLogicContext()
            )

            assertTrue(example.contains("Before (first pass)"))
            assertTrue(example.contains("After (cross-layer enrichment)"))
            assertTrue(example.contains("Authenticates user credentials"))
            assertTrue(example.contains("Depends on: TokenValidator"))
        }
    }

    @Nested
    @DisplayName("Extension Functions Tests")
    inner class ExtensionFunctionsTests {

        @Test
        fun `should add edges to ExtendedStructureContext`() {
            val context = ExtendedStructureContext()
            val edges = listOf(
                DependencyEdge("A", "B"),
                DependencyEdge("B", "C")
            )

            val updated = context.withEdges(edges)

            assertEquals(2, updated.dependencyGraph.edges.size)
            assertEquals(3, updated.dependencyGraph.nodes.size)
        }

        @Test
        fun `should add rule to ExtendedLogicContext`() {
            val context = ExtendedLogicContext()
            val rule = RuleReference(
                ruleName = "test_rule",
                description = "Test rule"
            )

            val updated = context.withRule("TestService", rule)

            val rules = updated.getRulesEnforcedBy("TestService")
            assertEquals(1, rules.size)
            assertEquals("test_rule", rules[0].ruleName)
        }
    }
}