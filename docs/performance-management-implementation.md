# Performance Management Implementation Guide

## Overview

This document describes the complete performance monitoring and management system implemented in the KOOG Coding Agent. The system provides real-time performance tracking, automatic alerting, and the foundation for intelligent model switching.

## Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   ModelWrapper  │───▶│ PerformanceMonitor│───▶│ AgentLauncher   │
│                 │    │                  │    │                 │
│ - Records       │    │ - Tracks metrics  │    │ - Exposes API   │
│ - Measures     │    │ - Detects issues │    │ - Streams data  │
└─────────────────┘    └──────────────────┘    └─────────────────┘
                                                        │
                                                        ▼
                                              ┌─────────────────┐
                                              │      UI        │
                                              │                 │
                                              │ - Live updates  │
                                              │ - Warnings      │
                                              │ - Metrics       │
                                              └─────────────────┘
```

## Core Components

### 1. PerformanceMonitor (`switching:monitor`)

**Location**: `switching/monitor/src/main/kotlin/com/alyk/ai/koog/switching/monitor/PerformanceMonitor.kt`

**Responsibilities**:
- Track response times, token usage, and generation metrics
- Detect performance warning signals
- Maintain sliding window of recent metrics
- Generate alerts when thresholds exceeded

**Key Methods**:
```kotlin
// Record a model execution metric
fun recordMetric(responseTime: Long, tokensUsed: Int, tokensGenerated: Int)

// Get current performance statistics
fun getStats(): PerformanceStats

// Detect warning signals (slow response, high token ratio, truncation)
fun detectWarningSignals(): List<WarningSignal>

// Check for critical alerts
fun checkThresholds(): List<Alert>
```

**Thresholds**:
| Metric | Warning | Critical |
|--------|---------|----------|
| Response Time | >5s | >15s |
| Token Ratio | >2x used vs generated | - |
| Empty Output | 0 tokens generated | - |

### 2. ModelWrapper Integration (`models:wrappers`)

**Location**: `models/wrappers/src/main/kotlin/com/alyk/ai/koog/models/wrappers/ModelWrapper.kt`

**Integration Point**:
```kotlin
override suspend fun generate(prompt: String): String {
    var response: String
    val responseTime = measureTimeMillis {
        response = agent.run(prompt)
    }
    
    // Record performance metrics
    val tokensUsed = estimateTokens(prompt)
    val tokensGenerated = estimateTokens(response)
    performanceMonitor?.recordMetric(responseTime, tokensUsed, tokensGenerated)
    
    return response
}
```

**Features**:
- Automatic performance tracking for all model executions
- Token estimation (currently rough: text.length / 4)
- Seamless integration with existing model wrapper logic

### 3. Real-time Status Streaming (`launcher`)

**Location**: `launcher/src/main/kotlin/Main.kt`

**Key Implementation**:
```kotlin
override fun getStatusStream(): Flow<AgentStatus> {
    return flow {
        while (currentCoroutineContext().isActive) {
            emit(getStatus())
            delay(1000) // Update every second
        }
    }.flowOn(Dispatchers.Default)
}
```

**Features**:
- Real-time status updates every second
- Automatic cancellation on app shutdown
- Coroutine-safe streaming

### 4. UI Integration (`gui`)

**Location**: `launcher/src/main/kotlin/gui/MainWindow.kt`

**Real-time Subscription**:
```kotlin
// Subscribe to real-time status updates
LaunchedEffect(agentLauncher) {
    agentLauncher.getStatusStream().collect { newStatus ->
        status = newStatus
    }
}
```

**PerformanceCard Component**:
- Live response time display with color coding
- Real-time performance warnings
- Model status indicators
- Session time tracking

## Data Structures

### PerformanceStats
```kotlin
data class PerformanceStats(
    val totalRequests: Int = 0,
    val avgResponseTimeMs: Long = 0,
    val maxResponseTimeMs: Long = 0,
    val totalTokensUsed: Int = 0,
    val totalTokensGenerated: Int = 0
)
```

### WarningSignal
```kotlin
data class WarningSignal(
    val type: SignalType,
    val severity: Severity,
    val message: String
)

enum class SignalType {
    SLOW_RESPONSE, HIGH_TOKEN_RATIO, TRUNCATION
}

