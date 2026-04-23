/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.model

/**
 * Types of contracts between VSLFC layers.
 */
enum class ContractType {
    IMPLEMENTS,
    DEFINES,
    EXERCISES,
    CALLS,
    DEPENDS_ON
}
