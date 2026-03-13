import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") // Add Kotlin Serialization plugin (version managed by root)
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.jetbrains.exposed:exposed-core:0.49.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.49.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.49.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.49.0") // Added for timestamp support
    implementation("org.xerial:sqlite-jdbc:3.45.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3") // For @Serializable
}

val generatedSettingsPackage = "com.alyk.ai.koog.database.settings" // Define package name for generated code

tasks.register<DefaultTask>("generateTerminalSettings") {
    val propertiesFile = project.layout.projectDirectory.file("src/main/resources/terminal_settings.properties").asFile
    val outputDir = project.layout.buildDirectory.dir("generated/terminal/settings").get().asFile
    val docsDir = project.layout.buildDirectory.dir("docs").get().asFile
    val schemaDir = project.layout.buildDirectory.dir("generated/terminal/schema").get().asFile

    inputs.file(propertiesFile)
    outputs.dir(outputDir)
    outputs.dir(docsDir)
    outputs.dir(schemaDir)

    doLast {
        outputDir.mkdirs()
        docsDir.mkdirs()
        schemaDir.mkdirs()

        val generator = TerminalSettingsGenerator()
        val settings = generator.parsePropertiesFile(propertiesFile)

        // Generate Kotlin enum
        val enumFile = File(outputDir, "TerminalSettingKey.kt")
        enumFile.writeText(generator.generateSettingsEnum(settings, generatedSettingsPackage))

        // Generate documentation
        val docsFile = File(docsDir, "terminal-settings.md")
        docsFile.writeText(generator.generateDocumentation(settings))
        
        // Generate JSON schema for validation
        val schemaFile = File(schemaDir, "terminal-settings-schema.json")
        schemaFile.writeText(generateJsonSchema(settings))
    }
}

// Add generated source to compilation
kotlin {
    sourceSets.main {
        kotlin.srcDir(layout.buildDirectory.dir("generated/terminal/settings"))
    }
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(tasks.named("generateTerminalSettings"))
}

// Helper function for JSON schema
fun generateJsonSchema(settings: List<TerminalSetting>): String {
    return """
    {
        "${'$'}schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "title": "Terminal Settings Schema",
        "description": "Runtime state for terminal UI settings",
        "properties": {
            ${settings.joinToString(",\n            ") { setting ->
                "\"${setting.key}\": {\n" +
                "                \"type\": \"boolean\",\n" +
                "                \"description\": \"${setting.description.replace("\"", "\\\"")}\",\n" +
                "                \"default\": ${setting.defaultValue}\n" +
                "            }"
            }}
        },
        "additionalProperties": false
    }
    """.trimIndent()
}
