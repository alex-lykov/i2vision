/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.config

import com.i2vision.agent.VslfcLayer

/**
 * Built-in default configurations for each VSLFC layer.
 * 
 * These defaults are used when:
 * - No configuration file is provided
 * - A configuration file is missing required sections
 * - Quick prototyping without YAML files
 * - Testing and development
 * 
 * Each layer has tailored settings:
 * - **CODE**: Focus on implementation, file operations, build verification
 * - **FLOW**: Focus on API calls, sequences, state transitions
 * - **LOGIC**: Focus on business rules, invariants, algorithms
 * - **STRUCTURE**: Focus on components, modules, dependencies
 * - **VISION**: Focus on requirements, goals, constraints
 * 
 * Example usage:
 * ```kotlin
 * // Get default for CODE layer
 * val codeConfig = DefaultConfigs.forLayer(VslfcLayer.CODE)
 * 
 * // Get specific default
 * val codeConfig = DefaultConfigs.CODE
 * 
 * // Use with adapter
 * val adapter = ConfigurationAdapter()
 * val agentConfig = adapter.toAgentConfig(DefaultConfigs.CODE)
 * ```
 */
object DefaultConfigs {
    
    // Template variable placeholders - using const to avoid repetition
    private const val WORKSPACE_ROOT = "{{$" + "workspaceRoot}}"
    private const val CURRENT_FILE = "{{$" + "currentFile}}"
    private const val PROJECT_ROOT = "{{$" + "projectRoot}}"
    
    /**
     * Default configuration for CODE layer agents.
     * 
     * Optimized for:
     * - Implementation tasks
     * - File modifications
     * - Build verification
     * - Code refactoring
     */
    val CODE = AgentPromptConfiguration(
        key = "default-code",
        agentType = "CODE",
        version = "1.0.0",
        isActive = true,
        systemPromptTemplate = """
You are an expert software engineer specializing in implementation tasks.
Your role is to write clean, efficient, and maintainable code.

## Context
- Layer: CODE
- Workspace: {{${"$"}workspaceRoot}}
- Current File: {{${"$"}currentFile}}

## Guidelines
1. Follow language best practices and conventions
2. Write self-documenting code with clear names
3. Add comments only for complex logic
4. Keep functions small and focused
5. Handle errors gracefully
6. Write testable code

## Process
1. Understand the task requirements
2. Analyze the existing code structure
3. Plan your implementation
4. Write the code
5. Verify it compiles and works
6. Explain your changes
""".trimIndent(),
        templateVariables = mapOf(
            "workspaceRoot" to WORKSPACE_ROOT,
            "currentFile" to CURRENT_FILE,
            "projectRoot" to PROJECT_ROOT
        ),
        ruleSetKeys = listOf("code-style", "error-handling"),
        model = ModelConfig(
            provider = "Ollama",
            id = "llama3.2:3b",
            contextLength = 8192,
            temperature = 0.3,
            topP = 0.9,
            topK = 40,
            maxTokens = 2048
        ),
        llm = LlmConfig(
            retries = 3,
            timeoutSeconds = 60,
            streaming = true
        ),
        formattingRules = FormattingRulesConfig(
            indentSize = 4,
            useTabs = false,
            maxLineLength = 120,
            trimTrailingWhitespace = true,
            insertFinalNewline = true
        ),
        iterationSettings = IterationConfig(
            maxIterations = 10,
            maxConsecutiveToolCalls = 12,
            enableKickstart = true,
            kickstartMinInvalidOutputs = 2,
            reflectionEnabled = true,
            selfCorrectionEnabled = true
        ),
        toolSelection = ToolSelectionConfig(
            enabledTools = listOf(
                "read_file",
                "write_file",
                "list_directory",
                "search_files",
                "run_command",
                "i2vision_search_symbols"
            ),
            disabledTools = emptyList(),
            toolTimeoutSeconds = 30,
            requireConfirmationFor = listOf("write_file", "run_command"),
            readOnlyMode = false
        ),
        safety = SafetyConfig(
            allowFileWrites = true,
            allowedDirectories = emptyList(),
            forbiddenDirectories = listOf(".git", "node_modules", "build"),
            enableBuildVerification = true,
            maxFileSize = 1024 * 1024,
            requireBackupBeforeWrite = true
        ),
        parsing = ParsingConfig(
            strictJsonParsing = true,
            allowMarkdownCodeBlocks = true,
            fallbackToPlainText = true,
            maxParseAttempts = 3
        ),
        discovery = DiscoveryConfig(
            enableClusterContext = true,
            cachePath = null,
            autoRefresh = false,
            refreshIntervalMinutes = 60,
            maxCacheAgeHours = 24
        ),
        execution = ExecutionConfig(
            buildCommand = "./gradlew compileKotlin",
            fileOperationMode = "DIRECT",
            workingDirectory = null,
            environmentVariables = emptyMap(),
            shellPath = null
        ),
        formatting = FormattingConfig(
            includeReasoningTrace = true,
            includeToolCallDetails = true,
            compactMode = false,
            syntaxHighlighting = true
        ),
        streaming = StreamingConfig(
            enabled = true,
            emitReasoning = true,
            emitToolCalls = true,
            emitProgress = true,
            chunkSize = 50
        ),
        mcp = McpConfig(
            enabled = false,
            servers = emptyList(),
            injectClusterContext = true,
            timeoutSeconds = 30
        )
    )
    
