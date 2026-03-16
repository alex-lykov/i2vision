package com.alyk.ai.koog.database

import com.alyk.ai.koog.database.settings.TerminalSettingsRepository
import com.alyk.ai.koog.database.store.*

/**
 * Central database provider for Koog agent persistence.
 * Provides access to: project settings, agent state, RAG, memory, prompt caching, and terminal output settings.
 */
interface DatabaseProvider {
    /** Per-project settings (e.g. config overrides stored as JSON). */
    val projectSettings: ProjectSettingsStore

    /** Agent session and conversation state persistence. */
    val agentState: AgentStateStore

    /** RAG: document chunks and embeddings for retrieval. */
    val rag: RagStore

    /** Agent memory: facts and knowledge retention. */
    val memory: AgentMemoryStore

    /** Prompt caching: cache prompt/completion by key (e.g. hash). */
    val promptCache: PromptCacheStore

    /** Projects list (UI Projects card) — add/select projects linked to the agent. */
    val projects: ProjectsStore

    /** Loaded LLM model status (local/cloud) — persist and restore running models across app restarts. */
    val loadedModels: LoadedModelsStore

    /** Agent tabs persistence — save and restore agent tabs across app restarts. */
    val agentTabs: AgentTabsStore

    /** Terminal output settings (definitions + per-session state). Used to filter/format terminal feed. */
    fun terminalSettingsRepository(sessionId: String, userId: String? = null): TerminalSettingsRepository

    /** Release resources (e.g. connection pool). */
    fun close()
}
