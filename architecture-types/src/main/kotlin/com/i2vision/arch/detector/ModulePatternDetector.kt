/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.arch.detector

import com.i2vision.arch.signature.ModulePattern
import java.io.File

/**
 * Detects module patterns from project structure
 * Analyzes directory structure and file organization to determine architectural patterns
 */
class ModulePatternDetector(private val projectRoot: String) {

    /**
     * Detect module patterns across the project
     */
    fun detect(): Result {
        val root = File(projectRoot)
        val modulePatterns = mutableMapOf<String, ModulePattern>()
        val confidences = mutableMapOf<String, Double>()

        // Analyze top-level directories as potential modules
        val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.toList() ?: emptyList()

        for (dir in dirs) {
            val pattern = detectPatternInDirectory(dir)
            if (pattern != ModulePattern.UNKNOWN) {
                modulePatterns[dir.name] = pattern
                confidences[dir.name] = 0.75
            }
        }

        // Detect nested modules (e.g., core/*)
        for (dir in dirs) {
            val subdirs =
                dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.toList() ?: emptyList()
            for (subdir in subdirs) {
                val pattern = detectPatternInDirectory(subdir)
                if (pattern != ModulePattern.UNKNOWN) {
                    val modulePath = "${dir.name}/${subdir.name}"
                    modulePatterns[modulePath] = pattern
                    confidences[modulePath] = 0.70
                }
            }
        }

        return Result(modulePatterns, confidences)
    }

    /**
     * Detect architectural pattern within a specific directory
     */
    private fun detectPatternInDirectory(dir: File): ModulePattern {
        val subdirs =
            dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name } ?: emptyList()

        // Hexagonal pattern: domain, application, infrastructure layers
        if (hasHexagonalLayers(subdirs)) {
            return ModulePattern.HEXAGONAL
        }

        // Layered pattern: controller, service, repository layers
        if (hasLayeredStructure(subdirs)) {
            return ModulePattern.LAYERED
        }

        // Agent pattern: agent, orchestrator, dispatcher
        if (hasAgentStructure(subdirs)) {
            return ModulePattern.AGENT_FRAMEWORK
        }

        // Microservices: each module has its own src/main structure
        if (hasMicroservicesStructure(dir)) {
            return ModulePattern.MICROSERVICES
        }

        // MVC pattern: model, view, controller
        if (hasMVCStructure(subdirs)) {
            return ModulePattern.MVC
        }

        // Clean architecture: entities, usecases, interfaces, infrastructure
        if (hasCleanArchitectureLayers(subdirs)) {
            return ModulePattern.CLEAN_ARCHITECTURE
        }

        return ModulePattern.UNKNOWN
    }

    private fun hasHexagonalLayers(subdirs: List<String>): Boolean {
        val hexagonalKeywords = setOf("domain", "application", "infrastructure", "adapter", "port")
        val matchCount = subdirs.count { hexagonalKeywords.any { keyword -> it.contains(keyword, ignoreCase = true) } }
        return matchCount >= 2
    }

    private fun hasLayeredStructure(subdirs: List<String>): Boolean {
        val layeredKeywords = setOf("controller", "service", "repository", "dao", "model", "entity")
        val matchCount = subdirs.count { layeredKeywords.any { keyword -> it.contains(keyword, ignoreCase = true) } }
        return matchCount >= 2
    }

    private fun hasAgentStructure(subdirs: List<String>): Boolean {
        val agentKeywords = setOf("agent", "orchestrator", "dispatcher", "router", "coordinator")
        val matchCount = subdirs.count { agentKeywords.any { keyword -> it.contains(keyword, ignoreCase = true) } }
        return matchCount >= 2
    }

    private fun hasMicroservicesStructure(dir: File): Boolean {
        val srcDir = File(dir, "src/main")
        if (srcDir.exists()) {
            val hasKotlin = File(srcDir, "kotlin").exists()
            val hasJava = File(srcDir, "java").exists()
            val hasResources = File(srcDir, "resources").exists()
            return hasKotlin || hasJava
        }
        return false
    }

    private fun hasMVCStructure(subdirs: List<String>): Boolean {
        val mvcKeywords = setOf("model", "view", "controller", "presenter")
        val matchCount = subdirs.count { mvcKeywords.any { keyword -> it.contains(keyword, ignoreCase = true) } }
        return matchCount >= 2
    }

    private fun hasCleanArchitectureLayers(subdirs: List<String>): Boolean {
        val cleanKeywords = setOf("entity", "usecase", "interface", "infrastructure", "boundary")
        val matchCount = subdirs.count { cleanKeywords.any { keyword -> it.contains(keyword, ignoreCase = true) } }
        return matchCount >= 2
    }

    data class Result(
        val modulePatterns: Map<String, ModulePattern>,
        val confidences: Map<String, Double>
    )
}
