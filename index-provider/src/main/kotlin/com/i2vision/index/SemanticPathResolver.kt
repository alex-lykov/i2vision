package com.i2vision.index

import com.i2vision.storage.I2VisionPaths
import java.io.File

enum class SemanticArtifactMode {
    legacy,
    dual,
    cache;

    companion object {
        val DEFAULT = cache
    }
}

/**
 * Resolves semantic artifact locations.
 *
 * Read priority:
 * 1) .vision-ai/overrides/<layer>/<file>
 * 2) explicit generated path (typically under .semantic-cache)
 */
class SemanticPathResolver(
    private val projectRoot: String,
    private val mode: SemanticArtifactMode = SemanticArtifactMode.cache
) {

    companion object {
        const val OVERRIDES_ROOT = ".vision-ai/overrides"
    }
    
    private val cacheRoot: String by lazy {
        I2VisionPaths.getProjectCacheDir(projectRoot).absolutePath
    }

    fun getMode(): SemanticArtifactMode = mode

    @Suppress("unused")
    fun expectedClusterRoot(modulePath: String?): String = when (mode) {
        SemanticArtifactMode.legacy -> {
            val module = normalizedModule(modulePath)
            if (module.isBlank()) "src" else "$module/src"
        }
        SemanticArtifactMode.dual,
        SemanticArtifactMode.cache -> {
            val module = normalizedModule(modulePath)
            if (module.isBlank()) cacheRoot else "$cacheRoot/$module"
        }
    }

    fun outputDirectories(layer: String, modulePath: String?, packagePath: String?): List<String> {
        val primary = primaryLayerKotlinDirectoryPath(layer, modulePath, packagePath)
        val mirror = mirrorLayerKotlinDirectoryPath(layer, modulePath, packagePath)
        return listOfNotNull(primary, mirror).distinct()
    }

    fun primaryLayerKotlinDirectoryPath(layer: String, modulePath: String?, packagePath: String?): String = when (mode) {
        SemanticArtifactMode.legacy -> legacyLayerKotlinDirectoryPath(layer, modulePath, packagePath)
        SemanticArtifactMode.dual,
        SemanticArtifactMode.cache -> cacheLayerKotlinDirectoryPath(layer, modulePath, packagePath)
    }

    fun mirrorLayerKotlinDirectoryPath(layer: String, modulePath: String?, packagePath: String?): String? = when (mode) {
        SemanticArtifactMode.dual -> legacyLayerKotlinDirectoryPath(layer, modulePath, packagePath)
        else -> null
    }

    fun cacheLayerKotlinDirectoryPath(layer: String, modulePath: String?, packagePath: String?): String {
        val module = normalizedModule(modulePath)
        require(module.isNotBlank()) { "Module path cannot be blank - semantic cache requires cluster_id folder structure" }
        val base = "$cacheRoot/$module/${layer.lowercase()}"
        return if (packagePath.isNullOrBlank()) base else "$base/kotlin/$packagePath"
    }

    fun legacyLayerKotlinDirectoryPath(layer: String, modulePath: String?, packagePath: String?): String {
        val base = "src/${layer.lowercase()}/kotlin"
        val module = normalizedModule(modulePath)
        val withModule = if (module.isBlank()) base else "$module/$base"
        return if (packagePath.isNullOrBlank()) withModule else "$withModule/$packagePath"
    }

    fun resolveInputArtifact(layer: String, explicitRelativePath: String): File {
        val explicit = File(projectRoot, explicitRelativePath)
        val override = File(projectRoot, "$OVERRIDES_ROOT/${layer.lowercase()}/${explicit.name}")
        if (override.exists()) return override
        if (explicit.exists()) return explicit

        val fallback = fallbackInputPath(layer, explicitRelativePath)
        return if (fallback != null && fallback.exists()) fallback else explicit
    }

    private fun fallbackInputPath(layer: String, explicitRelativePath: String): File? {
        val normalized = explicitRelativePath.replace('\\', '/').trim('/')
        return when (mode) {
            SemanticArtifactMode.legacy -> {
                if (!normalized.startsWith("$cacheRoot/")) return null
                val rem = normalized.removePrefix("$cacheRoot/")
                val parts = rem.split('/')
                if (parts.size < 3) return null
                val module = parts[0]
                val suffix = parts.drop(1).joinToString("/")
                val modulePrefix = if (module.isBlank()) "" else "$module/"
                File(projectRoot, "${modulePrefix}src/$suffix")
            }
            SemanticArtifactMode.cache,
            SemanticArtifactMode.dual -> {
                val idx = normalized.indexOf("/src/${layer.lowercase()}/")
                if (idx <= 0) return null
                val module = normalized.substring(0, idx).ifBlank { "" }
                val suffix = normalized.substring(idx + 1).removePrefix("src/")
                File(projectRoot, "$cacheRoot/$module/$suffix")
            }
        }
    }

    private fun normalizedModule(modulePath: String?): String {
        return modulePath.orEmpty().replace('\\', '/').trim('/').trim()
    }
}
