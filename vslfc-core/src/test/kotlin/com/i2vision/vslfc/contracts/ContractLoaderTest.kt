package com.i2vision.vslfc.contracts

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractLoaderTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `should return null when contract file does not exist`() {
        val loader = ContractLoader(contractsDir = tempDir.absolutePath)
        val contract = loader.loadContract("nonexistent")

        assertNull(contract)
    }

    @Test
    fun `should load contract from file`() {
        val loader = ContractLoader(contractsDir = tempDir.absolutePath)

        // Create a test contract file
        val contractFile = File(tempDir, "test-contract.yaml")
        contractFile.writeText(
            """
            contract:
              name: test-contract
              version: "1.0"
              description: Test contract
              target_layers: ["all"]
              priority: MEDIUM
            
            discovery_config:
              depth: STANDARD
              include_patterns:
                - "**/*.kt"
        """.trimIndent()
        )

        val contract = loader.loadContract("test-contract")

        assertNotNull(contract)
        assertEquals("test-contract", contract.metadata.name)
        assertEquals("1.0", contract.metadata.version)
        assertEquals(DiscoveryDepth.STANDARD, contract.discoveryConfig?.depth)
    }

    @Test
    fun `should cache loaded contracts`() {
        val loader = ContractLoader(contractsDir = tempDir.absolutePath)

        val contractFile = File(tempDir, "cache-test.yaml")
        contractFile.writeText(
            """
            contract:
              name: cache-test
              version: "1.0"
              target_layers: ["all"]
        """.trimIndent()
        )

        // First load
        val first = loader.loadContract("cache-test")
        assertNotNull(first)

        // Second load should use cache
        val second = loader.loadContract("cache-test")
        assertNotNull(second)

        // Verify cache stats
        val stats = loader.getCacheStats()
        assertTrue(stats.size > 0)
    }

    @Test
    fun `should clear cache`() {
        val loader = ContractLoader(contractsDir = tempDir.absolutePath)

        val contractFile = File(tempDir, "clear-test.yaml")
        contractFile.writeText(
            """
            contract:
              name: clear-test
              version: "1.0"
              target_layers: ["all"]
        """.trimIndent()
        )

        loader.loadContract("clear-test")
        assertTrue(loader.getCacheStats().size > 0)

        loader.clearCache()
        assertEquals(0, loader.getCacheStats().size)
    }

    @Test
    fun `should list contract names`() {
        val loader = ContractLoader(contractsDir = tempDir.absolutePath)

        File(tempDir, "contract1.yaml").writeText(
            """
            contract:
              name: contract1
              version: "1.0"
              target_layers: ["all"]
        """.trimIndent()
        )

        File(tempDir, "contract2.yaml").writeText(
            """
            contract:
              name: contract2
              version: "1.0"
              target_layers: ["all"]
        """.trimIndent()
        )

        val names = loader.listContractNames()

        assertTrue(names.contains("contract1"))
        assertTrue(names.contains("contract2"))
    }

    @Test
    fun `should load contracts by layer`() {
        val loader = ContractLoader(contractsDir = tempDir.absolutePath)

        File(tempDir, "vision-contract.yaml").writeText(
            """
            contract:
              name: vision-contract
              version: "1.0"
              target_layers: ["vision"]
        """.trimIndent()
        )

        File(tempDir, "all-contract.yaml").writeText(
            """
            contract:
              name: all-contract
              version: "1.0"
              target_layers: ["all"]
        """.trimIndent()
        )

        val visionContracts = loader.loadContractsForLayer("vision")

        assertEquals(2, visionContracts.size) // vision-contract + all-contract
    }
}
