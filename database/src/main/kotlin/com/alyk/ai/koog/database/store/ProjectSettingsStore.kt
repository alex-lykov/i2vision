package com.alyk.ai.koog.database.store

/**
 * Persistence for project-specific settings.
 * Settings are stored as JSON; the caller is responsible for serializing/deserializing
 * (e.g. [com.alyk.ai.koog.config.Config] or project overrides).
 */
interface ProjectSettingsStore {
    /**
     * Get settings JSON for a project, or null if not set.
     * @param projectId project identifier (e.g. from [com.alyk.ai.koog.core.session.project.Project.id])
     */
    fun getSettings(projectId: String): String?

    /**
     * Save settings JSON for a project.
     */
    fun setSettings(projectId: String, settingsJson: String)

    /**
     * Remove stored settings for a project.
     */
    fun deleteSettings(projectId: String)

    /**
     * List all project IDs that have stored settings.
     */
    fun listProjectIds(): List<String>
}
