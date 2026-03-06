# Hybrid Coding Agent - Kotlin Module Specification

## **Module 1: Core Agent Architecture**

### **1.1 Agent Orchestrator**
```
MODULE: com.codingagent.core.orchestrator

RESPONSIBILITY:
Central coordination of all agent components, managing request routing, model selection, and session lifecycle.

KEY LOGIC:
- Initialize and maintain references to local/cloud model wrappers
- Route incoming tasks based on decision engine recommendations
- Maintain session state across model switches using coroutines
- Implement fallback strategies when primary model fails
- Track agent health metrics and expose status endpoint
```

### **1.2 Model Wrapper Layer**
```
MODULE: com.codingagent.models.wrappers

RESPONSIBILITY:
Abstract model-specific implementations into unified interface with consistent request/response handling.

KEY LOGIC:
- LocalModelWrapper: HTTP client for ollama/llama.cpp REST APIs
- CloudModelWrapper: HTTP client for OpenAI/Anthropic with API key management
- FallbackModelWrapper: Cache-based responses for offline scenarios
- Token counting using model-specific tokenizers
- Response streaming via Kotlin Flow for real-time output
```

### **1.3 Session & State Management**
```
MODULE: com.codingagent.core.session

RESPONSIBILITY:
Preserve conversation context, manage session lifecycle, and provide persistence for agent state.

KEY LOGIC:
- SessionStore: ConcurrentHashMap with coroutine-safe access patterns
- ContextCache: Caffeine cache with time-based expiration for frequent contexts
- HistoryManager: Exposed database tables for conversation persistence
- StateSerializer: Kotlinx.serialization for converting objects to/from JSON
- Session cleanup scheduler for inactive sessions
```

---

## **Module 2: Switching Intelligence**

### **2.1 Context Analyzer**
```
MODULE: com.codingagent.switching.analyzer

RESPONSIBILITY:
Analyze task and codebase to determine context requirements and complexity metrics for switching decisions.

KEY LOGIC:
- TokenEstimator: Count tokens in code, task description, and conversation history
- ComplexityAnalyzer: Calculate inheritance depth, cyclomatic complexity, and dependency density
- DependencyDetector: Parse imports to identify circular dependencies and external libraries
- ContextScorer: Combine metrics into normalized score (0-1) for decision engine
- Threshold comparison against local model's context window limits
```

### **2.2 Performance Monitor**
```
MODULE: com.codingagent.switching.monitor

RESPONSIBILITY:
Real-time monitoring of model performance to detect struggle signals indicating context insufficiency.

KEY LOGIC:
- PerformanceTracker: Maintain sliding windows of response times, token usage
- SignalDetector: Identify warning patterns (slow responses, high token ratio, truncation)
- MetricsCollector: Aggregate performance data with timestamps
- AlertManager: Trigger alerts when thresholds exceeded (2x response time, 1.5x tokens, >20% errors)
- Warning signal aggregation for decision engine input
```

### **2.3 Decision Engine**
```
MODULE: com.codingagent.switching.decision

RESPONSIBILITY:
Aggregate analyzer inputs, apply weighted rules, and determine optimal model for current task.

KEY LOGIC:
- DecisionEngine: Combine context analysis (40%), complexity (25%), performance (20%), preferences (15%)
- RuleEngine: Evaluate sealed class rules against current metrics
- ConfidenceCalculator: Weight confidence based on signal strength and historical accuracy
- SwitchExecutor: Coordinate model transition with context preservation
- User prompt generation when confidence below threshold
```

---

## **Module 3: Hierarchical Context Management**

### **3.1 Hierarchy Builder**
```
MODULE: com.codingagent.context.hierarchy

RESPONSIBILITY:
Parse codebase into three hierarchical levels with extracted relationships between components.

KEY LOGIC:
- HierarchyBuilder: Orchestrate parsing across all source files
- ParserEngine: Kotlin compiler PSI integration for AST traversal
- RelationshipExtractor: Identify imports, extends, implements, calls, uses relationships
- SummaryGenerator: Create concise summaries for architecture and module levels
- Level 1 (Architecture): Module boundaries, data flows, API contracts, external dependencies
- Level 2 (Module): File structure, class hierarchies, public interfaces, key algorithms
- Level 3 (Implementation): Actual source code, function bodies, local variables
- Link types stored as references with source/target/line number
```

