/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.impl

import com.i2vision.architecture.ArchitectureDetector
import com.i2vision.storage.I2VisionPaths
import com.i2vision.storage.model.StorageConstants
import com.i2vision.vslfc.VslfcStructure
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Rollout Manager for VSLFC Structure
 * 
 * This manager handles initialization and validation of the VSLFC directory structure.
 * It uses VslfcStructure to know WHAT to create and StorageConstants to know WHERE to create it.
 * 
 * Responsibilities:
 * - Initialize .vision-ai and .semantic-cache directories
 * - Create layer directories and agent configs
 * - Create contract templates
 * - Validate existing structure
 * - Detect when rollout is needed
 */
class RolloutManager {

    /**
     * Initialize the VSLFC structure in the given project root.
     * 
     * @param projectRoot The root directory of the project
     * @param force Whether to force re-initialization even if files exist
     * @return InitializeResult with details of what was created, skipped, or errored
     */
    suspend fun initialize(projectRoot: File, force: Boolean = false): InitializeResult {
        val created = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val errors = mutableListOf<String>()

        try {
            // 1. Detect technology stack first
            val architectureDetector = ArchitectureDetector(projectRoot.absolutePath)
            val technologyStack = architectureDetector.detectStack()

            // 2. Create .vision-ai root
            val visionAiDir = File(projectRoot, StorageConstants.VISION_AI_DIR)
            if (visionAiDir.mkdirs()) {
                created.add(visionAiDir.path)
            } else {
                skipped.add(visionAiDir.path)
            }

            // 3. Create layer directories and their contents
            VslfcStructure.LAYERS.forEach { layer ->
                val layerDir = File(visionAiDir, VslfcStructure.layerDirName(layer))

                // Create layer directory
                if (layerDir.mkdirs()) {
                    created.add(layerDir.path)
                } else if (!layerDir.exists()) {
                    errors.add("Failed to create layer directory: ${layerDir.path}")
                } else {
                    skipped.add(layerDir.path)
                }

                // 4. Create agent config
                val agentConfig = File(layerDir, VslfcStructure.agentConfigFileName(layer))
                if (!agentConfig.exists()) {
                    agentConfig.writeText(
                        VslfcStructure.AGENT_CONFIG_TEMPLATE.replace("{layer}", layer)
                    )
                    created.add(agentConfig.path)
                } else {
                    skipped.add(agentConfig.path)
                }

                // 5. Generate contracts based on detected technology stack
                val contractContent = generateContractForLayer(layer, technologyStack)
                val contractFile = File(layerDir, VslfcStructure.contractFileName())
                if (!contractFile.exists() || force) {
                    contractFile.writeText(contractContent)
                    if (!contractFile.exists()) {
                        created.add(contractFile.path)
                    } else {
                        // File was overwritten
                        created.add(contractFile.path)
                    }
                } else {
                    skipped.add(contractFile.path)
                }

                // 6. Create requirements directory and template for Vision layer
                if (layer == "vision") {
                    val requirementsDir = File(layerDir, "requirements")
                    if (requirementsDir.mkdirs()) {
                        created.add(requirementsDir.path)
                    } else if (!requirementsDir.exists()) {
                        errors.add("Failed to create requirements directory: ${requirementsDir.path}")
                    } else {
                        skipped.add(requirementsDir.path)
                    }

                    // Create example requirement template
                    val exampleReq = File(requirementsDir, "REQ-001-example.yaml")
                    if (!exampleReq.exists()) {
                        exampleReq.writeText(VslfcStructure.REQUIREMENT_TEMPLATE.replace("{number}", "001"))
                        created.add(exampleReq.path)
                    } else {
                        skipped.add(exampleReq.path)
                    }
                }
            }

            // Semantic cache is NOT created in project root - it's in user home via I2VisionPaths

            // 7. Create control-plane directories
            val configDir = File(visionAiDir, "config")
            if (configDir.mkdirs()) created.add(configDir.path) else skipped.add(configDir.path)

            val clustersDir = File(visionAiDir, "clusters")
            if (clustersDir.mkdirs()) created.add(clustersDir.path) else skipped.add(clustersDir.path)

            val overridesDir = File(visionAiDir, "overrides")
            if (overridesDir.mkdirs()) created.add(overridesDir.path) else skipped.add(overridesDir.path)

            val crossModuleDir = File(visionAiDir, "cross-module")
            if (crossModuleDir.mkdirs()) created.add(crossModuleDir.path) else skipped.add(crossModuleDir.path)

            val learningDir = File(visionAiDir, "learning")
            if (learningDir.mkdirs()) created.add(learningDir.path) else skipped.add(learningDir.path)

            val projectDir = File(visionAiDir, "project")
            if (projectDir.mkdirs()) created.add(projectDir.path) else skipped.add(projectDir.path)

            // 8. Write version file
            val versionFile = File(visionAiDir, ".version")
            if (!versionFile.exists()) {
                versionFile.writeText(VslfcStructure.getCurrentVersion())
                created.add(versionFile.path)
            } else {
                skipped.add(versionFile.path)
            }

        } catch (e: Exception) {
            errors.add("Initialization failed: ${e.message}")
        }

        return InitializeResult(
            success = errors.isEmpty(),
            created = created,
            skipped = skipped,
            errors = errors,
            version = VslfcStructure.getCurrentVersion()
        )
    }

