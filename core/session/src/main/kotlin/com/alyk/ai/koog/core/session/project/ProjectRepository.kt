package com.alyk.ai.koog.core.session.project

/**
 * Repository interface for project persistence operations.
 * Following clean architecture - domain layer defines the contract.
 */
interface ProjectRepository {
    fun getAllProjects(): List<Project>
    fun getProjectById(id: String): Project?
    fun addProject(project: Project)
    fun removeProject(id: String): Boolean
    fun setActiveProject(id: String): Boolean
    fun getActiveProject(): Project?
    /** Clear active selection (no project selected). Returns true if there was an active project. */
    fun clearActiveProject(): Boolean
}