    /**
     * Default configuration for FLOW layer agents.
     * 
     * Optimized for:
     * - API design and implementation
     * - Sequence diagrams
     * - State machine design
     * - Interaction flows
     */
    val FLOW = AgentPromptConfiguration(
        key = "default-flow",
        agentType = "FLOW",
        version = "1.0.0",
        isActive = true,
        systemPromptTemplate = """
You are an expert system architect specializing in flow and interaction design.
Your role is to design clear, efficient, and maintainable interaction flows.

## Context
- Layer: FLOW
- Workspace: {{${"$"}workspaceRoot}}

## Guidelines
1. Design clear API contracts
2. Define state transitions explicitly
3. Document interaction sequences
4. Handle edge cases and errors
5. Ensure flows are testable
6. Maintain consistency across flows

## Process
1. Understand the interaction requirements
2. Identify actors and components
3. Design the flow
4. Document the sequence
5. Validate against constraints
6. Explain the design
""".trimIndent(),
        templateVariables = mapOf(
            "workspaceRoot" to WORKSPACE_ROOT,
            "currentFile" to CURRENT_FILE,
            "projectRoot" to PROJECT_ROOT
        ),
        ruleSetKeys = listOf("api-design", "state-machines"),
        model = ModelConfig(
            provider = "Ollama",
            id = "llama3.2:3b",
            contextLength = 8192,
            temperature = 0.5,
            topP = 0.9,
            topK = 40,
            maxTokens = 2048
        ),
        llm = LlmConfig(
            retries = 3,
            timeoutSeconds = 60,
            streaming = true
        ),
        formattingRules = FormattingRulesConfig(
            indentSize = 2,
            useTabs = false,
            maxLineLength = 100,
            trimTrailingWhitespace = true,
            insertFinalNewline = true
        ),
        iterationSettings = IterationConfig(
            maxIterations = 8,
            maxConsecutiveToolCalls = 10,
            enableKickstart = true,
            kickstartMinInvalidOutputs = 2,
            reflectionEnabled = true,
            selfCorrectionEnabled = true
        ),
        toolSelection = ToolSelectionConfig(
            enabledTools = listOf(
                "read_file",
                "write_file",
                "list_directory",
                "i2vision_discover",
                "i2vision_get_context"
            ),
            disabledTools = emptyList(),
            toolTimeoutSeconds = 30,
            requireConfirmationFor = listOf("write_file"),
            readOnlyMode = false
        ),
        safety = SafetyConfig(
            allowFileWrites = true,
            allowedDirectories = emptyList(),
            forbiddenDirectories = listOf(".git", "node_modules"),
            enableBuildVerification = false,
            maxFileSize = 1024 * 1024,
            requireBackupBeforeWrite = true
        ),
        parsing = ParsingConfig(
            strictJsonParsing = true,
            allowMarkdownCodeBlocks = true,
            fallbackToPlainText = true,
            maxParseAttempts = 3
        ),
        discovery = DiscoveryConfig(
            enableClusterContext = true,
            cachePath = null,
            autoRefresh = false,
            refreshIntervalMinutes = 60,
            maxCacheAgeHours = 24
        ),
        execution = ExecutionConfig(
            buildCommand = "./gradlew compileKotlin",
            fileOperationMode = "DIRECT",
            workingDirectory = null,
            environmentVariables = emptyMap(),
            shellPath = null
        ),
        formatting = FormattingConfig(
            includeReasoningTrace = true,
            includeToolCallDetails = true,
            compactMode = false,
            syntaxHighlighting = true
        ),
        streaming = StreamingConfig(
            enabled = true,
            emitReasoning = true,
            emitToolCalls = true,
            emitProgress = true,
            chunkSize = 50
        ),
        mcp = McpConfig(
            enabled = false,
            servers = emptyList(),
            injectClusterContext = true,
            timeoutSeconds = 30
        )
    )
    
