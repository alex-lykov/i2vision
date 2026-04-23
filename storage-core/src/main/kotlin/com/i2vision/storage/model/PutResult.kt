/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.model

/**
 * Result of a put operation.
 */
sealed class PutResult {
    data class Success(val ref: Any) : PutResult()
    data class Failure(val reason: String) : PutResult()
}