    /**
     * Validate the VSLFC structure in the given project root.
     * 
     * @param projectRoot The root directory of the project
     * @return RolloutValidationResult with details of missing or invalid items
     */
    suspend fun validate(projectRoot: File): RolloutValidationResult {
        val missing = mutableListOf<String>()
        val invalid = mutableListOf<String>()

        // Check .vision-ai root
        val visionAiDir = File(projectRoot, StorageConstants.VISION_AI_DIR)
        if (!visionAiDir.exists()) {
            missing.add(visionAiDir.path)
            return RolloutValidationResult(
                valid = false,
                missing = missing,
                invalid = invalid,
                version = null
            )
        }

        // Check layer directories and their contents
        VslfcStructure.LAYERS.forEach { layer ->
            val layerDir = File(visionAiDir, VslfcStructure.layerDirName(layer))
            if (!layerDir.exists()) {
                missing.add(layerDir.path)
            }

            val agentConfig = File(layerDir, VslfcStructure.agentConfigFileName(layer))
            if (!agentConfig.exists()) {
                missing.add(agentConfig.path)
            }

            val contractFile = File(layerDir, VslfcStructure.contractFileName())
            if (!contractFile.exists()) {
                missing.add(contractFile.path)
            }
        }

        // Semantic cache is NOT in project root - it's in user home via I2VisionPaths

        return RolloutValidationResult(
            valid = missing.isEmpty() && invalid.isEmpty(),
            missing = missing,
            invalid = invalid,
            version = getVersion(projectRoot)
        )
    }

    /**
     * Check if rollout is needed for the given project root.
     * 
     * @param projectRoot The root directory of the project
     * @return true if rollout is needed, false otherwise
     */
    fun needsRollout(projectRoot: File): Boolean {
        val visionAiDir = File(projectRoot, StorageConstants.VISION_AI_DIR)
        if (!visionAiDir.exists()) {
            return true
        }

        // Check if structure is complete by validating it
        val validationResult = runBlocking {
            validate(projectRoot)
        }

        // Rollout is needed if validation fails (missing items)
        return !validationResult.valid
    }

    /**
     * Get the current version of the VSLFC structure in the project.
     * 
     * @param projectRoot The root directory of the project
     * @return The version string, or null if not found
     */
    private fun getVersion(projectRoot: File): String? {
        val versionFile = File(projectRoot, "${StorageConstants.VISION_AI_DIR}/.version")
        return if (versionFile.exists()) versionFile.readText().trim() else null
    }

    /**
     * Migrate legacy cache from project root to user home directory.
     * 
     * @param projectRoot The root directory of the project
     * @return true if migration occurred, false otherwise
     */
    fun migrateFromLegacyCache(projectRoot: File): Boolean {
        val legacyCache = File(projectRoot, StorageConstants.SEMANTIC_CACHE_DIR)
        if (!legacyCache.exists()) {
            return false
        }

        try {
            val newCache = I2VisionPaths.getProjectCacheDir(projectRoot.absolutePath)

            // Copy all files from legacy cache to new location
            legacyCache.copyRecursively(newCache, overwrite = true)

            // Delete legacy cache
            val deleted = legacyCache.deleteRecursively()

            return deleted
        } catch (e: Exception) {
            // Log error but don't fail initialization
            println("[ROLLOUT] Migration failed: ${e.message}")
            return false
        }
    }

