/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.impl

import com.i2vision.storage.api.ContractStore
import com.i2vision.storage.impl.internal.StorageLayout
import com.i2vision.storage.model.*
import com.i2vision.vslfc.PutResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

/**
 * File-based implementation of ContractStore.
 * INTERNAL - knows the physical storage layout.
 * 
 * Uses NIO file operations for thread-safe concurrent access on Windows.
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
        // Use NIO for thread-safe file writing on Windows
        Files.writeString(file.toPath(), json.encodeToString(contract))

        return PutResult.Success(contract.id)
    }

    override suspend fun getDefinition(id: ContractId): ContractDefinition? {
        val relativePath = StorageLayout.contractDefinitionPath(
            layer = id.sourceLayer.name.lowercase(),
            filename = "${id.name}.yaml"
        )
        val file = File(projectRoot, relativePath)
        if (!file.exists()) return null

        // Use NIO for thread-safe file reading on Windows
        return json.decodeFromString(Files.readString(file.toPath()))
    }

    override suspend fun listDefinitions(): List<ContractDefinition> {
        val definitions = mutableListOf<ContractDefinition>()

        Layer.entries.forEach { layer ->
            val dir = File(projectRoot, StorageLayout.contractDefinitionsDir(layer.name.lowercase()))
            if (dir.exists()) {
                // Use NIO for thread-safe directory traversal on Windows
                Files.list(dir.toPath())
                    .filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".yaml") }
                    .forEach { path ->
                        try {
                            val contract = json.decodeFromString<ContractDefinition>(Files.readString(path))
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
        val artifactsDir = StorageLayout.contractArtifactsDir(projectRoot, contractId.sourceLayer.name.lowercase())
        val file = File(artifactsDir, "${contractId.name}-validation.yaml")
        // Use NIO for thread-safe file writing on Windows
        Files.writeString(file.toPath(), json.encodeToString(result))

        return PutResult.Success(contractId)
    }

    override suspend fun getValidation(contractId: ContractId): ValidationResult? {
        val artifactsDir = StorageLayout.contractArtifactsDir(projectRoot, contractId.sourceLayer.name.lowercase())
        val file = File(artifactsDir, "${contractId.name}-validation.yaml")
        if (!file.exists()) return null

        // Use NIO for thread-safe file reading on Windows
        return json.decodeFromString(Files.readString(file.toPath()))
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