### **3.2 Context Provider**
```
MODULE: com.codingagent.context.provider

RESPONSIBILITY:
Serve appropriate hierarchy levels based on request, with optimization for model type.

KEY LOGIC:
- ContextProvider: Main entry point for context requests with level selection
- LevelSelector: Determine required depth based on task type and complexity
- ContextOptimizer: Apply optimizations (comment removal, function summarization, whitespace stripping)
- LinkMaintainer: Preserve cross-references when pruning context
- Token counting after optimization to ensure context fit
- Caching of frequently accessed contexts
```

### **3.3 Hierarchy Navigator**
```
MODULE: com.codingagent.context.navigation

RESPONSIBILITY:
Enable traversal between hierarchy levels and resolution of cross-references.

KEY LOGIC:
- HierarchyNavigator: Coordinate navigation operations
- ReferenceResolver: Follow links to target contexts using PSI references
- NavigationHistory: Track navigation path with timestamps
- PathFinder: Calculate shortest path between two code elements
- drillDown(): Navigate from reference to detailed implementation
- rollUp(): Navigate from implementation to containing module/architecture
- followLink(): Resolve cross-reference to target context
```

---

## **Module 4: IntelliJ IDE Integration**

### **4.1 IntelliJ Plugin Core**
```
MODULE: com.codingagent.intellij

RESPONSIBILITY:
Register plugin with IntelliJ platform and provide extension points for IDE integration.

KEY LOGIC:
- CodingAgentPlugin: Extends BasePlugin for lifecycle management
- AgentToolWindow: Register tool window factory with icon and content panel
- AgentActionGroup: Define menu items and toolbar actions
- AgentSettings: Persistent state using IntelliJ's settings framework
- plugin.xml: Declare dependencies (platform, Kotlin) and extensions
```

### **4.2 IntelliJ Service Layer**
```
MODULE: com.codingagent.intellij.services

RESPONSIBILITY:
Provide project-level services that interact with IntelliJ APIs and communicate with agent backend.

KEY LOGIC:
- AgentProjectService: Per-project service with Ktor HTTP client for backend communication
- VirtualFileAnalyzer: Extract file content, path, and metadata
- EditorIntegrationService: Get current editor, selection, cursor position
- PSIHelper: Navigate PSI tree to extract class/function/property structures
- WriteCommandAction wrapper for safe document modifications
- FileDocumentManager for file change application
```

### **4.3 Deep Integration Features**
```
MODULE: com.codingagent.intellij.features

RESPONSIBILITY:
Implement IDE-specific capabilities at four integration depth levels.

KEY LOGIC:
- FileIntegration (Level 1): Open files, insert text at cursor, highlight ranges
- ProjectIntegration (Level 2): Find usages, run Gradle tasks, navigate to declaration
- DebuggerIntegration (Level 3): Attach to process, evaluate expressions, manage breakpoints
- CognitiveIntegration (Level 4): Predict developer intent, suggest next actions
- XDebuggerManager integration for debug session control
- GradleIntegration for build task execution
- ReferencesSearch for usage finding
```

### **4.4 IntelliJ UI Components**
```
MODULE: com.codingagent.intellij.ui

RESPONSIBILITY:
Provide user interface elements within IntelliJ for agent interaction.

KEY LOGIC:
- AgentToolWindow: Main panel with SimpleToolWindowPanel
- ChatPanel: Message list with markdown rendering and input field
- CodeDiffViewer: Show changes with side-by-side comparison
- SettingsPanel: Configuration UI with JBControls
- Model status indicator with color coding (green/red)
- Progress indicators for long-running operations
- Notification balloons for alerts
```

---

## **Module 5: Ktor Backend Services**

### **5.1 Ktor Application Structure**
```
MODULE: com.codingagent.server

RESPONSIBILITY:
Configure and launch Ktor HTTP server with all required plugins.

KEY LOGIC:
- Application: Main entry point with embedded server
- module(): Ktor application module installing all plugins
- ContentNegotiation with JSON serializer
- WebSockets for streaming responses
- CORS configuration for IDE integration
- Status pages for error handling
- Compression for response optimization
- HOCON configuration file for ports and timeouts
```

