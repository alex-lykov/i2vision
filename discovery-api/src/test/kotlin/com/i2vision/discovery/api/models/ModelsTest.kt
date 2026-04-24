/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.api.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `DiscoveryDepth enum has all values`() {
        val values = DiscoveryDepth.entries
        assertEquals(3, values.size)
        assertTrue(values.contains(DiscoveryDepth.BROWSE))
        assertTrue(values.contains(DiscoveryDepth.STANDARD))
        assertTrue(values.contains(DiscoveryDepth.DEEP))
    }

    @Test
    fun `DiscoveryGoal enum has all values`() {
        val values = DiscoveryGoal.entries
        assertEquals(5, values.size)
        assertTrue(values.contains(DiscoveryGoal.UNDERSTAND))
        assertTrue(values.contains(DiscoveryGoal.ANALYZE))
        assertTrue(values.contains(DiscoveryGoal.VALIDATE))
        assertTrue(values.contains(DiscoveryGoal.GENERATE))
        assertTrue(values.contains(DiscoveryGoal.REFACTOR))
    }

    @Test
    fun `IntentDepth enum has all values`() {
        val values = IntentDepth.entries
        assertEquals(3, values.size)
        assertTrue(values.contains(IntentDepth.BROWSE))
        assertTrue(values.contains(IntentDepth.STANDARD))
        assertTrue(values.contains(IntentDepth.DEEP))
    }

    @Test
    fun `DiscoveryQuality enum has all values`() {
        val values = DiscoveryQuality.entries
        assertEquals(3, values.size)
        assertTrue(values.contains(DiscoveryQuality.FAST))
        assertTrue(values.contains(DiscoveryQuality.BALANCED))
        assertTrue(values.contains(DiscoveryQuality.THOROUGH))
    }

    @Test
    fun `PipelineResult can be instantiated`() {
        val result = PipelineResult(
            success = true,
            artifacts = listOf(
                DiscoveryArtifact(
                    layer = "code",
                    path = "test.kt",
                    content = "content",
                    confidence = 0.9
                )
            ),
            errors = emptyList(),
            metadata = mapOf("key" to "value")
        )
        assertTrue(result.success)
        assertEquals(1, result.artifacts.size)
    }

    @Test
    fun `PipelineResult can be serialized`() {
        val result = PipelineResult(success = true)
        val serialized = json.encodeToString(result)
        assertTrue(serialized.contains("\"success\":true"))
    }

    @Test
    fun `DiscoveryIntent can be instantiated`() {
        val intent = DiscoveryIntent(
            goal = DiscoveryGoal.UNDERSTAND,
            depth = IntentDepth.STANDARD,
            quality = DiscoveryQuality.BALANCED
        )
        assertEquals(DiscoveryGoal.UNDERSTAND, intent.goal)
        assertEquals(IntentDepth.STANDARD, intent.depth)
        assertEquals(DiscoveryQuality.BALANCED, intent.quality)
    }

    @Test
    fun `DiscoveryIntent can be serialized`() {
        val intent = DiscoveryIntent(
            goal = DiscoveryGoal.UNDERSTAND,
            depth = IntentDepth.STANDARD,
            quality = DiscoveryQuality.BALANCED
        )
        val serialized = json.encodeToString(intent)
        assertTrue(serialized.contains("\"goal\":\"UNDERSTAND\""))
    }

    @Test
    fun `ContractHint can be instantiated`() {
        val hint = ContractHint(
            contractPath = "contract.yaml",
            relatedFiles = listOf("file1.kt", "file2.kt"),
            entryPoints = listOf("main")
        )
        assertEquals("contract.yaml", hint.contractPath)
        assertEquals(2, hint.relatedFiles.size)
    }

    @Test
    fun `ModifiableParameterSet can be instantiated`() {
        val params = ModifiableParameterSet(
            name = "test",
            description = "test params",
            parameters = emptyMap()
        )
        assertEquals("test", params.name)
        assertEquals("test params", params.description)
    }
}
