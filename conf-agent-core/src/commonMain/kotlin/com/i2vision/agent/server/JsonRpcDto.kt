/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * JSON-RPC 2.0 Request
 */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
    val id: Long? = null
)

/**
 * JSON-RPC 2.0 Success Response
 */
@Serializable
data class JsonRpcSuccessResponse(
    val jsonrpc: String = "2.0",
    val result: JsonElement,
    val id: Long?
)

/**
 * JSON-RPC 2.0 Error
 */
@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
) {
    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}

/**
 * JSON-RPC 2.0 Error Response
 */
@Serializable
data class JsonRpcErrorResponse(
    val jsonrpc: String = "2.0",
    val error: JsonRpcError,
    val id: Long?
)

/**
 * JSON-RPC 2.0 Notification
 */
@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null
)

/**
 * Sealed class for all JSON-RPC responses
 */
@Serializable
sealed class JsonRpcResponse {
    abstract val jsonrpc: String
    abstract val id: Long?
}
