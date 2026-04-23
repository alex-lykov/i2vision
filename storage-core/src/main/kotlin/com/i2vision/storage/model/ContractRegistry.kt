/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.model

/**
 * Unified contract registry containing all contract definitions and validation results.
 */
data class ContractRegistry(
    val definitions: List<ContractDefinition>,
    val validations: Map<ContractId, ValidationResult>,
    val summary: RegistrySummary
)

/**
 * Summary statistics for the contract registry.
 */
data class RegistrySummary(
    val totalContracts: Int,
    val passedValidations: Int,
    val failedValidations: Int,
    val totalViolations: Int,
    val healthScore: Double // 0.0 to 1.0
)
