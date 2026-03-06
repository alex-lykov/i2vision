# KOOG Coding Agent - Minimal Launcher + UI Implementation Task

## Task ID: LAUNCHER-UI-MINIMAL-V1
## Priority: HIGH
## Estimated Time: 3-4 Days
## Dependencies: core, switching, models modules

---

## 1. OVERVIEW

Create a minimal working launcher with a basic UI that can start the KOOG Coding Agent, display its status, accept simple tasks, and show outputs. This is the entry point for users to interact with the agent.

---

## 2. ARCHITECTURE

```
┌─────────────────────────────────────────────────────────────┐
│                    USER INTERFACE LAYER                      │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │  CLI Mode   │  │  GUI Mode   │  │  Status Indicators  │ │
│  └─────────────┘  └─────────────┘  └─────────────────────┘ │
├─────────────────────────────────────────────────────────────┤
│                     LAUNCHER CORE                            │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  - Agent Initialization                               │  │
│  │  - Model Switching Control                            │  │
│  │  - Task Routing                                       │  │
│  │  - Status Broadcasting                                │  │
│  └───────────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│                   EXTERNAL MODULES                           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐      │
│  │  core    │ │ switching│ │ models   │ │ context  │      │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘      │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. FILE STRUCTURE TO CREATE/MODIFY

```
koog-coding-agent/
├── launcher/
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       ├── Main.kt                          # Entry point (CLI or GUI selector)
│       ├── cli/
│       │   ├── CliLauncher.kt                # CLI implementation
│       │   └── CliCommands.kt                # Command handlers
│       ├── gui/
│       │   ├── GuiLauncher.kt                # GUI launcher
│       │   ├── components/
│       │   │   ├── StatusBar.kt              # Model status component
│       │   │   ├── TaskInput.kt              # Task input component
│       │   │   ├── OutputConsole.kt          # Output display component
│       │   │   └── QuickActions.kt           # Quick action buttons
│       │   └── MainWindow.kt                  # Main application window
│       └── core/
│           ├── AgentLauncher.kt               # Core launcher logic
│           ├── AgentClient.kt                 # Client for agent modules
│           ├── StatusListener.kt              # Status update listener
│           └── SwitchControl.kt               # Model switching control
├── ui/
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       ├── theme/
│       │   └── AppTheme.kt                    # UI theming
│       └── utils/
│           └── UIHelpers.kt                    # UI utilities
└── docs/
    └── launcher-ui-minimal.md                  # This document
```

---

## 4. FUNCTIONAL REQUIREMENTS

### 4.1 Core Launcher Functionality

```
FR-1: Agent Initialization
    - Load configuration from default location
    - Initialize core agent modules
    - Start background status monitoring
    - Handle initialization errors gracefully

FR-2: Model Status Tracking
    - Track current active model (local/cloud)
    - Monitor context usage percentage
    - Track loaded files count
    - Calculate and display confidence level
    - Update status every 1 second

FR-3: Model Switching
    - Allow manual switch between models
    - Display switch confirmation
    - Show reason for automatic switches
    - Maintain switch history

FR-4: Task Processing
    - Accept task descriptions
    - Route to appropriate module
    - Stream real-time output
    - Display execution time
    - Show token usage
```

### 4.2 CLI Interface

```
FR-5: CLI Commands
    - `> task description` - Process any task
    - `/status` - Show current status
    - `/switch [local|cloud]` - Switch models
    - `/context` - Show loaded context
    - `/help` - Show available commands
    - `/exit` - Exit application
    - `/clear` - Clear screen

FR-6: CLI Output Format
    - Color-coded output (green for success, yellow for warnings, red for errors)
    - Real-time streaming with progress indicators
    - Formatted tables for status display
    - Command history with up/down arrows
```

### 4.3 GUI Interface

```
FR-7: Main Window
    - Title: "KOOG Coding Agent"
    - Size: 900x600 pixels (default)
    - Resizable
    - Close button exits application

FR-8: Status Bar (Top)
    - Model indicator with color:
        ● GREEN - Local model active
        ● BLUE - Cloud model active  
        ● YELLOW - Switching in progress
        ● RED - Error state
    - Model name display
    - [SWITCH] button for manual toggle
    - Context usage progress bar
    - Files loaded count
    - Session time display

FR-9: Task Input Area
    - Multi-line text input (supports Shift+Enter for new line)
    - Placeholder: "What should I do? (e.g., 'Add error handling to login')"
    - [PROCESS] button - Execute with current model
    - [ANALYZE] button - Let system decide best model
    - [DEBUG] button - Enable debug mode for current task

FR-10: Quick Actions
    - Grid of 4 buttons:
        ┌──────────┬──────────┐
        │  Review  │ Refactor │
        ├──────────┼──────────┤
        │   Test   │ Document │
        └──────────┴──────────┘
    - Each button sends pre-defined task