    /**
     * Default configuration for LOGIC layer agents.
     * 
     * Optimized for:
     * - Business rule implementation
     * - Algorithm design
     * - Data transformations
     * - Invariant validation
     */
    val LOGIC = AgentPromptConfiguration(
        key = "default-logic",
        agentType = "LOGIC",
        version = "1.0.0",
        isActive = true,
        systemPromptTemplate = """
You are an expert software architect specializing in business logic and algorithms.
Your role is to implement correct, efficient, and maintainable business logic.

## Context
- Layer: LOGIC
- Workspace: {{${"$"}workspaceRoot}}

## Guidelines
1. Implement business rules accurately
2. Ensure algorithmic correctness
3. Optimize for performance where needed
4. Handle edge cases explicitly
5. Maintain invariants
6. Write testable logic

## Process
1. Understand the business requirements
2. Identify invariants and constraints
3. Design the algorithm
4. Implement the logic
5. Verify correctness
6. Explain the implementation
""".trimIndent(),
        templateVariables = mapOf(
            "workspaceRoot" to WORKSPACE_ROOT,
            "currentFile" to CURRENT_FILE,
            "projectRoot" to PROJECT_ROOT
        ),
        ruleSetKeys = listOf("business-rules", "algorithms"),
        model = ModelConfig(
            provider = "Ollama",
            id = "llama3.2:3b",
            contextLength = 8192,
            temperature = 0.2,
            topP = 0.9,
            topK = 40,
            maxTokens = 2048
        ),
        llm = LlmConfig(
            retries = 3,
            timeoutSeconds = 60,
            streaming = true
        ),
        formattingRules = FormattingRulesConfig(
            indentSize = 4,
            useTabs = false,
            maxLineLength = 120,
            trimTrailingWhitespace = true,
            insertFinalNewline = true
        ),
        iterationSettings = IterationConfig(
            maxIterations = 10,
            maxConsecutiveToolCalls = 12,
            enableKickstart = true,
            kickstartMinInvalidOutputs = 2,
            reflectionEnabled = true,
            selfCorrectionEnabled = true
        ),
        toolSelection = ToolSelectionConfig(
            enabledTools = listOf(
                "read_file",
                "write_file",
                "list_directory",
                "search_files",
                "i2vision_search_symbols"
            ),
            disabledTools = emptyList(),
            toolTimeoutSeconds = 30,
            requireConfirmationFor = listOf("write_file"),
            readOnlyMode = false
        ),
        safety = SafetyConfig(
            allowFileWrites = true,
            allowedDirectories = emptyList(),
            forbiddenDirectories = listOf(".git", "node_modules"),
            enableBuildVerification = false,
            maxFileSize = 1024 * 1024,
            requireBackupBeforeWrite = true
        ),
        parsing = ParsingConfig(
            strictJsonParsing = true,
            allowMarkdownCodeBlocks = true,
            fallbackToPlainText = true,
            maxParseAttempts = 3
        ),
        discovery = DiscoveryConfig(
            enableClusterContext = true,
            cachePath = null,
            autoRefresh = false,
            refreshIntervalMinutes = 60,
            maxCacheAgeHours = 24
        ),
        execution = ExecutionConfig(
            buildCommand = "./gradlew compileKotlin",
            fileOperationMode = "DIRECT",
            workingDirectory = null,
            environmentVariables = emptyMap(),
            shellPath = null
        ),
        formatting = FormattingConfig(
            includeReasoningTrace = true,
            includeToolCallDetails = true,
            compactMode = false,
            syntaxHighlighting = true
        ),
        streaming = StreamingConfig(
            enabled = true,
            emitReasoning = true,
            emitToolCalls = true,
            emitProgress = true,
            chunkSize = 50
        ),
        mcp = McpConfig(
            enabled = false,
            servers = emptyList(),
            injectClusterContext = true,
            timeoutSeconds = 30
        )
    )
    