### **5.2 Ktor Routes**
```
MODULE: com.codingagent.server.routes

RESPONSIBILITY:
Define REST and WebSocket endpoints for agent functionality.

KEY LOGIC:
- GET /api/v1/agent/status: Return current agent health and model
- POST /api/v1/agent/switch: Change active model with reason
- WebSocket /api/v1/task/stream: Stream task execution with progress
- GET /api/v1/context/hierarchy/{level}: Retrieve specific hierarchy level
- POST /api/v1/context/analyze: Analyze files and return metrics
- Route validation with Kotlinx.serialization
- Request/response logging
```

### **5.3 WebSocket Streaming**
```
MODULE: com.codingagent.server.streaming

RESPONSIBILITY:
Handle real-time bidirectional communication for task execution.

KEY LOGIC:
- WebSocketSessionManager: Track active WebSocket connections
- ResponseStreamer: Convert Flow responses to WebSocket frames
- StreamChunk sealed class hierarchy (Metadata, Progress, Content, Complete, Error)
- Progress tracking with percentage and message
- Flow collection with cancellation support
- Frame parsing and sending with proper error handling
- Heartbeat mechanism for connection keepalive
```

---

## **Module 6: Kotlin-Specific Features**

### **6.1 Kotlin PSI Analysis**
```
MODULE: com.codingagent.kotlin.analysis

RESPONSIBILITY:
Parse and analyze Kotlin source code using IntelliJ's PSI (Program Structure Interface).

KEY LOGIC:
- KotlinParser: Initialize CoreEnvironment for project analysis
- PsiTraverser: Recursively visit PSI elements
- KtClassHierarchyAnalyzer: Extract inheritance trees and super types
- KtFunctionAnalyzer: Parse function signatures, parameters, return types
- Package and import extraction from KtFile
- Modifier detection (public, private, suspend, etc.)
- Class kind detection (class, interface, object, data class)
```

### **6.2 Coroutine-Based Processing**
```
MODULE: com.codingagent.core.coroutines

RESPONSIBILITY:
Provide coroutine infrastructure for concurrent and asynchronous operations.

KEY LOGIC:
- DispatcherProvider: Custom dispatchers for IO, CPU, and database operations
- FlowOperators: Custom operators for stream processing (buffer, merge, throttle)
- CoroutineScopes: Application-wide and request-scoped coroutine contexts
- Parallel file analysis with async/awaitAll
- Flow-based streaming with backpressure handling (buffer)
- Structured concurrency with parent-child job relationships
- Timeout handling for long-running operations
```

### **6.3 Kotlinx.Serialization**
```
MODULE: com.codingagent.serialization

RESPONSIBILITY:
Handle JSON serialization/deserialization for all data classes.

KEY LOGIC:
- JsonFormats: Custom JSON configurations (pretty print, lenient, ignore unknown)
- PolymorphicSerializers: Handle sealed class hierarchies with @SerialName
- ContextAdapters: Custom serializers for complex types (VirtualFile, Instant)
- FileSetSerializer: Convert between file sets and comma-separated paths
- Polymorphic configuration for TaskType sealed class
- Encoding/decoding with strict mode for production
- Null handling with default values
```

---

## **Module 7: Database Layer with Exposed**

### **7.1 Exposed Schema**
```
MODULE: com.codingagent.database

RESPONSIBILITY:
Define database schema using Exposed DSL for all persistence needs.

KEY LOGIC:
- DatabaseFactory: Initialize database connection with connection pool
- SessionsTable: UUID id, timestamps, userId, projectPath, activeModel enum
- TasksTable: UUID id, session reference, type, description, complexity, tokensUsed
- ContextCacheTable: String key, text value, level integer, expiration time
- Indices on foreign keys and frequently queried columns
- Enumeration mapping for ModelType
- Default values for timestamps
- Primary key constraints
```

### **7.2 Repository Layer**
```
MODULE: com.codingagent.database.repositories

RESPONSIBILITY:
Provide coroutine-safe data access methods for database operations.

KEY LOGIC:
- SessionRepository: Create session, update activity, find expired sessions
- TaskRepository: Insert task, query by session, aggregate statistics
- CacheRepository: Get/put cache entries, increment hit count, clean expired
- Suspending functions with withContext(Dispatchers.IO)
- Transaction wrapping for atomic operations
- Batch inserts for performance
- Query optimization with select/where conditions
- Mapping functions between database rows and domain objects
```

