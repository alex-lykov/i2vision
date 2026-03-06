package com.alyk.ai.koog.core.session.project

import kotlinx.serialization.Serializable

/**
 * Domain entity representing a managed project.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val path: String,
    val addedAt: Long = System.currentTimeMillis()
)
