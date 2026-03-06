package com.boxtox

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer

// Define data classes for tool calls
@Serializable
data class ToolCall(
    val tool: String,
    val args: JsonElement // Use JsonElement to allow flexible argument structures
)

@Serializable
data class ReadFileArgs(val path: String)

@Serializable
data class WriteFileArgs(val path: String, val content: String)

@Serializable
data class ScanProjectArgs(val path: String)

class CodingAgent {

    private val ollamaClient: OllamaClient
    private val agent: AIAgent<String, String>
    private val json = Json { ignoreUnknownKeys = true }
    private val conversationHistory = mutableListOf<String>()

    init {
        println("🔧 Initializing Coding Agent...")

        // 1. Connect to local Ollama
        ollamaClient = OllamaClient(baseUrl = "http://localhost:11434")

        // 2. Create the model
        val modelName = "codellama:7b"
        val llmModel = LLModel(
            id = modelName,
            provider = LLMProvider.Ollama,
            contextLength = 4096,
            maxOutputTokens = 2048,
            capabilities = null
        )

        // 3. Create prompt executor using reflection
        // Find and instantiate the executor class, then use Java reflection to call AIAgent
        val promptExecutor = runBlocking {
            try {
                // Try common class names and package paths
                val classNames = listOf(
                    "ai.koog.prompt.executor.ollama.OllamaPromptExecutor",
                    "ai.koog.prompt.executor.ollama.OllamaExecutor",
                    "ai.koog.prompt.executor.ollama.executor.OllamaPromptExecutor"
                )
                
                var executor: Any? = null
                for (className in classNames) {
                    try {
                        val executorClass = Class.forName(className)
                        executor = executorClass.getConstructor(OllamaClient::class.java).newInstance(ollamaClient)
                        break
                    } catch (e: Exception) {
                        continue
                    }
                }
                
                executor ?: throw IllegalStateException("Could not find Ollama prompt executor class. Tried: $classNames")
            } catch (e: Exception) {
                throw IllegalStateException("Failed to create prompt executor: ${e.message}. Please check Koog library version and API.", e)
            }
        }

        // 4. Create the AI agent using Java reflection to bypass type checking
        val systemPromptText = """
                You are an expert AI programmer and a helpful coding assistant.
                You can help with writing code, debugging, and answering questions about software development.
                
                You have access to the following tools:
                
                1. **read_file**: Reads the content of a file.
                   - Input: {"tool": "read_file", "args": {"path": "path/to/file.txt"}}
                   - Output: The content of the file, or an error message.
                
                2. **write_file**: Writes content to a file.
                   - Input: {"tool": "write_file", "args": {"path": "path/to/file.txt", "content": "file content"}}
                   - Output: "Successfully wrote to path/to/file.txt" or an error message.
                
                3. **scan_project**: Scans a directory and lists its contents.
                   - Input: {"tool": "scan_project", "args": {"path": "path/to/directory"}}
                   - Output: A list of files and directories, or an error message.
                
                You should always follow this thought-action-observation loop:
                
                1. **Thought**: First, think step-by-step about what you need to do.
                2. **Action**: If you decide to use a tool, output a JSON object in the format: `{"tool": "tool_name", "args": {...}}`.
                3. **Observation**: After an Action, the tool's output will be provided to you.
                4. **Answer**: If you have enough information, provide your final answer.
                
                Always start with a Thought.
            """.trimIndent()
        
        // Use Java reflection to create AIAgent since we can't import PromptExecutor at compile time
        val agentClass = AIAgent::class.java
        @Suppress("UNCHECKED_CAST")
        agent = agentClass.getConstructor(
            Class.forName("ai.koog.prompt.executor.PromptExecutor"),
            LLModel::class.java,
            String::class.java
        ).newInstance(promptExecutor, llmModel, systemPromptText) as AIAgent<String, String>

        println("✅ Agent initialized! How can I help you today?")
    }

    suspend fun processUserInput(input: String): String {
        if (input.equals("/clear", ignoreCase = true)) {
            conversationHistory.clear()
            return "🧹 Conversation history cleared."
        }
        
        conversationHistory.add("User: $input")
        val response = askAgent(input)
        conversationHistory.add("Agent: $response")
        return response
    }

    private fun readFile(filePath: String): String {
        return try {
            val path = Paths.get(filePath)
            if (Files.exists(path)) {
                "TOOL_OUTPUT: Content of $filePath:\n\n${path.readText()}"
            } else {
                "TOOL_OUTPUT: ❌ File not found: $filePath"
            }
        } catch (e: Exception) {
            "TOOL_OUTPUT: ❌ Error reading file: ${e.message}"
        }
    }

