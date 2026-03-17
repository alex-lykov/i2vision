package com.i2vision.agent.engines

import org.slf4j.LoggerFactory

internal object ToolNameNormalizer {
    private val log = LoggerFactory.getLogger(ToolNameNormalizer::class.java)

    /**
     * Normalize tool names from various model outputs to canonical tool names.
     * Handles:
     * - Different casing: ReadFile, read_file, readFile, readfile
     * - Tool prefixes: tool:read_file, Tool.ReadFile
     * - Aliases: open_file → read_file, print_tree → list_dir
     */
    fun normalize(rawToolName: String): String {
        val leaf = rawToolName
            .trim()
            // camelCase / PascalCase → snake_case before lowercasing
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
            .lowercase()
            .replace('-', '_')
            .removePrefix("tool:")
            .removePrefix("tool.")
            .removePrefix("koog:")
            .removePrefix("koog.")
            .substringAfterLast(':')
            .substringAfterLast('.')
            .substringAfterLast('/')

        val canonical = when (leaf) {
            // read_file aliases
            "open_file", "read_file_text", "read_text_file", "read_file_tool", "readfile", "read", "open", "get_file", "get_content" -> "read_file"
            
            // list_dir aliases
            "print_tree", "list_directory", "list_files", "listdir", "list", "show_files", "show_directory" -> "list_dir"
            
            // write_file aliases
            "write_file_tool", "write_text_file", "writefile", "write", "save_file", "create_file", "put_file", "update_file" -> "write_file"
            
            // edit_file aliases (typically same as write_file)
            "editfile", "edit", "modify_file", "patch_file" -> "edit_file"
            
            // search aliases
            "search_files", "search_file", "search_files_regex", "find_in_files", "find", "search", "grep", "regex_search" -> "grep_search"
            
            else -> leaf
        }

        if (canonical != rawToolName) {
            log.debug("[TOOL_NORM] {} → {}", rawToolName, canonical)
        }
        return canonical
    }
}

