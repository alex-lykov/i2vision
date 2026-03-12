package com.alyk.ai.koog.database.impl

import com.alyk.ai.koog.database.schema.ProjectsTable
import com.alyk.ai.koog.database.store.ProjectsStore
import com.alyk.ai.koog.database.store.StoredProject
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class SqliteProjectsStore(private val db: org.jetbrains.exposed.sql.Database) : ProjectsStore {

    private val log = LoggerFactory.getLogger(SqliteProjectsStore::class.java)

    override fun getAll(): List<StoredProject> = transaction(db) {
        ProjectsTable.selectAll().orderBy(ProjectsTable.addedAt, SortOrder.DESC).map { it.toStoredProject() }
    }

    override fun getById(id: String): StoredProject? = transaction(db) {
        ProjectsTable.select { ProjectsTable.id eq id }.singleOrNull()?.toStoredProject()
    }

    override fun add(project: StoredProject) = transaction(db) {
        if (ProjectsTable.select { ProjectsTable.id eq project.id }.count() > 0) {
            log.warn("Project already exists id={}, updating", project.id)
            ProjectsTable.update({ ProjectsTable.id eq project.id }) {
                it[ProjectsTable.name] = project.name
                it[ProjectsTable.path] = project.path
                it[ProjectsTable.addedAt] = project.addedAt
                it[ProjectsTable.isActive] = project.isActive
            }
        } else {
            val isActive = project.isActive || ProjectsTable.selectAll().count() == 0L
            ProjectsTable.insert {
                it[ProjectsTable.id] = project.id
                it[ProjectsTable.name] = project.name
                it[ProjectsTable.path] = project.path
                it[ProjectsTable.addedAt] = project.addedAt
                it[ProjectsTable.isActive] = isActive
            }
            if (isActive) clearOtherActive(project.id)
        }
        log.info("Added project id={} name={} path={}", project.id, project.name, project.path)
    }

    override fun remove(id: String): Boolean = transaction(db) {
        val wasActive = ProjectsTable.select { ProjectsTable.id eq id }.singleOrNull()?.get(ProjectsTable.isActive) == true
        val deleted = ProjectsTable.deleteWhere { ProjectsTable.id eq id } > 0
        if (deleted && wasActive) {
            ProjectsTable.selectAll().firstOrNull()?.get(ProjectsTable.id)?.let { firstId ->
                ProjectsTable.update({ ProjectsTable.id eq firstId }) { it[ProjectsTable.isActive] = true }
            }
        }
        if (deleted) log.info("Removed project id={}", id)
        deleted
    }

    override fun setActive(id: String): Boolean = transaction(db) {
        if (ProjectsTable.select { ProjectsTable.id eq id }.count() == 0L) return@transaction false
        clearOtherActive(id)
        ProjectsTable.update({ ProjectsTable.id eq id }) { it[ProjectsTable.isActive] = true }
        log.info("Set active project id={}", id)
        true
    }

    override fun getActive(): StoredProject? = transaction(db) {
        ProjectsTable.select { ProjectsTable.isActive eq true }.singleOrNull()?.toStoredProject()
    }

    override fun clearActive(): Boolean = transaction(db) {
        val hadActive = ProjectsTable.select { ProjectsTable.isActive eq true }.count() > 0L
        ProjectsTable.update({ ProjectsTable.isActive eq true }) { it[ProjectsTable.isActive] = false }
        if (hadActive) log.info("Cleared active project")
        hadActive
    }

    private fun clearOtherActive(exceptId: String) {
        ProjectsTable.update({ ProjectsTable.id neq exceptId }) { it[ProjectsTable.isActive] = false }
    }

    private fun ResultRow.toStoredProject() = StoredProject(
        id = this[ProjectsTable.id],
        name = this[ProjectsTable.name],
        path = this[ProjectsTable.path],
        addedAt = this[ProjectsTable.addedAt],
        isActive = this[ProjectsTable.isActive]
    )
}