enum class Severity {
    LOW, MEDIUM, HIGH
}
```

### Alert
```kotlin
data class Alert(
    val message: String,
    val threshold: String,
    val currentValue: Double
)
```

### AgentStatus
```kotlin
data class AgentStatus(
    val currentModel: ModelType,
    val contextUsage: Float,
    val filesLoaded: Int,
    val sessionTime: Duration,
    val confidence: Float,
    val lastSwitch: Instant?,
    val errors: List<String>,
    val avgResponseTimeMs: Long = 0,
    val performanceWarnings: List<String> = emptyList(),
    val hasPerformanceAlert: Boolean = false,
    val currentModelId: String = ""
)
```

## Testing Guide

### 1. Unit Testing PerformanceMonitor

**Test File**: `switching/monitor/src/test/kotlin/PerformanceMonitorTest.kt`

```kotlin
class PerformanceMonitorTest {
    private lateinit var monitor: PerformanceMonitor

    @Before
    fun setUp() {
        monitor = PerformanceMonitor()
    }

    @Test
    fun `should record metrics correctly`() {
        monitor.recordMetric(1000, 100, 50)
        val stats = monitor.getStats()
        
        assertEquals(1, stats.totalRequests)
        assertEquals(1000, stats.avgResponseTimeMs)
        assertEquals(100, stats.totalTokensUsed)
        assertEquals(50, stats.totalTokensGenerated)
    }

    @Test
    fun `should detect slow response warnings`() {
        monitor.recordMetric(6000, 100, 50) // >5s threshold
        val warnings = monitor.detectWarningSignals()
        
        assertTrue(warnings.isNotEmpty())
        assertEquals(SignalType.SLOW_RESPONSE, warnings.first().type)
    }

    @Test
    fun `should detect high token ratio warnings`() {
        monitor.recordMetric(1000, 200, 50) // 4x ratio >2x threshold
        val warnings = monitor.detectWarningSignals()
        
        val tokenWarning = warnings.find { it.type == SignalType.HIGH_TOKEN_RATIO }
        assertNotNull(tokenWarning)
    }
}
```

### 2. Integration Testing ModelWrapper

**Test File**: `models/wrappers/src/test/kotlin/LocalModelWrapperTest.kt`

```kotlin
class LocalModelWrapperTest {
    private lateinit var performanceMonitor: PerformanceMonitor
    private lateinit var modelWrapper: LocalModelWrapper

    @Before
    fun setUp() {
        performanceMonitor = PerformanceMonitor()
        modelWrapper = LocalModelWrapper(
            modelName = "test-model",
            maxContextLength = 4096,
            performanceMonitor = performanceMonitor
        )
    }

    @Test
    fun `should record performance metrics during generation`() {
        // Execute a generation
        val response = modelWrapper.generate("test prompt")
        
        // Verify metrics were recorded
        val stats = performanceMonitor.getStats()
        assertTrue(stats.totalRequests > 0)
        assertTrue(stats.avgResponseTimeMs > 0)
    }
}
```

### 3. UI Testing Real-time Updates

**Test Approach**: Manual testing with performance simulation

```kotlin
// In a test environment or debug mode
class PerformanceSimulator {
    fun simulateSlowResponse() {
        // Simulate a slow model response
        delay(6000) // 6 seconds
        // This should trigger a slow response warning
    }
    
