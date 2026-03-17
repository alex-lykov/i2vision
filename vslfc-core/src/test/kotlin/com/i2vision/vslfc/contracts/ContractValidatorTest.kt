package com.i2vision.vslfc.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractValidatorTest {

    private val validator = ContractValidator()

    @Test
    fun `should validate contract with valid metadata`() {
        val contract = DiscoveryContract(
            metadata = ContractMetadata(
                name = "valid-contract",
                version = "1.0",
                description = "A valid contract",
                targetLayers = listOf("all")
            )
        )
        
        val result = validator.validate(contract)
        
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `should reject contract with blank name`() {
        val contract = DiscoveryContract(
            metadata = ContractMetadata(
                name = "",
                version = "1.0",
                targetLayers = listOf("all")
            )
        )
        
        val result = validator.validate(contract)
        
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "contract.name" })
    }

    @Test
    fun `should warn on invalid version format`() {
        val contract = DiscoveryContract(
            metadata = ContractMetadata(
                name = "test",
                version = "invalid",
                targetLayers = listOf("all")
            )
        )
        
        val result = validator.validate(contract)
        
        // Should still be valid but with warning
        assertTrue(result.isValid)
        assertTrue(result.warnings.any { it.field == "contract.version" })
    }

    @Test
    fun `should warn on missing description`() {
        val contract = DiscoveryContract(
            metadata = ContractMetadata(
                name = "test",
                version = "1.0",
                description = null,
                targetLayers = listOf("all")
            )
        )
        
        val result = validator.validate(contract)
        
        assertTrue(result.isValid)
        assertTrue(result.warnings.any { it.field == "contract.description" })
    }

    @Test
    fun `should reject empty target layers`() {
        val contract = DiscoveryContract(
            metadata = ContractMetadata(
                name = "test",
                version = "1.0",
                targetLayers = emptyList()
            )
        )
        
        val result = validator.validate(contract)
        
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "contract.target_layers" })
    }

    @Test
    fun `should validate quality gates coverage percentage`() {
        val contract = DiscoveryContract(
            metadata = ContractMetadata(
                name = "test",
                version = "1.0",
                targetLayers = listOf("all")
            ),
            qualityGates = QualityGates(
                minCoveragePercentage = 150.0 // Invalid
            )
        )
        
        val result = validator.validate(contract)
        
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.field == "quality_gates.min_coverage_percentage" })
    }

    @Test
    fun `should validate batch of contracts`() {
        val contracts = listOf(
            DiscoveryContract(
                metadata = ContractMetadata(name = "valid", targetLayers = listOf("all"))
            ),
            DiscoveryContract(
                metadata = ContractMetadata(name = "", targetLayers = listOf("all"))
            )
        )
        
        val result = validator.validateBatch(contracts)
        
        assertEquals(2, result.total)
        assertEquals(1, result.valid)
        assertEquals(1, result.invalid)
    }

    @Test
    fun `quick validation should return boolean`() {
        val validContract = DiscoveryContract(
            metadata = ContractMetadata(name = "valid", targetLayers = listOf("all"))
        )
        
        assertTrue(validator.validateQuick(validContract))
    }
}
