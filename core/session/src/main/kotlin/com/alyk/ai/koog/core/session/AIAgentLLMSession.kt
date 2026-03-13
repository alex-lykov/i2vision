package com.alyk.ai.koog.core.session

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import com.alyk.ai.koog.models.wrappers.ModelWrapper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Core session management for Koog LLM interactions
 * Implements the session patterns described in the documentation
 */

/**
 * Represents a message in the conversation history
 */
@Serializable
data class SessionMessage(
    val role: String, // "user", "assistant", "system", "tool"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, JsonElement> = emptyMap()
)

/**
 * Compression strategies for long conversation histories
 */
enum class CompressionStrategy {
    NO_COMPRESSION,
    SUMMARIZE,
    TRUNCATE_EARLY,
    TRUNCATE_MIDDLE
}

/**
 * Session configuration options
 */
data class SessionConfig(
    val maxHistorySize: Int = 50,
    val compressionStrategy: CompressionStrategy = CompressionStrategy.NO_COMPRESSION,
    val compressionThreshold: Int = 40,
    val enablePersistence: Boolean = true,
    val sessionTimeoutMs: Long = 30 * 60 * 1000 // 30 minutes
)

/**
 * Base class for LLM sessions implementing AutoCloseable for resource management
 * Provides thread-safe conversation history and tool management
 */
abstract class AIAgentLLMSession(
    protected val modelWrapper: ModelWrapper,
    protected val initialToolRegistry: ToolRegistry,
    protected val config: SessionConfig = SessionConfig(),
    val sessionId: String = java.util.UUID.randomUUID().toString()
) : AutoCloseable {
    
    protected val conversationHistory = mutableListOf<SessionMessage>()
    protected val dynamicTools = ConcurrentHashMap<String, Tool<*, *>>()
    protected val rwLock = ReentrantReadWriteLock()
    protected var isOpen = true
    protected val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Get current conversation history (thread-safe)
     */
    fun getHistory(): List<SessionMessage> = rwLock.read {
        conversationHistory.toList()
    }
    
    /**
     * Get current tools (static + dynamic merged).
     * Dynamic tools override initial tools by name.
     */
    fun getCurrentTools(): ToolRegistry {
        val dynamicList = dynamicTools.values.toList()
        if (dynamicList.isEmpty()) return initialToolRegistry
        val merged = initialToolRegistry.tools.filter { !dynamicTools.containsKey(it.name) } + dynamicList
        return buildToolRegistry(merged)
    }

    /**
     * Build a ToolRegistry from a list of tools.
     */
    private fun buildToolRegistry(tools: List<Tool<*, *>>): ToolRegistry {
        if (tools.isEmpty()) return ToolRegistry.EMPTY
        return try {
            ToolRegistry { tools(tools) }
        } catch (e: Exception) {
            initialToolRegistry
        }
    }
    
    /**
     * Check if session is still open
     */
    fun isSessionOpen(): Boolean = isOpen
    
    /**
     * Add a message to conversation history
     */
    protected fun addToHistory(message: SessionMessage) = rwLock.write {
        conversationHistory.add(message)
        
        // Apply compression if needed
        if (conversationHistory.size >= config.compressionThreshold && 
            config.compressionStrategy != CompressionStrategy.NO_COMPRESSION) {
            compressHistory(config.compressionStrategy)
        }
    }
    
    /**
     * Compress conversation history based on strategy
     */
    protected fun compressHistory(strategy: CompressionStrategy) = rwLock.write {
        when (strategy) {
            CompressionStrategy.NO_COMPRESSION -> { /* No action */ }
            CompressionStrategy.SUMMARIZE -> summarizeHistory()
            CompressionStrategy.TRUNCATE_EARLY -> truncateEarly()
            CompressionStrategy.TRUNCATE_MIDDLE -> truncateMiddle()
        }
    }
    
    private fun summarizeHistory() {
        if (conversationHistory.size <= config.maxHistorySize) return
        
        // Keep recent messages and summarize older ones
        val recentMessages = conversationHistory.takeLast(config.maxHistorySize / 2)
        val olderMessages = conversationHistory.dropLast(config.maxHistorySize / 2)
        
        val summary = SessionMessage(
            role = "system",
            content = buildString {
                append("[SUMMARY OF ${olderMessages.size} EARLIER MESSAGES]\n")
                olderMessages.groupBy { it.role }.forEach { (role, messages) ->
                    append("$role: ${messages.size} exchanges\n")
                }
                append("Key topics: ${extractKeyTopics(olderMessages)}\n")
            }
        )
        
        conversationHistory.clear()
        conversationHistory.add(summary)
        conversationHistory.addAll(recentMessages)
    }
    
    private fun truncateEarly() {
        if (conversationHistory.size <= config.maxHistorySize) return
        
        val keepCount = config.maxHistorySize
        conversationHistory.subList(0, conversationHistory.size - keepCount).clear()
    }
    
    private fun truncateMiddle() {
        if (conversationHistory.size <= config.maxHistorySize) return
        
        val keepCount = config.maxHistorySize
        val keepStart = keepCount / 3
        val keepEnd = keepCount - keepStart
        
        val middleMessages = conversationHistory.subList(keepStart, conversationHistory.size - keepEnd)
        val summary = SessionMessage(
            role = "system",
            content = "[${middleMessages.size} MESSAGES COMPRESSED]"
        )
        
        conversationHistory.subList(keepStart, conversationHistory.size - keepEnd).clear()
        conversationHistory.add(keepStart, summary)
    }
    
    private fun extractKeyTopics(messages: List<SessionMessage>): String {
        // Simple topic extraction - can be enhanced with NLP
        val topics = mutableSetOf<String>()
        messages.forEach { msg ->
            when {
                msg.content.contains("file", ignoreCase = true) -> topics.add("file operations")
                msg.content.contains("code", ignoreCase = true) -> topics.add("code analysis")
                msg.content.contains("error", ignoreCase = true) -> topics.add("error handling")
                msg.content.contains("test", ignoreCase = true) -> topics.add("testing")
                msg.content.contains("build", ignoreCase = true) -> topics.add("build process")
            }
        }
        return topics.take(3).joinToString(", ")
    }
    
    override fun close() {
        rwLock.write {
            isOpen = false
            dynamicTools.clear()
            conversationHistory.clear()
        }
    }
}

