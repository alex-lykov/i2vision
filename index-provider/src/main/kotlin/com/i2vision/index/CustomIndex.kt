package com.i2vision.index

import org.slf4j.LoggerFactory
import java.io.File

/**
 * CustomIndex — regex/grep-based [IndexProvider].
 *
 * This is the "always available" fallback that requires no external tools.
 * It wraps [ScannerService] for file/symbol enumeration and adds
 * grep-approximate call hierarchy and entry-point detection on top.
 *
 * Accuracy:
 *   - `findSymbol` / `symbolsInFile` / `listSourceFiles` → exact
 *   - `getCallHierarchy` / `getReachableFiles`           → approximate (grep-based)
 *   - `findEntryPoints`                                   → heuristic
 *   - `findClusters`                                      → directory-cohesion
 */
class CustomIndex(
    private val projectRoot: String,
    private val sourceDirNames: List<String> = listOf("src", "source", "lib")
) : IndexProvider {

    private val log = LoggerFactory.getLogger(CustomIndex::class.java)
    private val scanner = ScannerService(projectRoot)

    // Cache for file lists to avoid re-scanning across cluster discoveries
    private val fileListCache = mutableMapOf<String, List<SourceFile>>()
    private val fileListCacheLock = Any()

    companion object {
        // Kotlin keywords to exclude from call detection
        private val KOTLIN_KEYWORDS = setOf(
            "if", "else", "when", "for", "while", "do", "return",
            "break", "continue", "try", "catch", "finally", "throw",
            "val", "var", "fun", "class", "object", "interface", "enum",
            "import", "package", "as", "is", "in", "null", "true", "false",
            "this", "super", "it", "to", "by", "where", "override",
            "open", "abstract", "sealed", "data", "inner", "private", "public",
            "protected", "internal", "lateinit", "companion", "const"
        )

        // Common property names to exclude
        private val COMMON_KEYWORDS = setOf(
            "id", "name", "type", "value", "size", "key", "data", "text",
            "file", "path", "line", "code", "message", "error", "result"
        )
    }

    // ── Symbol resolution ─────────────────────────────────────────────────────

    override fun findSymbol(name: String): SymbolInfo? =
        scanner.findSymbol(name).firstOrNull()?.toSymbolInfo()

    override fun findSymbols(pattern: String): List<SymbolInfo> =
        scanner.findSymbol(pattern).map { it.toSymbolInfo() }

    override fun symbolsInFile(file: File): List<SymbolInfo> {
        val rel = file.relativeTo(File(projectRoot)).path.replace('\\', '/')
        return scanner.extractSymbols(rel).map { sym ->
            SymbolInfo(
                name = sym.name,
                qualifiedName = "${rel.replace('/', '.')}::${sym.name}",
                kind = sym.kind,
                file = file,
                line = sym.line,
                language = file.extension
            )
        }
    }

    override fun listSourceFiles(subPath: String): List<SourceFile> {
        // Check cache first to avoid re-scanning across cluster discoveries
        synchronized(fileListCacheLock) {
            fileListCache[subPath]?.let { return it }
        }

        // First try the provided subPath
        val standardFiles = scanner.listFiles(subPath)
        if (standardFiles.isNotEmpty()) {
            synchronized(fileListCacheLock) {
                fileListCache[subPath] = standardFiles
            }
            return standardFiles
        }

        // If no files found with provided subPath, search for all configured source directories
        // This handles multi-module projects where each module has its own source directory
        val root = File(projectRoot)
        val allFiles = mutableListOf<SourceFile>()

        root.walkTopDown()
            .filter { it.isDirectory && it.name in sourceDirNames }
            .forEach { srcDir ->
                val relativePath = srcDir.relativeTo(root).path.replace('\\', '/')
                val files = scanner.listFiles(relativePath)
                allFiles.addAll(files)
            }

        synchronized(fileListCacheLock) {
            fileListCache[subPath] = allFiles
        }
        return allFiles
    }

    // ── Call hierarchy (grep-approximate) ─────────────────────────────────────

    override fun getCallHierarchy(symbol: SymbolInfo): CallHierarchy {
        val root = File(projectRoot)

        // Callees: scan symbol's file for identifiers it calls (enhanced detection)
        val calleeCandidates = if (symbol.file.exists()) {
            val lines = symbol.file.readLines()
            val bodyLines = extractBodyLines(lines, symbol.line)
            val callNames = mutableSetOf<String>()

            bodyLines.forEach { line ->
                // 1. Function calls: functionName(...)
                Regex("""(\w+)\s*\(""").findAll(line).forEach { match ->
                    callNames.add(match.groupValues[1])
                }

                // 2. Qualified calls: object.method(...) or Class.staticMethod(...)
                Regex("""[\w.]+\.(\w+)\s*\(""").findAll(line).forEach { match ->
                    callNames.add(match.groupValues[1])
                }

                // 3. Constructor calls: ClassName(...) - capitalized identifier
                Regex("""(?:=|return|,|\()\s*([A-Z]\w+)\s*\(""").findAll(line).forEach { match ->
                    callNames.add(match.groupValues[1])
                }

                // 4. Property delegation: by lazy, by Delegates.observable, etc.
                Regex("""by\s+(\w+)""").findAll(line).forEach { match ->
                    callNames.add(match.groupValues[1])
                }
                Regex("""by\s+\w+\.(\w+)""").findAll(line).forEach { match ->
                    callNames.add(match.groupValues[1])
                }

                // 5. Object instantiation with 'new' (Java style)
                Regex("""new\s+([A-Z]\w+)\s*\(""").findAll(line).forEach { match ->
                    callNames.add(match.groupValues[1])
                }

                // 6. Extension function receivers: receiver.extensionFunc()
                Regex("""(\w+)\s*\.\s*\w+\(""").findAll(line).forEach { match ->
                    callNames.add(match.groupValues[1])
                }

                // 7. Property access that may trigger getters
                Regex("""\.(\w+)(?!\s*\()""").findAll(line).forEach { match ->
                    val propName = match.groupValues[1]
                    // Only add properties that look significant (not common keywords)
                    if (propName.length > 3 && propName[0].isLowerCase() && propName !in COMMON_KEYWORDS) {
                        callNames.add(propName)
                    }
                }
            }

            callNames
                .filter { it.isNotBlank() && it != symbol.name && it !in KOTLIN_KEYWORDS }
                .distinct()
                .mapNotNull { name -> findSymbol(name) }
        } else emptyList()

        // Callers: grep all source files for references to this symbol's name
        val callerLocations = scanner.findSymbol(symbol.name)
            .filter { it.filePath != symbol.file.path.replace('\\', '/') }
            .map { loc ->
                val f = File(root, loc.filePath)
                SymbolInfo(
                    name = loc.symbolName,
                    qualifiedName = "${loc.filePath.replace('/', '.')}::${loc.symbolName}",
                    kind = loc.kind,
                    file = f,
                    line = loc.line,
                    language = f.extension
                )
            }

        log.debug(
            "[CUSTOM_INDEX] callHierarchy({}): {} callees, {} callers",
            symbol.name, calleeCandidates.size, callerLocations.size
        )

        return CallHierarchy(symbol, calleeCandidates, callerLocations)
    }

    // ── Reachability (BFS via grep-approximate call hierarchy) ────────────────

    override fun getReachableFiles(entry: SymbolInfo, depth: Int): Set<File> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<SymbolInfo, Int>>()
        val result = mutableSetOf<File>()
        queue.add(entry to 0)

        while (queue.isNotEmpty()) {
            val (sym, d) = queue.removeFirst()
            if (sym.qualifiedName in visited) continue
            visited += sym.qualifiedName
            result += sym.file

            if (d < depth) {
                runCatching {
                    getCallHierarchy(sym).callees.forEach { callee ->
                        if (callee.qualifiedName !in visited) queue.add(callee to d + 1)
                    }
                }
            }
        }
        log.debug("[CUSTOM_INDEX] reachableFiles(depth={}): {} files from {}", depth, result.size, entry.name)
        return result
    }

    // ── Entry-point detection ─────────────────────────────────────────────────

    override fun findEntryPoints(): List<SymbolInfo> = findAllEntryPoints("unknown")

    /**
     * Architecture-aware entry point detection.
     */
    fun findEntryPoints(archStyle: String): List<SymbolInfo> = findAllEntryPoints(archStyle)

    /**
     * Find entry points across all source directories in multi-module projects.
     */
    fun findAllEntryPoints(archStyle: String = "unknown"): List<SymbolInfo> {
        val normalizedArch = archStyle.lowercase()
        val entryPatterns = when (normalizedArch) {
            "agent_framework" -> listOf(
                Regex("""class\s+\w*Agent\b"""),
                Regex("""class\s+\w*Orchestrator\b"""),
                Regex("""interface\s+\w*Service\b"""),
                Regex("""suspend\s+fun\s+(process|execute|run|handle)\b""")
            )

            else -> listOf(
                Regex("""fun\s+main\s*\("""),
                Regex("""@(Controller|RestController|Service|Component|Repository)\b"""),
                Regex("""@(GetMapping|PostMapping|PutMapping|DeleteMapping|RequestMapping)\b"""),
                Regex("""@Preview\b"""),
                Regex("""class\s+\w*Agent\b"""),
                Regex("""class\s+\w*Orchestrator\b"""),
                Regex("""app\.(get|post|put|delete|use)\s*\("""),
                Regex("""route\s*\("""),
                Regex("""def\s+(index|main|run|start|handle)\s*\(""")
            )
        }

        val sourcePaths = mutableListOf<String>()

        if (File(projectRoot, "src").exists()) {
            sourcePaths.add("src")
        }

        File(projectRoot).listFiles()?.forEach { moduleDir ->
            if (moduleDir.isDirectory && !moduleDir.name.startsWith(".") && moduleDir.name != "build") {
                val moduleSrc = File(moduleDir, "src")
                if (moduleSrc.exists() && moduleSrc.isDirectory) {
                    sourcePaths.add("${moduleDir.name}/src")
                }
            }
        }

        val coreDir = File(projectRoot, "core")
        if (coreDir.exists() && coreDir.isDirectory) {
            coreDir.listFiles()?.forEach { submodule ->
                if (submodule.isDirectory) {
                    val submoduleSrc = File(submodule, "src")
                    if (submoduleSrc.exists() && submoduleSrc.isDirectory) {
                        sourcePaths.add("core/${submodule.name}/src")
                    }
                }
            }
        }

        log.debug("[CUSTOM_INDEX] Scanning {} source paths: {}", sourcePaths.size, sourcePaths)

        val patternMatches = sourcePaths.flatMap { srcPath ->
            scanner.listFiles(srcPath).flatMap { sf ->
                val file = File(projectRoot, sf.path)
                if (!file.exists()) return@flatMap emptyList()
                val lines = runCatching { file.readLines() }.getOrElse { emptyList() }
                lines.flatMapIndexed { idx, line ->
                    if (entryPatterns.any { it.containsMatchIn(line) }) {
                        val sym = symbolsInFile(file).firstOrNull { it.line == idx + 1 }
                            ?: SymbolInfo(
                                name = file.nameWithoutExtension,
                                qualifiedName = "${sf.path.replace('/', '.')}::entry",
                                kind = "entry",
                                file = file,
                                line = idx + 1,
                                language = sf.language
                            )
                        listOf(sym)
                    } else emptyList()
                }
            }
        }

        // Agent frameworks often expose entry points through class/interface contracts.
        val fallbackMatches = if (patternMatches.isEmpty() && normalizedArch == "agent_framework") {
            log.info("[CUSTOM_INDEX] No regex entry points found for agent_framework; using symbol fallback")
            sourcePaths.flatMap { srcPath ->
                scanner.listFiles(srcPath).flatMap { sf ->
                    val file = File(projectRoot, sf.path)
                    if (!file.exists()) return@flatMap emptyList()
                    symbolsInFile(file).filter { sym ->
                        (sym.kind == "class" || sym.kind == "interface" || sym.kind == "object") &&
                                (sym.name.endsWith("Agent") ||
                                        sym.name.endsWith("Orchestrator") ||
                                        sym.name.endsWith("Service") ||
                                        sym.name.contains("Router"))
                    }
                }
            }
        } else {
            emptyList()
        }

        return (patternMatches + fallbackMatches)
            .distinctBy { "${it.file.path}:${it.line}:${it.name}" }
            .also {
                log.info(
                    "[CUSTOM_INDEX] findAllEntryPoints(arch={}): {} found from {} paths",
                    normalizedArch,
                    it.size,
                    sourcePaths.size
                )
            }
    }

    // ── Cluster suggestion (directory-cohesion) ───────────────────────────────

    override fun findClusters(subPath: String): List<ClusterSuggestion> {
        val byDir = scanner.listFiles(subPath).groupBy { sf ->
            sf.path.split('/').dropLast(1).takeLast(2).joinToString("/")
        }

        return byDir.map { (dir, files) ->
            val fileObjs = files.map { File(projectRoot, it.path) }.toSet()
            val entries = files.flatMap { sf ->
                symbolsInFile(File(projectRoot, sf.path)).filter {
                    it.kind in setOf("class", "object", "fun")
                }
            }.take(3)
            val cohesion = if (files.size >= 2) 0.7 else 0.4

            ClusterSuggestion(
                name = dir.substringAfterLast('/').ifBlank { dir },
                entryPoints = entries,
                files = fileObjs,
                cohesion = cohesion
            )
        }.filter { it.cohesion >= 0.4 }
            .also { log.debug("[CUSTOM_INDEX] findClusters: {} suggestions", it.size) }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun extractBodyLines(lines: List<String>, startLine: Int): List<String> {
        val start = (startLine - 1).coerceAtLeast(0)
        val end = (start + 60).coerceAtMost(lines.size)
        return lines.subList(start, end)
    }

    private fun SymbolLocation.toSymbolInfo(): SymbolInfo {
        val f = File(projectRoot, filePath)
        return SymbolInfo(
            name = symbolName,
            qualifiedName = "${filePath.replace('/', '.')}::$symbolName",
            kind = kind,
            file = f,
            line = line,
            language = f.extension
        )
    }
}
