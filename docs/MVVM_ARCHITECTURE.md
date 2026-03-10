# MVVM Architecture - UI Refactoring

## Overview

The UI components have been refactored to follow **MVVM (Model-View-ViewModel)** pattern with **DTOs (Data Transfer Objects)** for clean separation of concerns.

## Architecture Layers

```
┌─────────────────────────────────────┐
│         View (Compose UI)           │
│  - MainWindow.kt                    │
│  - Terminal.kt                      │
│  - PerformanceCard.kt               │
│  - Other UI Components              │
└──────────────┬──────────────────────┘
               │ Observes State
               │ Calls Actions
┌──────────────▼──────────────────────┐
│      ViewModel Layer                 │
│  - MainViewModel                     │
│  - TerminalViewModel                │
│  - (Future: Component ViewModels)    │
└──────────────┬──────────────────────┘
               │ Uses DTOs
               │ Maps from Domain
┌──────────────▼──────────────────────┐
│         DTO Layer                   │
│  - TerminalEventDto                │
│  - AgentStatusDto                  │
│  - PerformanceMetricsDto           │
│  - ProjectDto                      │
│  - McpStatusDto                    │
└──────────────┬──────────────────────┘
               │ Maps from
┌──────────────▼──────────────────────┐
│      Domain Layer                   │
│  - AgentLauncher                    │
│  - AgentStatus                      │
│  - OutputEvent                      │
│  - Core Business Logic              │
└─────────────────────────────────────┘
```

## Key Components

### 1. DTOs (`gui/data/UiDtos.kt`)

**Purpose**: Pure data classes for UI layer, no business logic

- `TerminalEventDto`: Terminal events with type, message, timestamp
- `AgentStatusDto`: Agent status information for display
- `ModelTypeDto`: Model type enum for UI
- `PerformanceMetricsDto`: Performance data
- `ProjectDto`: Project information
- `McpStatusDto`: MCP connection status
- `TerminalStateDto`: Complete terminal state (events, input, processing)

### 2. ViewModels

#### MainViewModel (`gui/viewmodel/MainViewModel.kt`)

**Responsibilities:**
- Manages all UI state (terminal, status, MCP)
- Subscribes to domain layer streams
- Maps domain objects to DTOs
- Handles user actions (submit task, load project)
- Coordinates between components

**State Flows:**
- `terminalState: StateFlow<TerminalStateDto>`
- `agentStatus: StateFlow<AgentStatusDto?>`
- `mcpStatus: StateFlow<McpStatusDto>`
- `availableMcpTools: StateFlow<List<String>>`

#### TerminalViewModel (`gui/viewmodel/TerminalViewModel.kt`)

**Responsibilities:**
- Terminal-specific state management
- Delegates to MainViewModel
- Provides terminal-focused API

### 3. View Components

#### MainWindow (`gui/MainWindow.kt`)

**Before (Tightly Coupled):**
```kotlin
@Composable
fun MainWindow(agentLauncher: AgentLauncher, ...) {
    var events by remember { mutableStateOf<List<OutputEvent>>(...) }
    var status by remember { mutableStateOf(agentLauncher.getStatus()) }
    
    fun submitTask(...) {
        // Direct business logic in UI
        agentLauncher.processTask(...).collect { ... }
    }
}
```

**After (MVVM with DTOs):**
```kotlin
@Composable
fun MainWindow(agentLauncher: AgentLauncher, ...) {
    val mainViewModel = remember { MainViewModel(agentLauncher, scope) }
    val terminalViewModel = remember { TerminalViewModel(mainViewModel) }
    
    val terminalState by terminalViewModel.state.collectAsState()
    val agentStatus by mainViewModel.agentStatus.collectAsState()
    
    // UI only observes state, calls ViewModel actions
    Terminal(viewModel = terminalViewModel, ...)
}
```

#### Terminal (`gui/components/Terminal.kt`)

**Before:**
```kotlin
@Composable
fun Terminal(
    events: List<OutputEvent>,  // Domain object
    onClear: () -> Unit,
    onSubmit: (String, TaskMode) -> Unit
) {
    // Direct state management
    var text by remember { mutableStateOf("") }
}
```

**After:**
```kotlin
@Composable
fun Terminal(
    viewModel: TerminalViewModel  // ViewModel, not domain objects
) {
    val state by viewModel.state.collectAsState()
    
    // Uses DTOs
    items(state.events) { event ->  // TerminalEventDto
        // Display logic only
    }
}
```

## Benefits

### 1. **Separation of Concerns**
- UI components are purely declarative
- Business logic in ViewModels
- Domain objects isolated from UI

### 2. **Testability**
- ViewModels can be unit tested without UI
- DTOs are simple data classes
- UI components testable with mock ViewModels

### 3. **Maintainability**
- Changes to domain layer don't affect UI directly
- DTOs provide stable UI contracts
- Clear data flow: Domain → DTO → ViewModel → View

### 4. **Reusability**
- ViewModels can be shared across components
- DTOs can be used in different contexts
- UI components are more composable

### 5. **Type Safety**
- DTOs provide UI-specific types
- No direct dependency on domain enums in UI
- Clear mapping between layers

## Data Flow

### Task Submission Flow

```
User Input (Terminal UI)
    ↓
TerminalViewModel.submitTask()
    ↓
MainViewModel.submitTask()
    ↓
AgentLauncher.processTask() [Domain Layer]
    ↓
OutputEvent [Domain]
    ↓
MainViewModel.mapOutputEventToDto()
    ↓
TerminalEventDto [DTO]
    ↓
TerminalStateDto.events
    ↓
Terminal UI displays event
```

### Status Update Flow

```
AgentLauncher.getStatusStream() [Domain]
    ↓
AgentStatus [Domain]
    ↓
MainViewModel.mapToStatusDto()
    ↓
AgentStatusDto [DTO]
    ↓
agentStatus StateFlow
    ↓
PerformanceCard displays status
```

## File Structure

```
launcher/src/main/kotlin/gui/
├── data/
│   └── UiDtos.kt              # All DTOs
├── viewmodel/
│   ├── MainViewModel.kt       # Main ViewModel
│   └── TerminalViewModel.kt   # Terminal ViewModel
├── components/
│   ├── Terminal.kt            # Uses TerminalViewModel
│   ├── PerformanceCard.kt     # Uses AgentStatusDto
│   └── ...                    # Other components
└── MainWindow.kt              # Orchestrates ViewModels
```

## Migration Status

✅ **Completed:**
- DTO layer created
- MainViewModel implemented
- TerminalViewModel implemented
- MainWindow refactored
- Terminal component refactored
- PerformanceCard uses DTOs

🔄 **Future Enhancements:**
- Create ViewModels for other components (ProjectManagementPanel, etc.)
- Add ViewModel tests
- Consider using Compose ViewModel integration
- Add state persistence if needed

## Example: Adding a New Component

1. **Create DTO** (if needed):
```kotlin
data class MyComponentDto(
    val data: String,
    val status: ComponentStatus
)
```

2. **Add to MainViewModel**:
```kotlin
private val _myComponentState = MutableStateFlow<MyComponentDto?>(null)
val myComponentState: StateFlow<MyComponentDto?> = _myComponentState.asStateFlow()
```

3. **Use in UI**:
```kotlin
@Composable
fun MyComponent(viewModel: MainViewModel) {
    val state by viewModel.myComponentState.collectAsState()
    // Display state
}
```

This architecture ensures clean separation and makes the codebase more maintainable and testable.