/**
 * Write Session - allows modification of prompt, tools, and making LLM requests
 * Provides full access for session management
 */
class AIAgentLLMWriteSession(
    modelWrapper: ModelWrapper,
    initialToolRegistry: ToolRegistry,
    config: SessionConfig = SessionConfig(),
    sessionId: String = java.util.UUID.randomUUID().toString()
) : AIAgentLLMSession(modelWrapper, initialToolRegistry, config, sessionId) {
    
    /**
     * Add a dynamic tool to the session
     */
    fun appendTool(tool: Tool<*, *>) {
        check(isOpen) { "Session is closed" }
        dynamicTools[tool.name] = tool
    }
    
    /**
     * Remove a tool by name or class
     */
    fun removeTool(toolName: String) {
        check(isOpen) { "Session is closed" }
        dynamicTools.remove(toolName)
    }
    
    fun removeTool(toolClass: Class<out Tool<*, *>>) {
        check(isOpen) { "Session is closed" }
        dynamicTools.values.removeIf { toolClass.isInstance(it) }
    }
    
    /**
     * Add messages to conversation history
     */
    fun appendPrompt(block: PromptBuilder.() -> Unit) {
        check(isOpen) { "Session is closed" }
        val builder = PromptBuilder()
        builder.block()
        builder.messages.forEach { addToHistory(it) }
    }
    
    /**
     * Compress conversation history manually (e.g. when history exceeds threshold).
     */
    fun triggerHistoryCompression(strategy: CompressionStrategy = config.compressionStrategy) {
        check(isOpen) { "Session is closed" }
        if (strategy != CompressionStrategy.NO_COMPRESSION) {
            super.compressHistory(strategy)
        }
    }

    /**
     * Completely rewrite conversation history
     */
    fun rewritePrompt(block: PromptBuilder.() -> Unit) {
        check(isOpen) { "Session is closed" }
        rwLock.write {
            conversationHistory.clear()
            val builder = PromptBuilder()
            builder.block()
            conversationHistory.addAll(builder.messages)
        }
    }
    
    /**
     * Make an LLM request with current context.
     * Response is automatically added to conversation history.
     */
    suspend fun requestLLM(customPrompt: String? = null): String {
        check(isOpen) { "Session is closed" }
        
        val prompt = customPrompt ?: buildPromptFromHistory()
        val response = modelWrapper.generate(prompt)
        
        addToHistory(SessionMessage(role = "assistant", content = response))
        return response
    }
    
    /**
     * Execute a tool and add result to history
     */
    suspend fun callTool(toolName: String, parameters: Map<String, Any>): String {
        check(isOpen) { "Session is closed" }
        
        // Look for tool in both static and dynamic tools
        val tool = initialToolRegistry.tools.find { it.name == toolName }
            ?: dynamicTools[toolName]
            ?: throw IllegalArgumentException("Tool not found: $toolName")
        
        try {
            val result = (tool as Tool<Map<String, Any>, String>).execute(parameters)
            
            addToHistory(SessionMessage(
                role = "tool",
                content = "Tool '$toolName' executed: ${result?.take(200) ?: "null"}${if ((result?.length ?: 0) > 200) "..." else ""}",
                metadata = mapOf(
                    "tool_name" to json.encodeToJsonElement(toolName),
                    "parameters" to json.encodeToJsonElement(parameters),
                    "result" to json.encodeToJsonElement(result ?: "null")
                )
            ))
            
            return result
        } catch (e: Exception) {
            addToHistory(SessionMessage(
                role = "system",
                content = "Tool execution failed: ${e.message}",
                metadata = mapOf(
                    "tool_name" to json.encodeToJsonElement(toolName),
                    "error" to json.encodeToJsonElement(e.message ?: "Unknown error")
                )
            ))
            throw e
        }
    }
    
    private fun buildPromptFromHistory(): String {
        return buildString {
            conversationHistory.forEach { msg ->
                when (msg.role) {
                    "system" -> append("System: ${msg.content}\n")
                    "user" -> append("User: ${msg.content}\n")
                    "assistant" -> append("Assistant: ${msg.content}\n")
                    "tool" -> append("Tool Result: ${msg.content}\n")
                }
            }
            append("Assistant: ")
        }
    }
}