    /**
     * Default configuration for STRUCTURE layer agents.
     * 
     * Optimized for:
     * - Component design
     * - Module architecture
     * - Dependency management
     * - System organization
     */
    val STRUCTURE = AgentPromptConfiguration(
        key = "default-structure",
        agentType = "STRUCTURE",
        version = "1.0.0",
        isActive = true,
        systemPromptTemplate = """
You are an expert system architect specializing in structural design.
Your role is to design clear, modular, and maintainable system architectures.

## Context
- Layer: STRUCTURE
- Workspace: {{${"$"}workspaceRoot}}

## Guidelines
1. Design modular components
2. Define clear interfaces
3. Minimize coupling
4. Maximize cohesion
5. Document dependencies
6. Ensure scalability

## Process
1. Understand the system requirements
2. Identify components and modules
3. Define interfaces and contracts
4. Map dependencies
5. Validate the architecture
6. Explain the design
""".trimIndent(),
        templateVariables = mapOf(
            "workspaceRoot" to WORKSPACE_ROOT,
            "currentFile" to CURRENT_FILE,
            "projectRoot" to PROJECT_ROOT
        ),
        ruleSetKeys = listOf("architecture", "modularity"),
        model = ModelConfig(
            provider = "Ollama",
            id = "llama3.2:3b",
            contextLength = 8192,
            temperature = 0.4,
            topP = 0.9,
            topK = 40,
            maxTokens = 2048
        ),
        llm = LlmConfig(
            retries = 3,
            timeoutSeconds = 60,
            streaming = true
        ),
        formattingRules = FormattingRulesConfig(
            indentSize = 4,
            useTabs = false,
            maxLineLength = 120,
            trimTrailingWhitespace = true,
            insertFinalNewline = true
        ),
        iterationSettings = IterationConfig(
            maxIterations = 8,
            maxConsecutiveToolCalls = 10,
            enableKickstart = true,
            kickstartMinInvalidOutputs = 2,
            reflectionEnabled = true,
            selfCorrectionEnabled = true
        ),
        toolSelection = ToolSelectionConfig(
            enabledTools = listOf(
                "read_file",
                "write_file",
                "list_directory",
                "i2vision_discover",
                "i2vision_get_context"
            ),
            disabledTools = emptyList(),
            toolTimeoutSeconds = 30,
            requireConfirmationFor = listOf("write_file"),
            readOnlyMode = false
        ),
        safety = SafetyConfig(
            allowFileWrites = true,
            allowedDirectories = emptyList(),
            forbiddenDirectories = listOf(".git", "node_modules"),
            enableBuildVerification = false,
            maxFileSize = 1024 * 1024,
            requireBackupBeforeWrite = true
        ),
        parsing = ParsingConfig(
            strictJsonParsing = true,
            allowMarkdownCodeBlocks = true,
            fallbackToPlainText = true,
            maxParseAttempts = 3
        ),
        discovery = DiscoveryConfig(
            enableClusterContext = true,
            cachePath = null,
            autoRefresh = false,
            refreshIntervalMinutes = 60,
            maxCacheAgeHours = 24
        ),
        execution = ExecutionConfig(
            buildCommand = "./gradlew compileKotlin",
            fileOperationMode = "DIRECT",
            workingDirectory = null,
            environmentVariables = emptyMap(),
            shellPath = null
        ),
        formatting = FormattingConfig(
            includeReasoningTrace = true,
            includeToolCallDetails = true,
            compactMode = false,
            syntaxHighlighting = true
        ),
        streaming = StreamingConfig(
            enabled = true,
            emitReasoning = true,
            emitToolCalls = true,
            emitProgress = true,
            chunkSize = 50
        ),
        mcp = McpConfig(
            enabled = false,
            servers = emptyList(),
            injectClusterContext = true,
            timeoutSeconds = 30
        )
    )
    
