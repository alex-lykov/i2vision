package com.i2vision.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for IntentParser.
 * Tests CLI argument parsing for discovery intents.
 */
class IntentParserTest {

    @Test
    fun `should parse full_discovery intent correctly`() {
        // Given: CLI arguments with full_discovery intent
        val args = mapOf(
            "intent" to "full_discovery"
        )

        // When
        val intent = IntentParser.parse(args)

        // Then
        assertNotNull(intent, "Intent should not be null")
        assertEquals(IntentGoal.FULL_DISCOVERY, intent.goal)
        assertEquals(LayerFocus.ALL, intent.focus)
        assertEquals(IntentDepth.STANDARD, intent.depth)
        assertEquals(QualityFocus.BALANCED, intent.quality)
    }

    @Test
    fun `should parse intent with custom depth`() {
        // Given: CLI arguments with deep depth
        val args = mapOf(
            "intent" to "full_discovery",
            "depth" to "deep"
        )

        // When
        val intent = IntentParser.parse(args)

        // Then
        assertNotNull(intent)
        assertEquals(IntentGoal.FULL_DISCOVERY, intent.goal)
        assertEquals(IntentDepth.DEEP, intent.depth)
    }

    @Test
    fun `should parse intent with browse depth`() {
        // Given: CLI arguments with browse depth
        val args = mapOf(
            "intent" to "quick_overview",
            "depth" to "browse"
        )

        // When
        val intent = IntentParser.parse(args)

        // Then
        assertNotNull(intent)
        assertEquals(IntentGoal.QUICK_OVERVIEW, intent.goal)
        assertEquals(IntentDepth.BROWSE, intent.depth)
    }

    @Test
    fun `should parse intent with standard depth`() {
        // Given: CLI arguments with standard depth (default)
        val args = mapOf(
            "intent" to "architecture_audit"
        )

        // When
        val intent = IntentParser.parse(args)

        // Then
        assertNotNull(intent)
        assertEquals(IntentGoal.ARCHITECTURE_AUDIT, intent.goal)
        assertEquals(IntentDepth.STANDARD, intent.depth)
    }

    @Test
    fun `should parse intent with custom focus layers`() {
        // Given: CLI arguments with custom focus
        val args = mapOf(
            "intent" to "flow_mapping",
            "focus" to "logic,flow"
        )

        // When
        val intent = IntentParser.parse(args)

        // Then
        assertNotNull(intent)
        assertEquals(IntentGoal.FLOW_MAPPING, intent.goal)
        assertEquals(setOf(LayerFocus.LOGIC, LayerFocus.FLOW), intent.focus)
    }

    @Test
    fun `should return null for invalid intent`() {
        // Given: Invalid intent string
        val args = mapOf(
            "intent" to "invalid_intent"
        )

        // When
        val intent = IntentParser.parse(args)

        // Then
        assertEquals(null, intent)
    }

    @Test
    fun `should return null when intent is missing`() {
        // Given: No intent parameter
        val args = mapOf(
            "depth" to "deep"
        )

        // When
        val intent = IntentParser.parse(args)

        // Then
        assertEquals(null, intent)
    }

    @Test
    fun `should parse quality focus correctly`() {
        // Given: CLI arguments with quality focus
        val args = mapOf(
            "intent" to "refactoring_analysis",
            "quality" to "quality"
        )

        // When
        val intent = IntentParser.parse(args)

        // Then
        assertNotNull(intent)
        assertEquals(QualityFocus.QUALITY, intent.quality)
    }

    @Test
    fun `should validate valid intent`() {
        // Given: Valid intent
        val intent = DiscoveryIntent(
            goal = IntentGoal.FULL_DISCOVERY,
            depth = IntentDepth.STANDARD
        )

        // When
        val errors = intent.validate()

        // Then
        assertTrue(errors.isEmpty(), "Valid intent should have no errors")
    }

    @Test
    fun `should detect invalid intent combination`() {
        // Given: Invalid intent combination (BROWSE + QUALITY)
        val intent = DiscoveryIntent(
            goal = IntentGoal.QUICK_OVERVIEW,
            depth = IntentDepth.BROWSE,
            quality = QualityFocus.QUALITY
        )

        // When
        val errors = intent.validate()

        // Then
        assertTrue(errors.isNotEmpty(), "Invalid combination should have errors")
        assertTrue(errors.any { it.contains("BROWSE depth cannot be combined with QUALITY focus") })
    }
}