FR-11: Output Console
    - Scrollable output area
    - Monospace font
    - Color-coded messages:
        • White: Normal output
        • Green: Success messages
        • Yellow: Warnings
        • Red: Errors
        • Blue: System messages
        • Gray: Debug info
    - Timestamp on each output line: [HH:MM:SS]
    - Clear button
    - Copy button for selected text
```

---

## 5. NON-FUNCTIONAL REQUIREMENTS

```
NFR-1: Performance
    - UI startup < 2 seconds
    - Status updates < 100ms latency
    - No UI freezing during task processing
    - Memory usage < 200MB

NFR-2: Reliability
    - Graceful handling of module failures
    - Auto-reconnect on module restart
    - Save unsaved output on crash
    - Configurable log levels

NFR-3: Usability
    - Intuitive for first-time users
    - Keyboard shortcuts for all actions
    - Tooltips on all buttons
    - Dark/Light theme support
    - Remember last window position/size

NFR-4: Compatibility
    - Windows 10/11
    - macOS 12+
    - Linux (Ubuntu 20.04+)
    - Minimum screen resolution: 1280x720
```

---

## 6. UI COMPONENT SPECIFICATIONS

### 6.1 StatusBar Component

```kotlin
// Properties
- currentModel: ModelType (LOCAL, CLOUD, SWITCHING, ERROR)
- contextUsage: Float (0.0 to 1.0)
- filesLoaded: Int
- sessionTime: String (HH:MM:SS)

// Events
- onSwitchClick: () -> Unit

// Visual
┌─────────────────────────────────────────────────────────────┐
│  ● LOCAL (CodeLlama-7b)  [SWITCH]  ███████░░░ 70%  📁 12  ⏱️ 00:05:23 │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 TaskInput Component

```kotlin
// Properties
- taskText: String
- isProcessing: Boolean

// Events
- onProcessClick: (String) -> Unit
- onAnalyzeClick: (String) -> Unit
- onDebugClick: (String) -> Unit

// Visual
┌─────────────────────────────────────────────────────┐
│ Add error handling to login function                │
│ that validates user credentials                      │
└─────────────────────────────────────────────────────┘
┌──────────┬──────────┬─────────┐
│ PROCESS  │ ANALYZE  │  DEBUG  │
└──────────┴──────────┴─────────┘
```

### 6.3 OutputConsole Component

```kotlin
// Properties
- outputLines: List<OutputLine>
- isAutoScroll: Boolean (default true)

// Methods
- append(text: String, type: OutputType)
- clear()
- copySelected()

// Visual
┌─────────────────────────────────────────────────────┐
│ [14:23:45] ▶ Processing task...                    │
│ [14:23:46] ✓ Using LOCAL model (confidence: 0.85)  │
│ [14:23:47] ⚠ Found 3 potential issues              │
│ [14:23:49] ✗ Error in line 42: Null pointer        │
│ [14:23:50] ℹ Switching to CLOUD for deeper analysis│
│ [14:23:52] ☁ Analyzing with GPT-4...                │
│ [14:24:01] ✓ Task complete (took 16s)              │
│ [14:24:01] 📊 Tokens used: 2,342                   │
└─────────────────────────────────────────────────────┘
[Clear] [Copy]
```

---

## 7. API / INTERFACES

### 7.1 Launcher Core Interface

```kotlin
interface AgentLauncher {
    fun initialize(config: Config): Result
    fun start()
    fun shutdown()
    fun getStatus(): AgentStatus
    fun processTask(task: String, mode: TaskMode): Flow<OutputEvent>
    fun switchModel(target: ModelType): Result
    fun addStatusListener(listener: StatusListener)
}

data class AgentStatus(
    val currentModel: ModelType,
    val contextUsage: Float,
    val filesLoaded: Int,
    val sessionTime: Duration,
    val confidence: Float,
    val lastSwitch: Instant?,
    val errors: List<String>
)

enum class TaskMode {
    CURRENT_MODEL,  // Use current model
    SMART_ANALYZE,  // Let system decide
    DEBUG           // Enable debug mode
}

sealed class OutputEvent {
    data class Standard(val text: String) : OutputEvent()
    data class Success(val text: String) : OutputEvent()
    data class Warning(val text: String) : OutputEvent()
    data class Error(val text: String) : OutputEvent()
    data class System(val text: String) : OutputEvent()
    data class Debug(val text: String) : OutputEvent()
    data class Progress(val percent: Int, val message: String) : OutputEvent()
    object Complete : OutputEvent()
}
```

### 7.2 StatusListener Interface

