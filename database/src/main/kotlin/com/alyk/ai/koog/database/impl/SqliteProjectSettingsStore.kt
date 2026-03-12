package com.alyk.ai.koog.database.impl

import com.alyk.ai.koog.database.schema.ProjectSettingsTable
import com.alyk.ai.koog.database.store.ProjectSettingsStore
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class SqliteProjectSettingsStore(private val db: Database) : ProjectSettingsStore {

    override fun getSettings(projectId: String): String? = transaction(db) {
        ProjectSettingsTable.select { ProjectSettingsTable.projectId eq projectId }
            .singleOrNull()
            ?.get(ProjectSettingsTable.settingsJson)
    }

    override fun setSettings(projectId: String, settingsJson: String) {
        transaction(db) {
            val now = System.currentTimeMillis()
            val existing = ProjectSettingsTable.select { ProjectSettingsTable.projectId eq projectId }.singleOrNull()
            if (existing != null) {
                ProjectSettingsTable.update({ ProjectSettingsTable.projectId eq projectId }) {
                    it[ProjectSettingsTable.settingsJson] = settingsJson
                    it[ProjectSettingsTable.updatedAt] = now
                }
            } else {
                ProjectSettingsTable.insert {
                    it[ProjectSettingsTable.projectId] = projectId
                    it[ProjectSettingsTable.settingsJson] = settingsJson
                    it[ProjectSettingsTable.updatedAt] = now
                }
            }
        }
    }

    override fun deleteSettings(projectId: String) {
        transaction(db) {
            ProjectSettingsTable.deleteWhere { ProjectSettingsTable.projectId eq projectId }
            Unit
        }
    }

    override fun listProjectIds(): List<String> = transaction(db) {
        ProjectSettingsTable.select(ProjectSettingsTable.projectId).map { it[ProjectSettingsTable.projectId] }
    }
}
