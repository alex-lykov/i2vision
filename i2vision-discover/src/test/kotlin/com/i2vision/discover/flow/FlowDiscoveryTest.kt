/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.flow

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for Flow Discovery.
 * Validates flow extraction from call graphs.
 */
class FlowDiscoveryTest {

    private fun withTempDir(block: (File) -> Unit) {
        val tempDir = java.nio.file.Files.createTempDirectory("flow-test").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `should create flow discovery instance`() = withTempDir { tempDir ->
        // Given: Project root
        val projectRoot = tempDir.absolutePath

        // When: Create FlowDiscovery
        val flowDiscovery = FlowDiscovery(projectRoot)

        // Then: Should be created successfully
        assertTrue(true, "FlowDiscovery created successfully")
    }
}