---

## **Module 8: Kotlin Project Configuration**

### **8.1 Gradle Build Structure**
```
MODULE: Project root build.gradle.kts

RESPONSIBILITY:
Configure multi-module Gradle build with Kotlin and necessary dependencies.

KEY LOGIC:
- Root project with subprojects for: core, intellij-plugin, server, common
- Kotlin JVM plugin with 1.9+ version
- Ktor BOM for version management
- Dependencies: ktor-server-core, ktor-websockets, ktor-serialization
- Exposed: core, jdbc, dao
- IntelliJ plugin development dependencies
- Kotlinx: coroutines, serialization, datetime
- Testing: kotest, testcontainers, mockk
- Version catalog for centralized dependency management
```

### **8.2 Application Configuration**
```
MODULE: src/main/resources/application.conf

RESPONSIBILITY:
External configuration for Ktor server and agent components.

KEY LOGIC:
- Ktor deployment: port, host, modules
- Database: driver class, JDBC URL, connection pool size
- Agent: local model URL and timeout, cloud API keys from environment
- Cache: expiration times, max size
- Thresholds: context warning levels, complexity limits
- Logging: levels, appenders
- Environment variable interpolation with ${?VAR} syntax
- Profile-specific configurations (dev, prod)
```

---

## **Module 9: Learning & Optimization**

### **9.1 Usage Tracker**
```
MODULE: com.codingagent.learning.tracker

RESPONSIBILITY:
Collect and store metrics on agent usage for analysis and optimization.

KEY LOGIC:
- UsageTracker: Intercept all task executions and record metrics
- MetricsCollector: Aggregate per-session, per-project, per-user statistics
- SwitchRecorder: Log each model switch with reason and context
- PerformanceDatabase: Exposed tables for analytics data
- Success rate calculation by task type
- User feedback correlation
- Export functionality for analysis
```

### **9.2 Threshold Optimizer**
```
MODULE: com.codingagent.learning.optimizer

RESPONSIBILITY:
Analyze historical data to adjust switching thresholds for better accuracy.

KEY LOGIC:
- ThresholdOptimizer: Query usage data for false positives/negatives
- ParameterTuning: Adjust context thresholds based on error rates
- UserPreferenceLearner: Adapt to individual user patterns
- A/B testing framework for threshold changes
- Gradual threshold adjustment with rollback capability
- Confidence scoring for threshold modifications
- Feedback loop integration
```

### **9.3 Pattern Learner**
```
MODULE: com.codingagent.learning.patterns

RESPONSIBILITY:
Identify common patterns in tasks and code to enable predictive behavior.

KEY LOGIC:
- PatternLearner: Sequence analysis of task types
- FrequencyAnalyzer: Identify most common operations per project
- ContextPredictor: Pre-load modules likely needed next
- CodePatternDetector: Recognize common coding patterns
- Markov chains for task sequence prediction
- Similarity matching for context reuse
- Proactive cache warming based on predictions
```

---

## **Module 10: Task Processing Pipeline**

### **10.1 Task Parser**
```
MODULE: com.codingagent.pipeline.parser

RESPONSIBILITY:
Parse natural language tasks to extract intent, requirements, and affected files.

KEY LOGIC:
- TaskParser: NLP-based intent extraction
- IntentClassifier: Categorize task (generation, debugging, refactoring)
- FileResolver: Identify affected files from task description
- RequirementExtractor: Pull explicit and implicit requirements
- ComplexityEstimator: Initial complexity assessment
- Structured output with Task data class
- Confidence scoring for parsed elements
```

### **10.2 Context Assembler**
```
MODULE: com.codingagent.pipeline.assembler

RESPONSIBILITY:
Gather and assemble required context based on task analysis.

KEY LOGIC:
- ContextAssembler: Coordinate context gathering from hierarchy
- RequirementAnalyzer: Map requirements to needed hierarchy levels
- PruningEngine: Remove irrelevant context based on task focus
- TokenCounter: Ensure assembled context fits model limits
- Fallback summarization when context too large
- Multi-level assembly for hybrid processing
- Cache checking before fresh assembly
```

