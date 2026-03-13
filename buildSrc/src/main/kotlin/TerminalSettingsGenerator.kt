import java.io.File

data class TerminalSetting(
    val key: String,
    val description: String,
    val defaultValue: Boolean,
    val category: String
)

class TerminalSettingsGenerator {
    
    fun parsePropertiesFile(file: File): List<TerminalSetting> {
        val settings = mutableListOf<TerminalSetting>()
        var currentCategory = "General"
        
        file.readLines().forEach { line ->
            val trimmedLine = line.trim()
            when {
                trimmedLine.startsWith('[') && trimmedLine.endsWith(']') -> {
                    currentCategory = trimmedLine.substring(1, trimmedLine.length - 1).trim()
                }
                trimmedLine.isNotBlank() && !trimmedLine.startsWith('#') && trimmedLine.contains('|') -> {
                    val parts = trimmedLine.split('|').map { it.trim() }
                    if (parts.size >= 3) {
                        settings.add(
                            TerminalSetting(
                                key = parts[0],
                                description = parts[1],
                                defaultValue = parts[2].toBooleanStrictOrNull() ?: false,
                                category = currentCategory
                            )
                        )
                    }
                }
            }
        }
        
        return settings
    }
    
    private fun sanitize(name: String): String {
        return name.replace(" ", "_")
                   .replace("&", "AND")
                   .replace("-", "_")
                   .uppercase()
    }
    
    fun generateSettingsEnum(settings: List<TerminalSetting>, packageName: String): String {
        val categories = settings.groupBy { it.category }
        
        return """
            // Generated file - DO NOT EDIT
            // Generated from terminal_settings.properties
            package $packageName
            
            import kotlinx.serialization.Serializable
            
            /**
             * Terminal UI settings - definitions are generated at build time
             * from terminal_settings.properties
             */
            @Serializable
            enum class TerminalSettingKey(
                val key: String,
                val description: String,
                val defaultValue: Boolean,
                val category: Category
            ) {
                ${settings.joinToString(",\n                ") { setting ->
                    """
                    ${sanitize(setting.key.replace(".", "_"))}(
                        key = "${setting.key}",
                        description = "${setting.description.replace("\"", "\\\"")}",
                        defaultValue = ${setting.defaultValue},
                        category = Category.${sanitize(setting.category)}
                    )
                    """.trimIndent()
                }};
                
                enum class Category(val displayName: String) {
                    ${categories.keys.joinToString(",\n                    ") { category ->
                        "${sanitize(category)}(\"$category\")"
                    }};
                }
                
                companion object {
                    fun fromKey(key: String): TerminalSettingKey? = 
                        values().find { it.key == key }
                    
                    val allSettings: List<TerminalSettingKey> = values().toList()
                    
                    fun getByCategory(category: Category): List<TerminalSettingKey> = 
                        values().filter { it.category == category }
                    
                    val defaultStates: Map<String, Boolean> = values().associate { 
                        it.key to it.defaultValue 
                    }
                }
            }
        """.trimIndent()
    }
    
    fun generateDocumentation(settings: List<TerminalSetting>): String {
        return """
            # Terminal Settings Documentation
            _Generated on: ${java.time.Instant.now()}_
            
            ${settings.groupBy { it.category }.map { (category, categorySettings) ->
                """
                ## $category
                
                | Setting Key | Description | Default |
                |-------------|-------------|---------|
                ${categorySettings.joinToString("\n") { setting ->
                    "| `${setting.key}` | ${setting.description} | `${setting.defaultValue}` |"
                }}
                
                """
            }.joinToString("\n")}
        """.trimIndent()
    }
}