/**
 * Read Session - read-only access to prompt and tools
 * For inspection without modification
 */
class AIAgentLLMReadSession(
    private val writeSession: AIAgentLLMWriteSession
) : AutoCloseable {
    
    fun getHistory(): List<SessionMessage> = writeSession.getHistory()
    fun getCurrentTools(): ToolRegistry = writeSession.getCurrentTools()
    fun getSessionId(): String = writeSession.sessionId
    fun isSessionOpen(): Boolean = writeSession.isSessionOpen()
    
    override fun close() {
        // Read sessions don't own resources, just delegate
    }
}

/**
 * Builder for creating prompts with message types
 */
class PromptBuilder {
    val messages = mutableListOf<SessionMessage>()
    
    fun system(content: String) {
        messages.add(SessionMessage(role = "system", content = content))
    }
    
    fun user(content: String) {
        messages.add(SessionMessage(role = "user", content = content))
    }
    
    fun assistant(content: String) {
        messages.add(SessionMessage(role = "assistant", content = content))
    }
    
    fun toolResult(content: String) {
        messages.add(SessionMessage(role = "tool", content = content))
    }
}

/**
 * Extension functions for parallel tool execution
 */
suspend fun List<suspend () -> String>.toParallelToolCallsRaw(): List<String> {
    return coroutineScope {
        map { async { it() } }.awaitAll()
    }
}
