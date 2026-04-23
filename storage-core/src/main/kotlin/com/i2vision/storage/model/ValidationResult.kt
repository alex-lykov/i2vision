/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.model

import java.time.Instant

/**
 * Result of contract validation.
 */
data class ValidationResult(
    val contractId: ContractId,
    val passed: Boolean,
    val violations: List<ContractViolation>,
    val validatedAt: Instant = Instant.now()
)

/**
 * A single contract violation.
 */
data class ContractViolation(
    val element: String,
    val rule: String,
    val message: String,
    val severity: ViolationSeverity
)

/**
 * Severity of a contract violation.
 */
enum class ViolationSeverity {
    ERROR,
    WARNING,
    INFO
}