```kotlin
interface StatusListener {
    fun onModelChanged(newModel: ModelType, reason: String?)
    fun onContextUpdated(usage: Float, files: Int)
    fun onTaskStarted(task: String)
    fun onTaskCompleted(duration: Duration, tokensUsed: Int)
    fun onError(throwable: Throwable)
}
```

---

## 8. IMPLEMENTATION STEPS

### Phase 1: Core Launcher (Day 1)

```
Step 1.1: Create AgentLauncher.kt
    - Implement initialization logic
    - Connect to core module
    - Set up status monitoring

Step 1.2: Create AgentClient.kt
    - Implement task routing
    - Handle module communication
    - Error handling

Step 1.3: Create StatusListener.kt
    - Define listener interface
    - Implement status broadcasting
    - Set up update scheduler

Step 1.4: Create SwitchControl.kt
    - Manual switch logic
    - Connect to switching module
    - Switch history tracking
```

### Phase 2: CLI Interface (Day 2)

```
Step 2.1: Create Main.kt with mode selection
    - Parse command line args (--cli, --gui)
    - Launch appropriate interface

Step 2.2: Create CliLauncher.kt
    - Initialize readline support
    - Set up command parser
    - Implement color output

Step 2.3: Create CliCommands.kt
    - Implement all commands
    - Command history
    - Tab completion (bonus)
```

### Phase 3: GUI Components (Day 3)

```
Step 3.1: Setup Compose for Desktop
    - Configure build.gradle.kts
    - Add dependencies
    - Create MainWindow.kt

Step 3.2: Create StatusBar.kt
    - Model indicator with color
    - Switch button
    - Progress bar
    - Metrics display

Step 3.3: Create TaskInput.kt
    - Multi-line text field
    - Action buttons
    - Input validation

Step 3.4: Create OutputConsole.kt
    - LazyColumn for output
    - Color-coded lines
    - Auto-scroll
    - Clear/Copy buttons

Step 3.5: Create QuickActions.kt
    - Grid layout
    - Button actions
    - Tooltips
```

### Phase 4: Integration (Day 4)

```
Step 4.1: Connect UI to Launcher
    - Pass status updates to UI
    - Route task processing
    - Handle model switches

Step 4.2: Add real-time updates
    - Status polling (or WebSocket)
    - Output streaming
    - Progress indicators

Step 4.3: Error handling
    - Graceful degradation
    - Error dialogs
    - Recovery options

Step 4.4: Testing
    - Test all user flows
    - Cross-platform testing
    - Performance testing
```

---

## 9. ACCEPTANCE CRITERIA

```
AC-1: Can launch agent in both CLI and GUI modes
AC-2: Status bar correctly shows model and context
AC-3: Can manually switch between local and cloud
AC-4: Can enter and process simple tasks
AC-5: Output shows in real-time with correct colors
AC-6: Quick actions trigger appropriate tasks
AC-7: Application runs on all target platforms
AC-8: No crashes during normal operation
AC-9: Startup time < 2 seconds
AC-10: UI remains responsive during task processing
```

---

## 10. TEST CASES

```gherkin
Feature: Launcher UI
  Scenario: Start application in GUI mode
    Given the application is installed
    When I run "./gradlew :ui:run"
    Then the main window appears within 2 seconds
    And the status shows "LOCAL" model
    
  Scenario: Process a simple task
    Given the application is running
    When I enter "Create a hello world function" in task input
    And I click PROCESS
    Then output appears in real-time
    And task completion message shows
    
  Scenario: Switch models manually
    Given the application is running
    When I click the SWITCH button
    Then model indicator changes to cloud
    And confirmation message appears in output
    
  Scenario: Use quick actions
    Given the application is running
    When I click the "Review" button
    Then a code review task is processed
    And results appear in output
```

---

## 11. DELIVERABLES

```
📦 koog-coding-agent/
├── launcher/build/libs/launcher.jar
├── ui/build/libs/ui.jar
├── docs/launcher-ui-minimal.md
├── screenshots/
│   ├── cli-mode.png
│   ├── gui-main.png
│   └── gui-processing.png
└── demo/
    └── launcher-ui-demo.mp4
```

---

## 12. NOTES & CONSIDERATIONS

```
- Use Kotlin Compose for Desktop for GUI
- Use kotlinx.coroutines for async operations
- Implement proper threading (never block UI thread)
- Consider using Material 3 design system
- Add keyboard shortcuts for power users
- Remember to add license headers
- Document all public APIs
- Add logging for debugging
```

---

## 13. FUTURE ENHANCEMENTS (Not in scope)

```
- Settings/Configuration window
- Multi-tab interface
- Plugin system
- Remote agent connection
- Voice input
- Code editor integration
- Project workspace management
- Collaboration features
```

---

**Task Ready for Implementation** ✅

This document provides everything needed to build the minimal launcher + UI. The implementation can be done in parallel by different team members following the phased approach.