    /**
     * Default configuration for VISION layer agents.
     * 
     * Optimized for:
     * - Requirements analysis
     * - Goal definition
     * - Constraint identification
     * - Strategic planning
     */
    val VISION = AgentPromptConfiguration(
        key = "default-vision",
        agentType = "VISION",
        version = "1.0.0",
        isActive = true,
        systemPromptTemplate = """
You are an expert strategic planner specializing in requirements and vision.
Your role is to define clear goals, identify constraints, and create strategic plans.

## Context
- Layer: VISION
- Workspace: {{${"$"}workspaceRoot}}

## Guidelines
1. Understand the big picture
2. Identify key stakeholders
3. Define clear goals
4. Identify constraints and risks
5. Create actionable plans
6. Ensure alignment with business objectives

## Process
1. Gather requirements
2. Analyze stakeholders
3. Define goals and objectives
4. Identify constraints
5. Create strategic plan
6. Validate and refine
""".trimIndent(),
        templateVariables = mapOf(
            "workspaceRoot" to WORKSPACE_ROOT,
            "currentFile" to CURRENT_FILE,
            "projectRoot" to PROJECT_ROOT
        ),
        ruleSetKeys = listOf("requirements", "strategic-planning"),
        model = ModelConfig(
            provider = "Ollama",
            id = "llama3.2:3b",
            contextLength = 8192,
            temperature = 0.6,
            topP = 0.9,
            topK = 40,
            maxTokens = 2048
        ),
        llm = LlmConfig(
            retries = 3,
            timeoutSeconds = 60,
            streaming = true
        ),
        formattingRules = FormattingRulesConfig(
            indentSize = 4,
            useTabs = false,
            maxLineLength = 120,
            trimTrailingWhitespace = true,
            insertFinalNewline = true
        ),
        iterationSettings = IterationConfig(
            maxIterations = 8,
            maxConsecutiveToolCalls = 10,
            enableKickstart = true,
            kickstartMinInvalidOutputs = 2,
            reflectionEnabled = true,
            selfCorrectionEnabled = true
        ),
        toolSelection = ToolSelectionConfig(
            enabledTools = listOf(
                "read_file",
                "write_file",
                "list_directory",
                "i2vision_discover",
                "i2vision_get_context"
            ),
            disabledTools = emptyList(),
            toolTimeoutSeconds = 30,
            requireConfirmationFor = listOf("write_file"),
            readOnlyMode = false
        ),
        safety = SafetyConfig(
            allowFileWrites = true,
            allowedDirectories = emptyList(),
            forbiddenDirectories = listOf(".git", "node_modules"),
            enableBuildVerification = false,
            maxFileSize = 1024 * 1024,
            requireBackupBeforeWrite = true
        ),
        parsing = ParsingConfig(
            strictJsonParsing = true,
            allowMarkdownCodeBlocks = true,
            fallbackToPlainText = true,
            maxParseAttempts = 3
        ),
        discovery = DiscoveryConfig(
            enableClusterContext = true,
            cachePath = null,
            autoRefresh = false,
            refreshIntervalMinutes = 60,
            maxCacheAgeHours = 24
        ),
        execution = ExecutionConfig(
            buildCommand = "./gradlew compileKotlin",
            fileOperationMode = "DIRECT",
            workingDirectory = null,
            environmentVariables = emptyMap(),
            shellPath = null
        ),
        formatting = FormattingConfig(
            includeReasoningTrace = true,
            includeToolCallDetails = true,
            compactMode = false,
            syntaxHighlighting = true
        ),
        streaming = StreamingConfig(
            enabled = true,
            emitReasoning = true,
            emitToolCalls = true,
            emitProgress = true,
            chunkSize = 50
        ),
        mcp = McpConfig(
            enabled = false,
            servers = emptyList(),
            injectClusterContext = true,
            timeoutSeconds = 30
        )
    )
    
    /**
     * Get the default configuration for a specific VSLFC layer.
     */
    fun forLayer(layer: VslfcLayer): AgentPromptConfiguration {
        return when (layer) {
            VslfcLayer.CODE -> CODE
            VslfcLayer.FLOW -> FLOW
            VslfcLayer.LOGIC -> LOGIC
            VslfcLayer.STRUCTURE -> STRUCTURE
            VslfcLayer.VISION -> VISION
        }
    }
}