    fun simulateHighTokenRatio() {
        // Simulate high token ratio scenario
        // Large prompt, small response
    }
}
```

### 4. Manual Testing Procedures

#### A. Performance Monitoring Verification

1. **Start the Application**:
   ```bash
   ./gradlew :launcher:run
   ```

2. **Verify Real-time Updates**:
   - Open the GUI
   - Observe the Performance Card
   - Session timer should increment every second
   - Response time should update after tasks

3. **Trigger Performance Warnings**:
   - Submit a complex task that takes >5 seconds
   - Verify warning appears in Performance Card
   - Check color coding (orange for warning, red for critical)

#### B. Model Performance Testing

1. **Load a Large Project**:
   - Add a project with many files
   - Observe context usage percentage
   - Monitor response times as project size increases

2. **Test Model Switching**:
   - Switch between available models
   - Verify performance metrics reset/update appropriately
   - Check model indicator changes

#### C. Stress Testing

1. **High Volume Tasks**:
   ```kotlin
   // Submit multiple tasks rapidly
   repeat(10) {
       submitTask("Generate Kotlin code for class ${it}", TaskMode.CURRENT_MODEL)
   }
   ```

2. **Memory Leak Testing**:
   - Run application for extended period
   - Monitor memory usage
   - Verify performance metrics don't grow indefinitely

### 5. Performance Benchmarking

#### Response Time Benchmarks

| Model | Small Task | Medium Task | Large Task |
|-------|------------|-------------|------------|
| qwen3:4b | <2s | <5s | <10s |
| gpt-oss:20b | <1s | <3s | <8s |

#### Token Efficiency Benchmarks

| Scenario | Input Tokens | Output Tokens | Ratio |
|----------|--------------|---------------|-------|
| Code Generation | 100 | 200 | 0.5x |
| Debugging | 200 | 50 | 4.0x |
| Refactoring | 300 | 150 | 2.0x |

## Configuration

### Threshold Adjustment

Edit thresholds in `PerformanceMonitor.kt`:

```kotlin
companion object {
    const val SLOW_RESPONSE_THRESHOLD_MS = 5000L  // 5 seconds
    const val VERY_SLOW_RESPONSE_THRESHOLD_MS = 15000L  // 15 seconds
    const val HIGH_TOKEN_RATIO_THRESHOLD = 2.0  // 2x tokens used vs generated
    const val SLIDING_WINDOW_SIZE = 10  // Last 10 requests for averages
}
```

### Model-Specific Thresholds

Future enhancement: Different thresholds per model:

```kotlin
data class ModelThresholds(
    val slowResponseThreshold: Long,
    val highTokenRatioThreshold: Double
)

val modelThresholds = mapOf(
    "qwen3:4b" to ModelThresholds(5000L, 2.0),
    "gpt-oss:20b" to ModelThresholds(3000L, 1.5)
)
```

## Troubleshooting

### Common Issues

1. **Performance Data Not Updating**:
   - Verify `PerformanceMonitor` is injected into `ModelWrapper`
   - Check `getStatusStream()` is being called
   - Ensure `LaunchedEffect` is properly set up in UI

2. **Missing Warnings**:
   - Verify thresholds are appropriate for your hardware
   - Check token estimation is working
   - Ensure metrics are being recorded

3. **High Memory Usage**:
   - Monitor sliding window size
   - Check for metric accumulation
   - Verify proper cleanup on shutdown

### Debug Mode

Enable debug logging:

```kotlin
// In PerformanceMonitor
fun recordMetric(responseTime: Long, tokensUsed: Int, tokensGenerated: Int) {
    if (DEBUG) {
        println("Recording metric: ${responseTime}ms, ${tokensUsed} used, ${tokensGenerated} generated")
    }
    // ... rest of implementation
}
```

## Future Enhancements

### Planned Features

1. **Persistent Storage**: Database storage for historical metrics
2. **Adaptive Thresholds**: Machine learning-based threshold adjustment
3. **Performance-based Switching**: Automatic model switching on performance degradation
4. **Advanced Analytics**: Trend analysis and prediction
5. **Performance Profiling**: Detailed performance breakdowns

### Implementation Roadmap

1. **Phase 1**: Persistent data storage (current priority)
2. **Phase 2**: Performance-based model switching
3. **Phase 3**: Advanced analytics and trend visualization
4. **Phase 4**: Adaptive thresholds and machine learning

## API Reference

### PerformanceMonitor Public API

```kotlin
class PerformanceMonitor {
    fun recordMetric(responseTime: Long, tokensUsed: Int, tokensGenerated: Int)
    fun getStats(): PerformanceStats
    fun detectWarningSignals(): List<WarningSignal>
    fun checkThresholds(): List<Alert>
}
```

### AgentLauncher Performance API

```kotlin
interface AgentLauncher {
    fun getStatus(): AgentStatus
    fun getStatusStream(): Flow<AgentStatus>
    // ... other methods
}
```

### UI Performance Components

```kotlin
@Composable
fun PerformanceCard(status: AgentStatus, modifier: Modifier = Modifier)

@Composable
fun ProjectPerformanceCard(
    projectRepository: ProjectRepository,
    status: AgentStatus,
    onProjectSelected: (Project) -> Unit,
    modifier: Modifier = Modifier
)
```

---

This implementation provides a solid foundation for performance monitoring and management. The real-time streaming architecture ensures immediate feedback on performance issues, while the modular design allows for easy extension and customization.
