package com.i2vision.vslfc.contracts

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileNotFoundException

/**
 * ContractLoader - Loads VSLFC discovery contracts from YAML files.
 * 
 * Supports:
 * - Loading single contract files
 * - Loading all contracts from directory
 * - Caching loaded contracts
 * - Hot-reloading (optional)
 */
class ContractLoader(
    private val contractsDir: String = DEFAULT_CONTRACTS_DIR
) {
    private val log = LoggerFactory.getLogger(ContractLoader::class.java)
    private val cache = mutableMapOf<String, DiscoveryContract>()
    private val yaml = Yaml()

    companion object {
        const val DEFAULT_CONTRACTS_DIR = ".vision-ai/contracts"
        const val CONTRACT_EXTENSION = ".yaml"
    }

    // ── Main API ───────────────────────────────────────────────────────────

    /**
     * Load a single contract by name.
     */
    fun loadContract(name: String): DiscoveryContract? {
        val cacheKey = "contract:$name"

        // Check cache first
        cache[cacheKey]?.let {
            log.debug("[CONTRACT] Cache hit for {}", name)
            return it
        }

        val file = findContractFile(name)
        return file?.let {
            loadFromFile(it)?.also { contract ->
                cache[cacheKey] = contract
                log.info("[CONTRACT] Loaded and cached: {}", name)
            }
        }
    }

    /**
     * Load all contracts from the contracts directory.
     */
    fun loadAllContracts(): List<DiscoveryContract> {
        val dir = File(contractsDir)

        if (!dir.exists() || !dir.isDirectory) {
            log.warn("[CONTRACT] Contracts directory not found: {}", contractsDir)
            return emptyList()
        }

        val contractFiles = dir.listFiles { file ->
            file.isFile && file.name.endsWith(CONTRACT_EXTENSION)
        } ?: emptyArray()

        log.info("[CONTRACT] Found {} contract files in {}", contractFiles.size, contractsDir)

        return contractFiles.mapNotNull { file ->
            loadFromFile(file)?.also { contract ->
                cache["contract:${contract.metadata.name}"] = contract
            }
        }
    }

    /**
     * Load contracts by layer target.
     */
    fun loadContractsForLayer(layer: String): List<DiscoveryContract> {
        return loadAllContracts().filter { contract ->
            contract.metadata.targetLayers.contains(layer) ||
                    contract.metadata.targetLayers.contains("all")
        }
    }

    /**
     * Load contracts by priority.
     */
    fun loadContractsByPriority(minPriority: ContractPriority): List<DiscoveryContract> {
        val priorityOrder = ContractPriority.values()
        val minIndex = priorityOrder.indexOf(minPriority)

        return loadAllContracts().filter { contract ->
            priorityOrder.indexOf(contract.metadata.priority) >= minIndex
        }
    }

    /**
     * Clear the contract cache.
     */
    fun clearCache() {
        val size = cache.size
        cache.clear()
        log.info("[CONTRACT] Cache cleared ({} entries)", size)
    }

    /**
     * Reload a contract from disk (bypass cache).
     */
    fun reloadContract(name: String): DiscoveryContract? {
        cache.remove("contract:$name")
        return loadContract(name)
    }

    // ── Loading Implementation ──────────────────────────────────────────────

    private fun findContractFile(name: String): File? {
        // Try exact name first
        val exactFile = File(contractsDir, "$name$CONTRACT_EXTENSION")
        if (exactFile.exists()) return exactFile

        // Try with 'to-' prefix (convention for layer contracts)
        val prefixedFile = File(contractsDir, "to-$name$CONTRACT_EXTENSION")
        if (prefixedFile.exists()) return prefixedFile

        // Search by metadata name inside files
        val dir = File(contractsDir)
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles { file ->
                file.isFile && file.name.endsWith(CONTRACT_EXTENSION)
            }?.forEach { file ->
                try {
                    val yamlMap = yaml.load<Map<String, Any>>(file.readText())
                    val contract = parseContract(yamlMap)
                    if (contract?.metadata?.name == name) {
                        return file
                    }
                } catch (e: Exception) {
                    // Skip invalid files
                }
            }
        }

        log.warn("[CONTRACT] Contract not found: {}", name)
        return null
    }

    private fun loadFromFile(file: File): DiscoveryContract? {
        return try {
            val yamlMap = yaml.load<Map<String, Any>>(file.readText())
            parseContract(yamlMap)
        } catch (e: FileNotFoundException) {
            log.error("[CONTRACT] File not found: {}", file.path)
            null
        } catch (e: Exception) {
            log.error("[CONTRACT] Failed to parse {}: {}", file.name, e.message)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseContract(yamlMap: Map<String, Any>?): DiscoveryContract? {
        yamlMap ?: return null

        val contractMap = yamlMap["contract"] as? Map<String, Any> ?: return null

        return DiscoveryContract(
            metadata = parseMetadata(contractMap),
            layerExpectations = parseLayerExpectations(yamlMap["layer_expectations"] as? Map<String, Any>),
            discoveryConfig = parseDiscoveryConfig(yamlMap["discovery_config"] as? Map<String, Any>),
            qualityGates = parseQualityGates(yamlMap["quality_gates"] as? Map<String, Any>),
            caching = parseCachingConfig(yamlMap["caching"] as? Map<String, Any>)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseMetadata(map: Map<String, Any>): ContractMetadata {
        return ContractMetadata(
            name = map["name"] as? String ?: "unnamed",
            version = map["version"] as? String ?: "1.0",
            description = map["description"] as? String,
            author = map["author"] as? String,
            targetLayers = (map["target_layers"] as? List<String>) ?: listOf("all"),
            priority = parsePriority(map["priority"] as? String)
        )
    }

    private fun parsePriority(value: String?): ContractPriority {
        return try {
            value?.let { ContractPriority.valueOf(it.uppercase()) } ?: ContractPriority.MEDIUM
        } catch (e: Exception) {
            ContractPriority.MEDIUM
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLayerExpectations(map: Map<String, Any>?): LayerExpectations? {
        map ?: return null
        return LayerExpectations(
            vision = parseVisionExpectations(map["vision"] as? Map<String, Any>),
            structure = null,
            logic = null,
            flow = null,
            code = null
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseVisionExpectations(map: Map<String, Any>?): VisionExpectations? {
        map ?: return null
        return VisionExpectations(
            requiredCapabilities = (map["required_capabilities"] as? List<String>) ?: emptyList(),
            constraints = (map["constraints"] as? List<String>) ?: emptyList()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseDiscoveryConfig(map: Map<String, Any>?): DiscoveryConfig? {
        map ?: return null
        return DiscoveryConfig(
            depth = parseDepth(map["depth"] as? String),
            includePatterns = (map["include_patterns"] as? List<String>) ?: listOf("**/*.kt", "**/*.java"),
            excludePatterns = (map["exclude_patterns"] as? List<String>) ?: listOf("**/test/**", "**/build/**"),
            autoDiscover = map["auto_discover"] as? Boolean ?: true,
            entryPoints = (map["entry_points"] as? List<String>) ?: emptyList()
        )
    }

    private fun parseDepth(value: String?): DiscoveryDepth {
        return try {
            value?.let { DiscoveryDepth.valueOf(it.uppercase()) } ?: DiscoveryDepth.STANDARD
        } catch (e: Exception) {
            DiscoveryDepth.STANDARD
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseQualityGates(map: Map<String, Any>?): QualityGates? {
        map ?: return null
        return QualityGates(
            minCoveragePercentage = (map["min_coverage_percentage"] as? Number)?.toDouble() ?: 70.0,
            maxOrphanedRules = (map["max_orphaned_rules"] as? Number)?.toInt() ?: 10,
            requiredEntryPoints = (map["required_entry_points"] as? Number)?.toInt(),
            maxComplexity = (map["max_complexity"] as? Number)?.toInt(),
            enforceTestCoverage = map["enforce_test_coverage"] as? Boolean ?: false
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCachingConfig(map: Map<String, Any>?): CachingConfig? {
        map ?: return null
        return CachingConfig(
            enabled = map["enabled"] as? Boolean ?: true,
            ttlMinutes = (map["ttl_minutes"] as? Number)?.toInt() ?: 60,
            resultFile = map["result_file"] as? String,
            invalidateOnChange = map["invalidate_on_change"] as? Boolean ?: true
        )
    }

    // ── Contract Management ────────────────────────────────────────────────

    /**
     * Check if a contract exists.
     */
    fun contractExists(name: String): Boolean {
        return findContractFile(name) != null
    }

    /**
     * List all available contract names.
     */
    fun listContractNames(): List<String> {
        val dir = File(contractsDir)

        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }

        return dir.listFiles { file ->
            file.isFile && file.name.endsWith(CONTRACT_EXTENSION)
        }?.map { it.nameWithoutExtension } ?: emptyList()
    }

    /**
     * Get contract file path.
     */
    fun getContractPath(name: String): String? {
        return findContractFile(name)?.path
    }

    /**
     * Get cache statistics.
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            size = cache.size,
            contracts = cache.keys.filter { it.startsWith("contract:") }
        )
    }

    data class CacheStats(
        val size: Int,
        val contracts: List<String>
    )
}