    /**
     * Generate contract content for a given layer based on the detected technology stack.
     *
     * @param layer The layer for which to generate the contract
     * @param technologyStack The detected technology stack
     * @return The generated contract content
     */
    private fun generateContractForLayer(layer: String, technologyStack: ArchitectureDetector.TechnologyStack): String {
        val contract = mutableMapOf<String, Any>()

        // Base contract structure
        contract["version"] = "2.0"
        contract["layer"] = layer

        // Documentation section
        val documentation = mutableMapOf<String, Any>()
        documentation["primary"] = "docs/reference/api.md"
        documentation["secondary"] = listOf(
            "docs/guides/deployment.md",
            "README.md"
        )
        contract["documentation"] = documentation

        // Verbalization section based on detected technologies
        val verbalization = mutableMapOf<String, Any>()
        verbalization["primary"] = ".semantic-cache/{cluster}/$layer/symbols.yaml"
        verbalization["secondary"] = listOf(
            ".semantic-cache/{cluster}/$layer/complexity.yaml"
        )
        contract["verbalization"] = verbalization

        // Contracts section with mappings based on technology stack
        val contracts = mutableListOf<Map<String, Any>>()

        // Generate contracts based on detected frameworks and platforms
        when (layer) {
            "code" -> {
                val codeContract = mutableMapOf<String, Any>()
                codeContract["id"] = "code-documentation"
                codeContract["name"] = "Code Documentation Contract"
                codeContract["direction"] = "top-down"

                val mappings = mutableListOf<Map<String, Any>>()

                // Framework-specific mappings
                technologyStack.frameworks.forEach { framework ->
                    when (framework) {
                        ArchitectureDetector.Framework.SPRING_BOOT -> {
                            mappings.add(mapOf(
                                "doc_section" to "## API Reference",
                                "layer_field" to "implementations",
                                "parser" to "markdown_list",
                                "confidence" to 0.95
                            ))
                            mappings.add(mapOf(
                                "doc_section" to "## Spring Configuration",
                                "layer_field" to "spring_config",
                                "parser" to "yaml_properties",
                                "confidence" to 0.9
                            ))
                        }
                        ArchitectureDetector.Framework.KTOR -> {
                            mappings.add(mapOf(
                                "doc_section" to "## API Routes",
                                "layer_field" to "ktor_routes",
                                "parser" to "kotlin_routes",
                                "confidence" to 0.9
                            ))
                        }
                        ArchitectureDetector.Framework.REACT -> {
                            mappings.add(mapOf(
                                "doc_section" to "## Components",
                                "layer_field" to "react_components",
                                "parser" to "jsx_components",
                                "confidence" to 0.85
                            ))
                        }
                        else -> {
                            // Generic mapping
                            mappings.add(mapOf(
                                "doc_section" to "## API Reference",
                                "layer_field" to "implementations",
                                "parser" to "markdown_list",
                                "confidence" to 0.8
                            ))
                        }
                    }
                }

                codeContract["mappings"] = mappings
                contracts.add(codeContract)

                // Add verbalization contract
                val verbalizationContract = mutableMapOf<String, Any>()
                verbalizationContract["id"] = "code-verbalization"
                verbalizationContract["name"] = "Code Verbalization Contract"
                verbalizationContract["direction"] = "bottom-up"

                val verbalizationMappings = mutableListOf<Map<String, Any>>()

                // Language-specific patterns
                when (technologyStack.primaryLanguage) {
                    ArchitectureDetector.Language.KOTLIN -> {
                        verbalizationMappings.add(mapOf(
                            "code_pattern" to "class\\s+(\\w+)",
                            "verbalized_as" to "Component: \$1",
                            "confidence" to 0.8
                        ))
                        verbalizationMappings.add(mapOf(
                            "code_pattern" to "fun\\s+(\\w+)\\(",
                            "verbalized_as" to "Method: \$1",
                            "confidence" to 0.7
                        ))
                    }
                    ArchitectureDetector.Language.JAVA -> {
                        verbalizationMappings.add(mapOf(
                            "code_pattern" to "class\\s+(\\w+)",
                            "verbalized_as" to "Class: \$1",
                            "confidence" to 0.8
                        ))
                        verbalizationMappings.add(mapOf(
                            "code_pattern" to "public\\s+\\w+\\s+(\\w+)\\(",
                            "verbalized_as" to "Method: \$1",
                            "confidence" to 0.7
                        ))
                    }
                    ArchitectureDetector.Language.TYPESCRIPT -> {
                        verbalizationMappings.add(mapOf(
                            "code_pattern" to "class\\s+(\\w+)",
                            "verbalized_as" to "TypeScript Class: \$1",
                            "confidence" to 0.8
                        ))
                        verbalizationMappings.add(mapOf(
                            "code_pattern" to "function\\s+(\\w+)\\(",
                            "verbalized_as" to "Function: \$1",
                            "confidence" to 0.7
                        ))
                    }
                    else -> {
                        verbalizationMappings.add(mapOf(
                            "code_pattern" to "class\\s+(\\w+)",
                            "verbalized_as" to "Component: \$1",
                            "confidence" to 0.8
                        ))
                        verbalizationMappings.add(mapOf(
                            "code_pattern" to "fun\\s+(\\w+)\\(",
                            "verbalized_as" to "Method: \$1",
                            "confidence" to 0.7
                        ))
                    }
                }

                verbalizationContract["mappings"] = verbalizationMappings
                contracts.add(verbalizationContract)
            }

            "data" -> {
                val dataContract = mutableMapOf<String, Any>()
                dataContract["id"] = "data-schema"
                dataContract["name"] = "Data Schema Contract"
                dataContract["direction"] = "bottom-up"

                val mappings = mutableListOf<Map<String, Any>>()

                // Platform-specific mappings
                if (technologyStack.platforms.contains(ArchitectureDetector.Platform.BACKEND)) {
                    mappings.add(mapOf(
                        "schema_pattern" to "entity|model|dto",
                        "layer_field" to "data_models",
                        "parser" to "class_annotations",
                        "confidence" to 0.9
                    ))
                }

                dataContract["mappings"] = mappings
                contracts.add(dataContract)
            }

            "api" -> {
                val apiContract = mutableMapOf<String, Any>()
                apiContract["id"] = "api-specification"
                apiContract["name"] = "API Specification Contract"
                apiContract["direction"] = "top-down"

                val mappings = mutableListOf<Map<String, Any>>()

                // Framework-specific API mappings
                technologyStack.frameworks.forEach { framework ->
                    when (framework) {
                        ArchitectureDetector.Framework.SPRING_BOOT -> {
                            mappings.add(mapOf(
                                "spec_section" to "## REST Endpoints",
                                "layer_field" to "rest_endpoints",
                                "parser" to "spring_annotations",
                                "confidence" to 0.95
                            ))
                        }
                        ArchitectureDetector.Framework.KTOR -> {
                            mappings.add(mapOf(
                                "spec_section" to "## HTTP Routes",
                                "layer_field" to "http_routes",
                                "parser" to "ktor_routing",
                                "confidence" to 0.9
                            ))
                        }
                        else -> {
                            mappings.add(mapOf(
                                "spec_section" to "## API Endpoints",
                                "layer_field" to "api_endpoints",
                                "parser" to "generic_api",
                                "confidence" to 0.8
                            ))
                        }
                    }
                }

                apiContract["mappings"] = mappings
                contracts.add(apiContract)
            }

            "vision" -> {
                val visionContract = mutableMapOf<String, Any>()
                visionContract["id"] = "vision-requirements"
                visionContract["name"] = "Vision Requirements Contract"
                visionContract["direction"] = "top-down"

                val mappings = mutableListOf<Map<String, Any>>()

                mappings.add(mapOf(
                    "req_section" to "## Functional Requirements",
                    "layer_field" to "requirements",
                    "parser" to "requirement_list",
                    "confidence" to 0.9
                ))

                visionContract["mappings"] = mappings
                contracts.add(visionContract)
            }
        }

        contract["contracts"] = contracts

        // Validation rules
        val validation = mapOf(
            "rules" to listOf(
                mapOf(
                    "rule" to "every_doc_implementation_has_code_evidence",
                    "severity" to "warning"
                ),
                mapOf(
                    "rule" to "every_verbalized_description_matches_code",
                    "severity" to "warning"
                )
            )
        )
        contract["validation"] = validation

        // Convert to YAML-like string (simplified for now)
        return buildYamlString(contract)
    }