### **10.3 Execution Engine**
```
MODULE: com.codingagent.pipeline.execution

RESPONSIBILITY:
Execute tasks with selected model and handle real-time streaming.

KEY LOGIC:
- ExecutionEngine: Main execution coordinator
- ModelSelector: Get optimal model from decision engine
- PromptBuilder: Construct prompts with assembled context
- StreamHandler: Process model response streams
- InterruptionManager: Handle user cancellations
- Mid-task switching: Preserve state during model change
- Error recovery with retry logic
- Timeout enforcement
```

### **10.4 Result Processor**
```
MODULE: com.codingagent.pipeline.processor

RESPONSIBILITY:
Parse model responses and prepare actionable results for IDE integration.

KEY LOGIC:
- ResultProcessor: Parse and validate model output
- CodeBlockExtractor: Identify and extract code sections
- DiffGenerator: Create file changes from original to new
- SyntaxValidator: Check extracted code for errors
- ChangeFormatter: Prepare changes for IDE application
- ExplanationFormatter: Format explanations with markdown
- Confidence scoring for generated code
- Error categorization for failed generations
```

---

## **Module 11: User Interface & Experience**

### **11.1 Chat Interface**
```
MODULE: com.codingagent.ui.chat

RESPONSIBILITY:
Provide natural language chat interface for agent interaction.

KEY LOGIC:
- ChatView: Message list with scrolling
- MessageRenderer: Markdown rendering with code highlighting
- InputField: Multi-line text input with send button
- ConversationHistory: Load and display previous messages
- TypingIndicator: Show when agent is generating
- ModelBadge: Visual indicator of current model
- CancelButton: Interrupt ongoing generation
- Copy buttons for code blocks
```

### **11.2 Configuration Interface**
```
MODULE: com.codingagent.ui.config

RESPONSIBILITY:
Allow user customization of agent behavior and preferences.

KEY LOGIC:
- SettingsView: Tabbed configuration panel
- ModelSettings: Default model selection, API keys, timeouts
- SwitchingSettings: Aggressiveness slider, thresholds
- PrivacySettings: Cloud enable/disable, data retention
- IntegrationSettings: IDE feature toggles
- ProfileManager: Save/load configuration profiles
- Validation: API key format checking
- Reset to defaults option
```

---

## **Module 12: Security & Privacy**

### **12.1 Data Protection**
```
MODULE: com.codingagent.security

RESPONSIBILITY:
Ensure sensitive data is protected during processing and storage.

KEY LOGIC:
- EncryptionManager: AES encryption for stored API keys
- SensitiveDataDetector: Regex patterns for keys, tokens, passwords
- RedactionEngine: Remove sensitive data before cloud transmission
- AuditLogger: Log all cloud transmissions (metadata only)
- ConsentManager: Track user consent for features
- DataRetentionPolicy: Automatic cleanup of old data
- Secure configuration loading from environment
```

### **12.2 Access Control**
```
MODULE: com.codingagent.security.access

RESPONSIBILITY:
Control access to agent features based on authentication and authorization.

KEY LOGIC:
- APIKeyManager: Secure storage and rotation of cloud API keys
- RateLimiter: Per-user/instance rate limiting
- QuotaManager: Track and enforce usage quotas
- UserAuthentication: Optional user login for multi-user setups
- PermissionChecker: Feature access based on subscription
- RequestValidation: Sanitize all incoming requests
- Audit trail for sensitive operations
```

---

## **Project Structure Overview**

```
com.codingagent/
├── core/
│   ├── orchestrator/
│   ├── session/
│   └── coroutines/
├── models/
│   └── wrappers/
├── switching/
│   ├── analyzer/
│   ├── monitor/
│   └── decision/
├── context/
│   ├── hierarchy/
│   ├── provider/
│   └── navigation/
├── intellij/
│   ├── services/
│   ├── features/
│   └── ui/
├── server/
│   ├── routes/
│   └── streaming/
├── kotlin/
│   └── analysis/
├── database/
│   └── repositories/
├── learning/
│   ├── tracker/
│   ├── optimizer/
│   └── patterns/
├── pipeline/
│   ├── parser/
│   ├── assembler/
│   ├── execution/
│   └── processor/
├── ui/
│   ├── chat/
│   └── config/
└── security/
    └── access/
```