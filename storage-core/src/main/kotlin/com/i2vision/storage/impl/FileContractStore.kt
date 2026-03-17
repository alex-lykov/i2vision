package com.i2vision.storage.impl

import com.i2vision.storage.api.*
import com.i2vision.storage.impl.internal.StorageLayout
import com.i2vision.storage.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-based implementation of ContractStore.
 * INTERNAL - knows the physical storage layout.
 */
class FileContractStore(
    private val projectRoot: File
) : ContractStore {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    override suspend fun putDefinition(contract: ContractDefinition): PutResult {
        val relativePath = StorageLayout.contractDefinitionPath(
            layer = contract.id.sourceLayer.name.lowercase(),
            filename = "${contract.id.name}.yaml"
        )
        val file = File(projectRoot, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(contract))
        
        return PutResult.Success(contract.id)
    }
    
    override suspend fun getDefinition(id: ContractId): ContractDefinition? {
        val relativePath = StorageLayout.contractDefinitionPath(
            layer = id.sourceLayer.name.lowercase(),
            filename = "${id.name}.yaml"
        )
        val file = File(projectRoot, relativePath)
        if (!file.exists()) return null
        
        return json.decodeFromString<ContractDefinition>(file.readText())
    }
    
    override suspend fun listDefinitions(): List<ContractDefinition> {
        val definitions = mutableListOf<ContractDefinition>()
        
        Layer.entries.forEach { layer ->
            val dir = File(projectRoot, StorageLayout.contractDefinitionsDir(layer.name.lowercase()))
            if (dir.exists()) {
                dir.listFiles()
                    ?.filter { it.extension == "yaml" }
                    ?.forEach { file ->
                        try {
                            val contract = json.decodeFromString<ContractDefinition>(file.readText())
                            definitions.add(contract)
                        } catch (e: Exception) {
                            // Skip corrupted files
                        }
                    }
            }
        }
        
        return definitions
    }
    
    override suspend fun putValidation(contractId: ContractId, result: ValidationResult): PutResult {
        val relativePath = "${StorageLayout.contractArtifactsDir(contractId.sourceLayer.name.lowercase())}/${contractId.name}-validation.yaml"
        val file = File(projectRoot, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(result))
        
        return PutResult.Success(contractId)
    }
    
    override suspend fun getValidation(contractId: ContractId): ValidationResult? {
        val relativePath = "${StorageLayout.contractArtifactsDir(contractId.sourceLayer.name.lowercase())}/${contractId.name}-validation.yaml"
        val file = File(projectRoot, relativePath)
        if (!file.exists()) return null
        
        return json.decodeFromString<ValidationResult>(file.readText())
    }
    
    override suspend fun getRegistry(): ContractRegistry {
        val definitions = listDefinitions()
        val validations = mutableMapOf<ContractId, ValidationResult>()
        
        definitions.forEach { contract ->
            val validation = getValidation(contract.id)
            if (validation != null) {
                validations[contract.id] = validation
            }
        }
        
        val passedValidations = validations.values.count { it.passed }
        val failedValidations = validations.values.count { !it.passed }
        val totalViolations = validations.values.sumOf { it.violations.size }
        val healthScore = if (validations.isNotEmpty()) {
            passedValidations.toDouble() / validations.size
        } else {
            1.0
        }
        
        return ContractRegistry(
            definitions = definitions,
            validations = validations,
            summary = RegistrySummary(
                totalContracts = definitions.size,
                passedValidations = passedValidations,
                failedValidations = failedValidations,
                totalViolations = totalViolations,
                healthScore = healthScore
            )
        )
    }
}
