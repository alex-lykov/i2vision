/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.api

import com.i2vision.storage.model.ContractDefinition
import com.i2vision.storage.model.ContractId
import com.i2vision.storage.model.ContractRegistry
import com.i2vision.storage.model.ValidationResult
import com.i2vision.vslfc.PutResult

/**
 * Public API for contract storage and validation.
 */
interface ContractStore {

    /**
     * Store a contract definition (design-time).
     */
    suspend fun putDefinition(contract: ContractDefinition): PutResult

    /**
     * Get a contract definition.
     */
    suspend fun getDefinition(id: ContractId): ContractDefinition?

    /**
     * List all contract definitions.
     */
    suspend fun listDefinitions(): List<ContractDefinition>

    /**
     * Store contract validation results (runtime).
     */
    suspend fun putValidation(contractId: ContractId, result: ValidationResult): PutResult

    /**
     * Get validation results for a contract.
     */
    suspend fun getValidation(contractId: ContractId): ValidationResult?

    /**
     * Get the unified contract registry.
     */
    suspend fun getRegistry(): ContractRegistry
}
