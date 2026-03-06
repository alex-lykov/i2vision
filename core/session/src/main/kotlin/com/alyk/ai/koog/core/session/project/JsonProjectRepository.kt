package com.alyk.ai.koog.core.session.project

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JSON file-based implementation of ProjectRepository.
 * Data layer implementation that persists to ~/.koog/projects.json
 */
class JsonProjectRepository(
    private val configFile: File = File(System.getProperty("user.home"), ".koog/projects.json"),
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }
) : ProjectRepository {

    private val projects = mutableListOf<Project>()
    private var activeProjectId: String? = null

    init {
        loadProjects()
    }

    override fun getAllProjects(): List<Project> = projects.toList()

    override fun getProjectById(id: String): Project? = projects.find { it.id == id }

    override fun addProject(project: Project) {
        projects.add(project)
        if (activeProjectId == null) {
            activeProjectId = project.id
        }
        saveProjects()
    }

    override fun removeProject(id: String): Boolean {
        val removed = projects.removeIf { it.id == id }
        if (removed && activeProjectId == id) {
            activeProjectId = projects.firstOrNull()?.id
        }
        if (removed) {
            saveProjects()
        }
        return removed
    }

    override fun setActiveProject(id: String): Boolean {
        if (projects.any { it.id == id }) {
            activeProjectId = id
            return true
        }
        return false
    }

    override fun getActiveProject(): Project? = projects.find { it.id == activeProjectId }

    private fun saveProjects() {
        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(json.encodeToString(projects))
        } catch (e: Exception) {
            println("Failed to save projects: ${e.message}")
        }
    }

    private fun loadProjects() {
        if (configFile.exists()) {
            try {
                val loaded = json.decodeFromString<List<Project>>(configFile.readText())
                projects.addAll(loaded)
            } catch (e: Exception) {
                println("Failed to load projects: ${e.message}")
            }
        }
    }
}
