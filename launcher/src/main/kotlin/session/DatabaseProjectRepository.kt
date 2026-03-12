package session

import com.alyk.ai.koog.core.session.project.Project
import com.alyk.ai.koog.core.session.project.ProjectRepository
import com.alyk.ai.koog.database.store.ProjectsStore
import com.alyk.ai.koog.database.store.StoredProject
import org.slf4j.LoggerFactory

/**
 * Project repository backed by the database.
 * Projects added from the UI (Projects card) are stored here and linked to the agent.
 */
class DatabaseProjectRepository(
    private val projectsStore: ProjectsStore
) : ProjectRepository {

    private val log = LoggerFactory.getLogger(DatabaseProjectRepository::class.java)

    override fun getAllProjects(): List<Project> =
        projectsStore.getAll().map { it.toProject() }

    override fun getProjectById(id: String): Project? =
        projectsStore.getById(id)?.toProject()

    override fun addProject(project: Project) {
        projectsStore.add(
            StoredProject(
                id = project.id,
                name = project.name,
                path = project.path,
                addedAt = project.addedAt,
                isActive = projectsStore.getActive() == null
            )
        )
    }

    override fun removeProject(id: String): Boolean {
        val removed = projectsStore.remove(id)
        if (removed) log.info("Removed project from repository id={}", id)
        return removed
    }

    override fun setActiveProject(id: String): Boolean {
        val ok = projectsStore.setActive(id)
        if (ok) log.info("Set active project id={}", id)
        return ok
    }

    override fun getActiveProject(): Project? =
        projectsStore.getActive()?.toProject()

    override fun clearActiveProject(): Boolean {
        val ok = projectsStore.clearActive()
        if (ok) log.info("Cleared active project in repository")
        return ok
    }

    private fun StoredProject.toProject() = Project(
        id = id,
        name = name,
        path = path,
        addedAt = addedAt
    )
}
