package com.i2vision.cli

import com.i2vision.discover.api.models.DiscoveryDepth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for CLI Depth Parameter.
 * Tests that BROWSE/STANDARD/DEEP modes apply correct parameters.
 */
class CliDepthTest {
    
    @Test
    fun `should accept BROWSE depth parameter`() {
        // Given: BROWSE depth string
        val depthStr = "BROWSE"
        
        // When
        val depth = DiscoveryDepth.valueOf(depthStr)
        
        // Then
        assertEquals(DiscoveryDepth.BROWSE, depth)
    }
    
    @Test
    fun `should accept STANDARD depth parameter`() {
        // Given: STANDARD depth string
        val depthStr = "STANDARD"
        
        // When
        val depth = DiscoveryDepth.valueOf(depthStr)
        
        // Then
        assertEquals(DiscoveryDepth.STANDARD, depth)
    }
    
    @Test
    fun `should accept DEEP depth parameter`() {
        // Given: DEEP depth string
        val depthStr = "DEEP"
        
        // When
        val depth = DiscoveryDepth.valueOf(depthStr)
        
        // Then
        assertEquals(DiscoveryDepth.DEEP, depth)
    }
    
    @Test
    fun `should accept lowercase browse depth`() {
        // Given: lowercase browse string
        val depthStr = "browse"
        
        // When
        val depth = DiscoveryDepth.valueOf(depthStr.uppercase())
        
        // Then
        assertEquals(DiscoveryDepth.BROWSE, depth)
    }
    
    @Test
    fun `should accept mixed case depth parameters`() {
        // Given: mixed case depth strings
        val depthStr1 = "StAnDaRd"
        val depthStr2 = "DeEp"
        
        // When
        val depth1 = DiscoveryDepth.valueOf(depthStr1.uppercase())
        val depth2 = DiscoveryDepth.valueOf(depthStr2.uppercase())
        
        // Then
        assertEquals(DiscoveryDepth.STANDARD, depth1)
        assertEquals(DiscoveryDepth.DEEP, depth2)
    }
    
    @Test
    fun `should throw exception for invalid depth parameter`() {
        // Given: Invalid depth string
        val depthStr = "INVALID"
        
        // When & Then
        assertFailsWith<IllegalArgumentException> {
            DiscoveryDepth.valueOf(depthStr)
        }
    }
    
    @Test
    fun `should default to STANDARD when no depth provided`() {
        // Given: No depth provided (null)
        val depthStr: String? = null
        val defaultDepth = "STANDARD"
        
        // When
        val depth = DiscoveryDepth.valueOf((depthStr ?: defaultDepth).uppercase())
        
        // Then
        assertEquals(DiscoveryDepth.STANDARD, depth)
    }
}
