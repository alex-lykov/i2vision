/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.vslfc.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContractModelsTest {

    @Test
    fun `contract metadata should have default values`() {
        val metadata = ContractMetadata(name = "test")

        assertEquals("test", metadata.name)
        assertEquals("1.0", metadata.version)
        assertEquals(listOf("all"), metadata.targetLayers)
        assertEquals(ContractPriority.MEDIUM, metadata.priority)
    }

    @Test
    fun `layer expectations should allow null layers`() {
        val expectations = LayerExpectations(
            vision = VisionExpectations(requiredCapabilities = listOf("auth")),
            structure = null,
            logic = null,
            flow = null,
            code = null
        )

        assertNotNull(expectations.vision)
        assertTrue(expectations.vision.requiredCapabilities.contains("auth"))
    }

    @Test
    fun `discovery config should have sensible defaults`() {
        val config = DiscoveryConfig()

        assertEquals(DiscoveryDepth.STANDARD, config.depth)
        assertEquals(listOf("**/*.kt", "**/*.java"), config.includePatterns)
        assertEquals(listOf("**/test/**", "**/build/**"), config.excludePatterns)
        assertTrue(config.autoDiscover)
    }

    @Test
    fun `quality gates should have default thresholds`() {
        val gates = QualityGates()

        assertEquals(70.0, gates.minCoveragePercentage)
        assertEquals(10, gates.maxOrphanedRules)
        assertEquals(false, gates.enforceTestCoverage)
    }

    @Test
    fun `validation error should have severity`() {
        val error = ValidationError(
            field = "test",
            message = "test error",
            severity = ValidationSeverity.ERROR
        )

        assertEquals("test", error.field)
        assertEquals("test error", error.message)
        assertEquals(ValidationSeverity.ERROR, error.severity)
    }

    @Test
    fun `contract validation result should track timestamp`() {
        val contract = DiscoveryContract(
            metadata = ContractMetadata(name = "test")
        )

        val result = ContractValidationResult(
            contract = contract,
            isValid = true,
            errors = emptyList(),
            warnings = emptyList()
        )

        assertNotNull(result.timestamp)
    }
}
