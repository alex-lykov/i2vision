package com.alyk.ai.koog.core.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Provide coroutine infrastructure for concurrent and asynchronous operations.
 */
object DispatcherProvider {
    /**
     * Custom dispatchers for IO, CPU, and database operations
     */
    val io: CoroutineDispatcher = Dispatchers.IO
    val cpu: CoroutineDispatcher = Dispatchers.Default
    val database: CoroutineDispatcher = Dispatchers.IO // TODO: Use dedicated DB dispatcher
}
