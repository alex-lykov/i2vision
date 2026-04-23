package com.i2vision.storage.impl

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
     * @return InitializeResult with details of what was created, skipped, or errored
     */
    suspend fun initialize(projectRoot: File): InitializeResult {
        val created = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val errors = mutableListOf<String>()
        
        try {
            // 1. Create .vision-ai root
            val visionAiDir = File(projectRoot, StorageConstants.VISION_AI_DIR)
            if (visionAiDir.mkdirs()) {
                created.add(visionAiDir.path)
            } else {
                skipped.add(visionAiDir.path)
            }
            
            // 2. Create layer directories and their contents
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
                
                // 3. Create contracts directory
                val contractsDir = File(layerDir, VslfcStructure.contractsDirName())
                if (contractsDir.mkdirs()) {
                    created.add(contractsDir.path)
                } else if (!contractsDir.exists()) {
                    errors.add("Failed to create contracts directory: ${contractsDir.path}")
                } else {
                    skipped.add(contractsDir.path)
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
                
                // 5. Create contract templates
                VslfcStructure.CONTRACT_TEMPLATES[layer]?.forEach { (name, template) ->
                    val contractFile = File(contractsDir, name)
                    if (!contractFile.exists()) {
                        contractFile.writeText(template)
                        created.add(contractFile.path)
                    } else {
                        skipped.add(contractFile.path)
                    }
                }
            }
            
            // Semantic cache is NOT created in project root - it's in user home via I2VisionPaths
            
            // 6. Create control-plane directories
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
            
            val contractsDir = File(layerDir, VslfcStructure.contractsDirName())
            if (!contractsDir.exists()) {
                missing.add(contractsDir.path)
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
