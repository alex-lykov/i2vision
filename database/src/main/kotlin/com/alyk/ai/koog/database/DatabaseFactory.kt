package com.alyk.ai.koog.database

import com.alyk.ai.koog.database.DatabaseFactory.init
import java.io.File

/**
 * Factory for creating and initializing the Koog database provider.
 * Provides: project settings, agent state, RAG, memory, and prompt caching.
 */
object DatabaseFactory {

    private var _provider: DatabaseProvider? = null

    /**
     * Initialize the database and return the provider.
     * Uses default path `~/.koog/koog.db` unless [dbPath] is specified.
     */
    @JvmOverloads
    fun init(dbPath: File = File(System.getProperty("user.home"), ".koog/koog.db")): DatabaseProvider {
        if (_provider == null) {
            _provider = SqliteDatabaseProvider(dbPath)
        }
        return _provider!!
    }

    /**
     * Get the current provider. Call [init] first.
     */
    fun getProvider(): DatabaseProvider? = _provider

    /**
     * Close the provider and clear the reference.
     */
    fun close() {
        _provider?.close()
        _provider = null
    }
}