    private fun buildYamlString(data: Map<String, Any>, indent: String = ""): String {
        val sb = StringBuilder()

        data.forEach { (key, value) ->
            sb.append("$indent$key: ")
            when (value) {
                is String -> sb.append("\"$value\"\n")
                is Number -> sb.append("$value\n")
                is Boolean -> sb.append("$value\n")
                is List<*> -> {
                    sb.append("\n")
                    value.forEach { item ->
                        when (item) {
                            is Map<*, *> -> {
                                sb.append("$indent  - ")
                                @Suppress("UNCHECKED_CAST")
                                sb.append(buildYamlString(item as Map<String, Any>, "$indent    ").trim())
                                sb.append("\n")
                            }
                            is String -> sb.append("$indent  - \"$item\"\n")
                            else -> sb.append("$indent  - $item\n")
                        }
                    }
                }
                is Map<*, *> -> {
                    sb.append("\n")
                    @Suppress("UNCHECKED_CAST")
                    sb.append(buildYamlString(value as Map<String, Any>, "$indent  "))
                }
                else -> sb.append("$value\n")
            }
        }

        return sb.toString()
    }
}

/**
 * Result of initialization operation
 */
data class InitializeResult(
    val success: Boolean,
    val created: List<String>,
    val skipped: List<String>,
    val errors: List<String>,
    val version: String
)

/**
 * Result of validation operation
 */
data class RolloutValidationResult(
    val valid: Boolean,
    val missing: List<String>,
    val invalid: List<String>,
    val version: String?
)
