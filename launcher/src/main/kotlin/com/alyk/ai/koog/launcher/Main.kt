package com.alyk.ai.koog.launcher

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.readText
import kotlin.io.path.writeText

// Define data classes for tool calls
@Serializable
data class ToolCall(
    val tool: String,
    val args: JsonElement
)

@Serializable
data class ReadFileArgs(val path: String)

@Serializable
data class WriteFileArgs(val path: String, val content: String)

@Serializable
data class ScanProjectArgs(val path: String)

class CodingAgent {
    private val promptExecutor = simpleOllamaAIExecutor()
    private val llmModel = LLModel(
        id = "gpt-oss:20b",
        provider = LLMProvider.Ollama,
        contextLength = 1024,
        maxOutputTokens = 256,
        capabilities = null
    )
    
    private val systemPrompt = """
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

    private val json = Json { ignoreUnknownKeys = true }
    private val conversationHistory = mutableListOf<String>()

    init {
        println("🔧 Initializing Coding Agent...")
        println("✅ Agent ready! How can I help you today?")
    }
    
    private fun createAgent(): AIAgent<String, String> {
        return AIAgent(
            promptExecutor = promptExecutor,
            llmModel = llmModel,
            systemPrompt = systemPrompt
        )
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

    private suspend fun askAgent(userInput: String): String {
        println("🤖 Thinking...")
        
        val fullPrompt = if (conversationHistory.isEmpty()) {
            userInput
        } else {
            conversationHistory.joinToString("\n") + "\nUser: $userInput"
        }
        
        val agent = createAgent()
        
        var currentPrompt = fullPrompt
        var finalResponse = "Agent did not provide a final response."
        val maxIterations = 10

        for (iteration in 0 until maxIterations) {
            try {
                val agentResponse: String = agent.run(currentPrompt)
                println("RAW AGENT RESPONSE (iteration ${iteration + 1}):\n$agentResponse\n---")

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

                        currentPrompt += "\n\n$agentResponse\n\nObservation: $toolOutput\n\nThought:"
                        finalResponse = "Agent executed a tool. Processing..."
                    } catch (e: Exception) {
                        println("❌ Error executing tool or parsing JSON: ${e.message}")
                        currentPrompt += "\n\n$agentResponse\n\nObservation: Error: ${e.message}\n\nThought:"
                        finalResponse = "Error during tool execution."
                    }
                } else if (answer != null) {
                    finalResponse = answer
                    break
                } else {
                    finalResponse = agentResponse
                    break
                }
            } catch (e: Exception) {
                println("❌ Error during agent execution: ${e.message}")
                return "Error: ${e.message}"
            }
        }
        
        if (finalResponse == "Agent did not provide a final response." || 
            finalResponse == "Agent executed a tool. Processing...") {
            return "Agent reached maximum iterations without providing a final answer. Last response: $finalResponse"
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
