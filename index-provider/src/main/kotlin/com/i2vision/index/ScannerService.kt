package com.i2vision.index

import com.i2vision.storage.I2VisionPaths
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Code Intelligence Layer — language-agnostic with pluggable adapters.
 *
 * Implements the interface specified in the vision document:
 *   listFiles()       — enumerate source files in the project
 *   extractSymbols()  — extract top-level symbols from a file
 *   findSymbol()      — locate a symbol across the codebase
 *
 * Adapters available: Kotlin, Python, JavaScript/TypeScript, Generic (regex)
 */
class ScannerService(private val projectRoot: String) {
    private val log = LoggerFactory.getLogger(ScannerService::class.java)

    private val adapters: List<LanguageAdapter> = listOf(
        KotlinAdapter(),
        PythonAdapter(),
        JavaScriptAdapter(),
        GenericAdapter()
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * List all source files under [subPath] (relative to project root).
     * Excludes build outputs, hidden dirs, and binary files.
     * For multi-module projects, if the default "src" doesn't exist at root,
     * searches for all src directories in the project.
     */
    fun listFiles(subPath: String = "src"): List<SourceFile> {
        val root = File(projectRoot, subPath)

        // If the specified path exists, use it directly
        if (root.exists()) {
            return root.walkTopDown()
                .filter { it.isFile && !isExcluded(it) }
                .map { file ->
                    SourceFile(
                        path = file.relativeTo(File(projectRoot)).path.replace('\\', '/'),
                        language = detectLanguage(file),
                        sizeBytes = file.length()
                    )
                }
                .toList()
                .also { log.debug("[SCANNER] listFiles({}): {} files", subPath, it.size) }
        }

        // For multi-module projects: if "src" doesn't exist at root, search for all src directories
        if (subPath == "src") {
            log.debug("[SCANNER] Top-level src not found, searching for all src directories in multi-module project")
            return findAllSrcDirectories()
        }

        log.warn("[SCANNER] listFiles({}) skipped: path does not exist under project root {}", subPath, projectRoot)
        return emptyList()
    }

    /**
     * Find all src directories in a multi-module project.
     * Searches recursively for directories named "src" under the project root.
     */
    private fun findAllSrcDirectories(): List<SourceFile> {
        val srcDirs = File(projectRoot)
            .walkTopDown()
            .maxDepth(5)  // Limit depth to avoid excessive searching
            .filter { it.isDirectory && it.name == "src" }
            .toList()

        if (srcDirs.isEmpty()) {
            log.warn("[SCANNER] No src directories found in project")
            return emptyList()
        }

        log.debug(
            "[SCANNER] Found {} src directories: {}",
            srcDirs.size,
            srcDirs.map { it.relativeTo(File(projectRoot)) })

        return srcDirs.flatMap { srcDir ->
            srcDir.walkTopDown()
                .filter { it.isFile && !isExcluded(it) }
                .map { file ->
                    SourceFile(
                        path = file.relativeTo(File(projectRoot)).path.replace('\\', '/'),
                        language = detectLanguage(file),
                        sizeBytes = file.length()
                    )
                }
                .toList()
        }.also { log.debug("[SCANNER] findAllSrcDirectories: {} total files", it.size) }
    }

    /**
     * Extract top-level symbols from a single file.
     * Returns empty list if the file is not recognised.
     */
    fun extractSymbols(filePath: String): List<CodeSymbol> {
        val file = File(projectRoot, filePath).takeIf { it.exists() } ?: return emptyList()
        val adapter = adapterFor(file)
        return adapter.extractSymbols(file)
            .also { log.debug("[SCANNER] extractSymbols({}): {} symbols via {}", filePath, it.size, adapter.language) }
    }

    /**
     * Find all occurrences of [symbolName] across the project.
     */
    fun findSymbol(symbolName: String, inSubPath: String = "src"): List<SymbolLocation> {
        return listFiles(inSubPath).flatMap { sf ->
            val file = File(projectRoot, sf.path)
            val adapter = adapterFor(file)
            adapter.findSymbol(file, symbolName)
        }.also { log.debug("[SCANNER] findSymbol({}): {} occurrences", symbolName, it.size) }
    }

    /**
     * Scan all logic entity IDs referenced in semantic YAML files,
     * preferring `.semantic-cache/` and committed overrides.
     */
    fun scanLogicEntities(): List<LogicEntity> {
        val roots = buildList {
            add(I2VisionPaths.getProjectCacheDir(projectRoot))
            add(File(projectRoot, "${SemanticPathResolver.OVERRIDES_ROOT}/logic"))
            // Optional fallback for dual-mode artifacts still written under module-local src/logic.
            addAll(findModuleLogicRoots())
        }.distinctBy { it.absolutePath }.filter { it.exists() }
        if (roots.isEmpty()) return emptyList()
        return roots.asSequence()
            .flatMap { root ->
                root.walkTopDown()
                    .filter {
                        it.isFile &&
                                it.extension == "yaml" &&
                                !it.name.contains("config") &&
                                it.path.replace('\\', '/').contains("/logic/")
                    }
                    .asSequence()
            }
            .distinctBy { it.absolutePath }
            .flatMap { scanYamlForEntities(it) }
            .toList()
            .distinctBy { it.id }
            .also { log.debug("[SCANNER] scanLogicEntities: {} entities", it.size) }
    }

    private fun findModuleLogicRoots(): List<File> {
        return File(projectRoot)
            .walkTopDown()
            .maxDepth(4)
            .filter { it.isDirectory && it.path.replace('\\', '/').endsWith("/src/logic") }
            .toList()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun adapterFor(file: File): LanguageAdapter =
        adapters.firstOrNull { it.supports(file) } ?: adapters.last()

    private fun detectLanguage(file: File): String =
        adapterFor(file).language

    private fun isExcluded(file: File): Boolean {
        val path = file.path.replace('\\', '/')
        return path.contains("/build/") ||
                path.contains("/.gradle/") ||
                path.contains("/node_modules/") ||
                path.contains("/.git/") ||
                file.name.startsWith(".")
    }

    @Suppress("UNCHECKED_CAST")
    private fun scanYamlForEntities(file: File): List<LogicEntity> {
        return try {
            val raw = org.yaml.snakeyaml.Yaml().load<Any>(file.readText())
            val result = mutableListOf<LogicEntity>()
            extractIds(raw, file.nameWithoutExtension, result)
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractIds(node: Any?, namespace: String, out: MutableList<LogicEntity>) {
        when (node) {
            is Map<*, *> -> {
                val id = node["id"] as? String
                val name = node["name"] as? String ?: node["key"] as? String
                if (id != null) out.add(LogicEntity(id = id, displayName = name ?: id, sourceFile = namespace))
                node.values.forEach { extractIds(it, namespace, out) }
            }

            is List<*> -> node.forEach { extractIds(it, namespace, out) }
        }
    }
}

// ─── Data types ───────────────────────────────────────────────────────────────

data class SourceFile(val path: String, val language: String, val sizeBytes: Long)

data class CodeSymbol(
    val name: String,
    val kind: String,          // class | function | property | interface | object | enum
    val filePath: String,
    val line: Int
)

data class SymbolLocation(
    val filePath: String,
    val line: Int,
    val symbolName: String,
    val kind: String
)

data class LogicEntity(
    val id: String,
    val displayName: String,
    val sourceFile: String     // YAML file (without .yaml)
)

// ─── Language adapters ────────────────────────────────────────────────────────

interface LanguageAdapter {
    val language: String
    fun supports(file: File): Boolean
    fun extractSymbols(file: File): List<CodeSymbol>
    fun findSymbol(file: File, symbolName: String): List<SymbolLocation>
}

/** Kotlin adapter — regex-based extraction (no PSI dependency). */
class KotlinAdapter : LanguageAdapter {
    override val language = "kotlin"
    override fun supports(file: File) = file.extension == "kt"

    private val symbolRegex = Regex(
        """^(?:(?:public|internal|private|protected|abstract|open|data|sealed|inline|suspend|override)\s+)*"""
                + """(class|object|interface|fun|val|var|enum class|typealias)\s+(\w+)"""
    )

    override fun extractSymbols(file: File): List<CodeSymbol> = buildList {
        file.readLines().forEachIndexed { idx, line ->
            symbolRegex.find(line.trim())?.let { m ->
                add(
                    CodeSymbol(
                        name = m.groupValues[2],
                        kind = m.groupValues[1].trim(),
                        filePath = file.path,
                        line = idx + 1
                    )
                )
            }
        }
    }

    override fun findSymbol(file: File, symbolName: String): List<SymbolLocation> = buildList {
        file.readLines().forEachIndexed { idx, line ->
            if (line.contains(symbolName)) {
                val kind = symbolRegex.find(line.trim())?.groupValues?.get(1)?.trim() ?: "reference"
                add(SymbolLocation(file.path, idx + 1, symbolName, kind))
            }
        }
    }
}

class PythonAdapter : LanguageAdapter {
    override val language = "python"
    override fun supports(file: File) = file.extension == "py"
    private val defRegex = Regex("""^(def|class|async def)\s+(\w+)""")

    override fun extractSymbols(file: File) = buildList<CodeSymbol> {
        file.readLines().forEachIndexed { idx, line ->
            defRegex.find(line)?.let { m ->
                add(CodeSymbol(m.groupValues[2], m.groupValues[1], file.path, idx + 1))
            }
        }
    }

    override fun findSymbol(file: File, symbolName: String) = buildList<SymbolLocation> {
        file.readLines().forEachIndexed { idx, line ->
            if (line.contains(symbolName))
                add(SymbolLocation(file.path, idx + 1, symbolName, "reference"))
        }
    }
}

class JavaScriptAdapter : LanguageAdapter {
    override val language = "javascript"
    override fun supports(file: File) = file.extension in listOf("js", "ts", "jsx", "tsx")
    private val defRegex = Regex("""(?:function|class|const|let|var)\s+(\w+)""")

    override fun extractSymbols(file: File) = buildList<CodeSymbol> {
        file.readLines().forEachIndexed { idx, line ->
            defRegex.find(line)?.let { m ->
                add(CodeSymbol(m.groupValues[1], "declaration", file.path, idx + 1))
            }
        }
    }

    override fun findSymbol(file: File, symbolName: String) = buildList<SymbolLocation> {
        file.readLines().forEachIndexed { idx, line ->
            if (line.contains(symbolName))
                add(SymbolLocation(file.path, idx + 1, symbolName, "reference"))
        }
    }
}

/** Fallback for any file type — simple grep. */
class GenericAdapter : LanguageAdapter {
    override val language = "generic"
    override fun supports(file: File) = true
    override fun extractSymbols(file: File) = emptyList<CodeSymbol>()

    override fun findSymbol(file: File, symbolName: String) = buildList<SymbolLocation> {
        runCatching {
            file.readLines().forEachIndexed { idx, line ->
                if (line.contains(symbolName))
                    add(SymbolLocation(file.path, idx + 1, symbolName, "reference"))
            }
        }
    }
}
