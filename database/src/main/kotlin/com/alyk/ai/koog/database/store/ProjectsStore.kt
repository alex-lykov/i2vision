package com.alyk.ai.koog.database.store

/**
 * Persistence for the list of projects (UI Projects card).
 * Used to store projects added by the user and the active project.
 */
interface ProjectsStore {
    fun getAll(): List<StoredProject>
    fun getById(id: String): StoredProject?
    fun add(project: StoredProject)
    fun remove(id: String): Boolean
    fun setActive(id: String): Boolean
    fun getActive(): StoredProject?
    /** Clear active selection (no project selected). */
    fun clearActive(): Boolean
}

data class StoredProject(
    val id: String,
    val name: String,
    val path: String,
    val addedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = false
)