    private fun writeFile(filePath: String, content: String): String {
        return try {
            val path = Paths.get(filePath)
            path.writeText(content)
            "TOOL_OUTPUT: ✅ Successfully wrote to $filePath"
        } catch (e: Exception) {
            "TOOL_OUTPUT: ❌ Error writing file: ${e.message}"
        }
    }

    private fun scanProject(directoryPath: String): String {
        val rootPath = Paths.get(directoryPath)
        if (!Files.isDirectory(rootPath)) {
            return "TOOL_OUTPUT: ❌ Invalid directory path: $directoryPath"
        }

        val fileList = StringBuilder("TOOL_OUTPUT: 📂 Project Structure for: $directoryPath\n")
        try {
            Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    fileList.append("  - ${rootPath.relativize(file)}\n")
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: Exception) {
            return "TOOL_OUTPUT: ❌ Error scanning directory: ${e.message}"
        }
        return fileList.toString()
    }

    private suspend fun askAgent(initialPrompt: String): String {
        println("🤖 Thinking...")
        var currentPrompt = conversationHistory.joinToString("\n")
        var finalResponse = "Agent did not provide a final response."

        for (i in 0 until 5) {
            val agentResponse: String = agent.run(currentPrompt)
            println("RAW AGENT RESPONSE:\n$agentResponse\n---")

            if (agentResponse.isBlank()) {
                return "Agent did not provide a response."
            }

            val actionJson = extractActionJson(agentResponse)
            val answer = extractAnswer(agentResponse)

            if (actionJson != null) {
                try {
                    val toolCall = json.decodeFromString<ToolCall>(actionJson)
                    println("🛠️ Agent wants to use tool: ${toolCall.tool}")

                    val toolOutput = executeTool(toolCall)
                    println("✅ Tool output received.")

                    currentPrompt += "\n$agentResponse\nObservation: $toolOutput\nThought:"
                    finalResponse = "Agent executed a tool. Waiting for its next thought..."
                } catch (e: Exception) {
                    println("❌ Error executing tool or parsing JSON: ${e.message}")
                    currentPrompt += "\n$agentResponse\nObservation: Error: ${e.message}\nThought:"
                    finalResponse = "Error during tool execution."
                }
            } else if (answer != null) {
                finalResponse = answer
                break
            } else {
                finalResponse = agentResponse
                break
            }
        }
        return finalResponse
    }
    
    private fun extractActionJson(response: String): String? {
        val actionMarker = "Action:"
        val actionIndex = response.indexOf(actionMarker)
        if (actionIndex == -1) return null
        
        val jsonStart = response.indexOf('{', actionIndex)
        val jsonEnd = response.lastIndexOf('}')
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd < jsonStart) return null
        
        return response.substring(jsonStart, jsonEnd + 1)
    }

    private fun extractAnswer(response: String): String? {
        val answerMarker = "Answer:"
        val answerIndex = response.indexOf(answerMarker)
        return if (answerIndex != -1) {
            response.substring(answerIndex + answerMarker.length).trim()
        } else {
            null
        }
    }

    private fun executeTool(toolCall: ToolCall): String {
        return when (toolCall.tool) {
            "read_file" -> {
                val args = json.decodeFromJsonElement(ReadFileArgs.serializer(), toolCall.args)
                readFile(args.path)
            }
            "write_file" -> {
                val args = json.decodeFromJsonElement(WriteFileArgs.serializer(), toolCall.args)
                writeFile(args.path, args.content)
            }
            "scan_project" -> {
                val args = json.decodeFromJsonElement(ScanProjectArgs.serializer(), toolCall.args)
                scanProject(args.path)
            }
            else -> "TOOL_OUTPUT: ❌ Unknown tool: ${toolCall.tool}"
        }
    }
}

fun main() = runBlocking {
    val agent = CodingAgent()
    println("Enter your commands below.")
    println("  /clear         - Clear conversation history")
    println("  <prompt>       - Ask the agent anything.")
    println("  exit           - Quit")

    while (true) {
        print("\n> ")
        val input = readLine()
        if (input == null || input.equals("exit", ignoreCase = true)) {
            println("👋 Goodbye!")
            break
        }

        if (input.isNotBlank()) {
            val response = agent.processUserInput(input)
            println("\n--- Agent Response ---")
            println(response)
            println("----------------------")
        }
    }
}
