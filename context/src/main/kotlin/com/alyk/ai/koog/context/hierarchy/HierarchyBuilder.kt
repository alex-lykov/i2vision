package com.alyk.ai.koog.context.hierarchy

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

/**
 * Parse codebase into three hierarchical levels with extracted
 * relationships between components.
 */
class HierarchyBuilder {
    private var projectRoot: Path? = null
    private val sourceExtensions = setOf("kt", "java", "kts", "gradle")

    /**
     * Initialize the hierarchy builder with a project path
     */
    fun initialize(projectPath: String) {
        this.projectRoot = Paths.get(projectPath)
    }

    /**
     * Clear the current project (e.g. when user unselects).
     */
    fun clear() {
        this.projectRoot = null
    }

    /**
     * Scan and return all source files in the project
     */
    suspend fun scanProjectFiles(): List<String> {
        val root = projectRoot ?: return emptyList()
        if (!root.exists()) return emptyList()

        return root.walk()
            .filter { it.isRegularFile() }
            .filter { it.extension in sourceExtensions }
            .map { it.toString() }
            .toList()
    }

    /**
     * Get project root path
     */
    fun getProjectRoot(): String? = projectRoot?.toString()

    /**
     * Check if a file is within the project
     */
    fun isInProject(filePath: String): Boolean {
        val root = projectRoot ?: return false
        return Paths.get(filePath).startsWith(root)
    }
    /**
     * Level 1 (Architecture): Module boundaries, data flows, API contracts, external dependencies
     */
    suspend fun buildArchitectureLevel(files: List<String>): ArchitectureLevel {
        // TODO: Implement architecture parsing
        return ArchitectureLevel(
            modules = emptyList(),
            dataFlows = emptyList(),
            apiContracts = emptyList()
        )
    }

    /**
     * Level 2 (Module): File structure, class hierarchies, public interfaces, key algorithms
     */
    suspend fun buildModuleLevel(file: String): ModuleLevel {
        // TODO: Implement module parsing
        return ModuleLevel(
            fileStructure = emptyList(),
            classHierarchies = emptyList(),
            publicInterfaces = emptyList()
        )
    }

    /**
     * Level 3 (Implementation): Actual source code, function bodies, local variables
     */
    suspend fun buildImplementationLevel(file: String): ImplementationLevel {
        // TODO: Implement implementation parsing
        return ImplementationLevel(
            sourceCode = file,
            functions = emptyList()
        )
    }
}

data class ArchitectureLevel(
    val modules: List<String>,
    val dataFlows: List<String>,
    val apiContracts: List<String>
)

data class ModuleLevel(
    val fileStructure: List<String>,
    val classHierarchies: List<String>,
    val publicInterfaces: List<String>
)

data class ImplementationLevel(
    val sourceCode: String,
    val functions: List<String>
)
