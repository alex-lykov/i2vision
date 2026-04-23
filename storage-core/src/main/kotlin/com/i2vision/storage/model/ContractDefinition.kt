/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.model

/**
 * Definition of a contract between VSLFC layers.
 */
data class ContractDefinition(
    val id: ContractId,
    val type: ContractType,
    val mappings: List<ContractMapping>,
    val validationRules: List<ValidationRule>
)

/**
 * Mapping between source and target elements in a contract.
 */
data class ContractMapping(
    val source: String,
    val target: String,
    val description: String? = null
)

/**
 * Validation rule for a contract.
 */
data class ValidationRule(
    val name: String,
    val description: String,
    val check: String // DSL expression or rule reference
)
