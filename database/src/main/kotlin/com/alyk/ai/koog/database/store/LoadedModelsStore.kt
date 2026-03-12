package com.alyk.ai.koog.database.store

/**
 * Persistence for loaded LLM model status (local/cloud).
 * Used to restore running models on app boot and to persist status on each start/stop.
 */
interface LoadedModelsStore {
    /** Pairs of (modelId, provider). */
    fun getRunning(): List<Pair<String, String>>
    fun setRunning(modelId: String, provider: String)
    fun setStopped(modelId: String)
}
